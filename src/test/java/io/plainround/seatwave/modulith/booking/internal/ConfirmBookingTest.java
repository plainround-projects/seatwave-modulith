package io.plainround.seatwave.modulith.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.H2Deployable;
import io.plainround.seatwave.modulith.booking.BookingConfirmed;
import io.plainround.seatwave.modulith.booking.Bookings.ConfirmFailure;
import io.plainround.seatwave.modulith.booking.Bookings.Confirmed;
import io.plainround.seatwave.modulith.payment.Payments.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The booking saga in one deployable: its three transactions, its compensations, its sweeper. */
class ConfirmBookingTest {

  private static final long FAN = 1001;
  private static final String SEAT = "A-7";
  private static final String PROVIDER_KEY_PREFIX = "seatwave-hold-";
  private static final Money PRICE = new Money(new BigDecimal("80.00"), "USD");
  private static final Duration PAST_THE_HOLD = Duration.ofSeconds(301);
  private static final Instant SHOW_STARTS = Instant.parse("2026-12-08T20:20:51.783Z");

  @Test
  void confirmSellsTheSeatInThreeLocalTransactions() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      UUID hold = deployable.holdSeat(FAN, SEAT);
      int commitsBefore = deployable.database().commits();

      Result<Confirmed, ConfirmFailure> outcome = deployable.bookings().confirm(hold, FAN);

      assertThat(outcome.discardError())
          .hasValueSatisfying(sale -> assertThat(sale.seatLabel()).isEqualTo(SEAT));
      assertThat(deployable.database().commits() - commitsBefore).isEqualTo(3);
      assertThat(deployable.column("SELECT state FROM booking.booking_sagas"))
          .containsExactly("COMPLETED");
      assertThat(deployable.column("SELECT state FROM inventory.holds"))
          .containsExactly("CONFIRMED");
      assertThat(deployable.column("SELECT price_paid FROM booking.bookings"))
          .containsExactly("80.00");
      assertThat(deployable.column("SELECT count(*) FROM notification.emails"))
          .containsExactly("1");
    }
  }

  @Test
  void declinedCardReleasesTheSeatAndCompensates() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      UUID hold = deployable.holdSeat(FAN, SEAT);
      deployable.decline(PROVIDER_KEY_PREFIX + hold);

      Result<Confirmed, ConfirmFailure> outcome = deployable.bookings().confirm(hold, FAN);

      assertThat(failureOf(outcome)).isInstanceOf(ConfirmFailure.Failed.class);
      assertThat(failureOf(outcome).reason()).isEqualTo("card declined by the provider");
      assertThat(deployable.column("SELECT state FROM booking.booking_sagas"))
          .containsExactly("COMPENSATED");
      assertThat(deployable.column("SELECT state FROM inventory.holds"))
          .containsExactly("RELEASED");
      assertThat(deployable.column("SELECT count(*) FROM booking.bookings")).containsExactly("0");
    }
  }

  @Test
  void expiredHoldIsRefusedAndNothingIsChargedOrRecorded() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      UUID hold = deployable.holdSeat(FAN, SEAT);
      deployable.advance(PAST_THE_HOLD);

      Result<Confirmed, ConfirmFailure> outcome = deployable.bookings().confirm(hold, FAN);

      assertThat(failureOf(outcome)).isEqualTo(new ConfirmFailure.Refused("hold is expired"));
      assertThat(deployable.column("SELECT count(*) FROM payment.payments")).containsExactly("0");
      assertThat(deployable.column("SELECT count(*) FROM booking.booking_sagas"))
          .containsExactly("0");
    }
  }

  @Test
  void anotherFansHoldIdLocksNothing() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      UUID hold = deployable.holdSeat(FAN, SEAT);
      long otherFan = 2002;

      Result<Confirmed, ConfirmFailure> outcome = deployable.bookings().confirm(hold, otherFan);

      assertThat(failureOf(outcome))
          .isEqualTo(new ConfirmFailure.Refused("no such hold for this fan"));
      assertThat(deployable.column("SELECT state FROM inventory.holds")).containsExactly("ACTIVE");
    }
  }

  @Test
  void failingListenerRollsTheSaleBackAndLeavesAStuckSaga() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      deployable
          .events()
          .subscribe(
              BookingConfirmed.class,
              confirmed -> {
                throw new IllegalStateException("listener failed");
              });
      UUID hold = deployable.holdSeat(FAN, SEAT);

      assertThatThrownBy(() -> deployable.bookings().confirm(hold, FAN))
          .hasMessage("listener failed");

      assertThat(deployable.column("SELECT state FROM booking.booking_sagas"))
          .containsExactly("PAYMENT_REQUESTED");
      assertThat(deployable.column("SELECT state FROM inventory.holds"))
          .containsExactly("CONFIRMING");
      assertThat(deployable.column("SELECT state FROM payment.payments"))
          .containsExactly("CAPTURED");
      assertThat(deployable.column("SELECT count(*) FROM notification.emails"))
          .containsExactly("0");
    }
  }

  @Test
  void seatTakenAwayAfterPaymentIsRefundedAndCompensated() throws SQLException, IOException {
    AtomicReference<H2Deployable> running = new AtomicReference<>();
    // An operator releases the hold while the provider is answering: the seat is gone after
    // payment, which the semantic lock otherwise prevents.
    try (H2Deployable deployable =
        H2Deployable.start(key -> releaseBehindTheSagasBack(running.get()))) {
      running.set(deployable);
      UUID hold = deployable.holdSeat(FAN, SEAT);

      Result<Confirmed, ConfirmFailure> outcome = deployable.bookings().confirm(hold, FAN);

      assertThat(failureOf(outcome).reason())
          .isEqualTo("the seat was taken away after payment; the payment was refunded");
      assertThat(deployable.column("SELECT state FROM booking.booking_sagas"))
          .containsExactly("COMPENSATED");
      assertThat(deployable.column("SELECT state FROM payment.payments"))
          .containsExactly("REFUNDED");
      assertThat(deployable.column("SELECT count(*) FROM booking.bookings")).containsExactly("0");
    }
  }

  @Test
  void sweeperFinishesTheSaleThatAFailingListenerRolledBack() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      boolean[] failNext = {true};
      deployable
          .events()
          .subscribe(
              BookingConfirmed.class,
              confirmed -> {
                if (failNext[0]) {
                  failNext[0] = false;
                  throw new IllegalStateException("listener failed once");
                }
              });
      UUID hold = deployable.holdSeat(FAN, SEAT);
      assertThatThrownBy(() -> deployable.bookings().confirm(hold, FAN))
          .isInstanceOf(IllegalStateException.class);
      deployable.advance(PAST_THE_HOLD);

      List<Result<Confirmed, ConfirmFailure>> finished =
          deployable.bookings().resumeStuckSagas(H2Deployable.HOLD_LENGTH);

      assertThat(finished).hasSize(1);
      assertThat(finished.getFirst().discardError()).isPresent();
      assertThat(deployable.column("SELECT state FROM payment.payments"))
          .containsExactly("CAPTURED");
      assertThat(deployable.column("SELECT count(*) FROM notification.emails"))
          .containsExactly("1");
    }
  }

  @Test
  void sweeperLeavesASagaThatMovedRecentlyAlone() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      UUID hold = deployable.holdSeat(FAN, SEAT);
      UUID booking = UUID.randomUUID();
      deployable.inventory().lockForPayment(hold, FAN, booking);
      insertSaga(deployable, booking, hold, SagaState.PAYMENT_REQUESTED, deployable.now());

      List<Result<Confirmed, ConfirmFailure>> finished =
          deployable.bookings().resumeStuckSagas(H2Deployable.HOLD_LENGTH);

      assertThat(finished).isEmpty();
      assertThat(deployable.column("SELECT state FROM booking.booking_sagas"))
          .containsExactly("PAYMENT_REQUESTED");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = SagaState.class,
      names = {"COMPLETED", "COMPENSATED"},
      mode = EnumSource.Mode.EXCLUDE)
  void sweeperFinishesASagaStuckInEveryState(SagaState stuckIn) throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      UUID hold = deployable.holdSeat(FAN, SEAT);
      UUID booking = UUID.randomUUID();
      arrangeWhatTheStateImplies(deployable, stuckIn, hold, booking);
      insertSaga(deployable, booking, hold, stuckIn, deployable.now());
      deployable.advance(PAST_THE_HOLD);

      List<Result<Confirmed, ConfirmFailure>> finished =
          deployable.bookings().resumeStuckSagas(H2Deployable.HOLD_LENGTH);

      // A saga that never took the lock finds its hold expired; one that was paid keeps the seat;
      // every other gives the seat back.
      List<String> expected =
          switch (stuckIn) {
            case SEAT_HELD -> List.of("COMPENSATED", "ACTIVE");
            case PAYMENT_REQUESTED, PAYMENT_SUCCEEDED -> List.of("COMPLETED", "CONFIRMED");
            case PAYMENT_FAILED, REFUND_REQUESTED, PAYMENT_REFUNDED, COMPLETED, COMPENSATED ->
                List.of("COMPENSATED", "RELEASED");
          };
      assertThat(finished).as("sagas the sweeper found").hasSize(1);
      assertThat(deployable.column("SELECT state FROM booking.booking_sagas"))
          .containsExactly(expected.getFirst());
      assertThat(deployable.column("SELECT state FROM inventory.holds"))
          .containsExactly(expected.getLast());
    }
  }

  @Test
  void paymentRefusesToChargeInsideATransaction() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      assertThatThrownBy(() ->
              deployable
                  .database()
                  .inTransaction(
                      connection -> deployable.payments().charge("key", UUID.randomUUID(), PRICE)))
          .hasMessage("charge must be called outside any transaction");
    }
  }

  @Test
  void secondLiveClaimOnOneSeatIsRefused() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      deployable.holdSeat(FAN, SEAT);

      String refusal =
          deployable
              .inventory()
              .hold(2002, H2Deployable.ARENA_NIGHT, SEAT)
              .map(held -> "held")
              .recoverError(refused -> refused.reason());

      assertThat(refusal).isEqualTo("seat A-7 is already claimed");
    }
  }

  @Test
  void holdOfASeatTheCatalogDoesNotOfferIsRefused() throws SQLException, IOException {
    try (H2Deployable deployable = H2Deployable.start()) {
      String refusal =
          deployable
              .inventory()
              .hold(FAN, H2Deployable.ARENA_NIGHT, "Z-1")
              .map(held -> "held")
              .recoverError(refused -> refused.reason());

      assertThat(refusal).isEqualTo("no seat Z-1 in event 1");
    }
  }

  private static ConfirmFailure failureOf(Result<Confirmed, ConfirmFailure> outcome) {
    return outcome
        .map(confirmed -> (ConfirmFailure) new ConfirmFailure.Refused("unexpected sale"))
        .recoverError(failure -> failure);
  }

  private static boolean releaseBehindTheSagasBack(H2Deployable deployable) {
    try {
      deployable.execute(
          "UPDATE inventory.holds SET state = 'RELEASED' WHERE state = 'CONFIRMING'");
      return false;
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  // What each state says has happened already, arranged through the modules' interfaces.
  private static void arrangeWhatTheStateImplies(
      H2Deployable deployable, SagaState stuckIn, UUID hold, UUID booking) throws SQLException {
    if (stuckIn == SagaState.SEAT_HELD) {
      return;
    }
    deployable.inventory().lockForPayment(hold, FAN, booking);
    String key = PROVIDER_KEY_PREFIX + hold;
    switch (stuckIn) {
      case PAYMENT_SUCCEEDED -> deployable.payments().charge(key, booking, PRICE);
      case REFUND_REQUESTED -> {
        deployable.payments().charge(key, booking, PRICE);
        deployable.execute("UPDATE inventory.holds SET state = 'RELEASED' WHERE hold_id = ?", hold);
      }
      case PAYMENT_REFUNDED -> {
        deployable.payments().charge(key, booking, PRICE);
        deployable.payments().refund(key);
      }
      case SEAT_HELD, PAYMENT_REQUESTED, PAYMENT_FAILED, COMPLETED, COMPENSATED -> {
        // Locked and nothing else: no money has moved yet, or none ever will.
      }
    }
  }

  private static void insertSaga(
      H2Deployable deployable, UUID booking, UUID hold, SagaState state, Instant updatedAt)
      throws SQLException {
    deployable.execute(
        """
        INSERT INTO booking.booking_sagas (booking_id, hold_id, customer_id, event_id, seat_label,
          price, currency, show_starts_at, state, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        booking,
        hold,
        FAN,
        H2Deployable.ARENA_NIGHT,
        SEAT,
        PRICE.amount(),
        PRICE.currency(),
        SHOW_STARTS.atOffset(ZoneOffset.UTC),
        state.name(),
        updatedAt.atOffset(ZoneOffset.UTC));
  }
}
