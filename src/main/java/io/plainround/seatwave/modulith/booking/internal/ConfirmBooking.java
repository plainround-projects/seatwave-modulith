package io.plainround.seatwave.modulith.booking.internal;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.booking.BookingConfirmed;
import io.plainround.seatwave.modulith.booking.Bookings;
import io.plainround.seatwave.modulith.booking.internal.BookingSagas.Saga;
import io.plainround.seatwave.modulith.catalog.Catalog.SeatOffer;
import io.plainround.seatwave.modulith.inventory.Inventory.ClaimedSeat;
import io.plainround.seatwave.modulith.payment.Payments.Captured;
import io.plainround.seatwave.modulith.payment.Payments.Declined;
import io.plainround.seatwave.modulith.payment.Payments.Money;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.DomainEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The confirm, as Module 28's saga shrinks inside one deployable: its eight states, in three local
 * transactions instead of eight. T1 locks the seat and records the saga (one transaction across
 * inventory and booking); the charge runs in payment's own transaction, outside any other; T2 sells
 * (across inventory, booking and every in-process listener) or gives the seat back. The sweeper
 * finishes a saga from whatever state a crash left it in. Public only so the module's interface can
 * create it. Thread-safe: no mutable state.
 */
public final class ConfirmBooking implements Bookings {

  // Module 7's provider key, so a repeated charge for one hold is answered with the first one.
  private static final String PROVIDER_KEY_PREFIX = "seatwave-hold-";

  private final Database database;
  private final Clock clock;
  private final DomainEvents events;
  private final Collaborators modules;
  private final BookingSagas sagas;

  /** Creates the confirm over the other modules' interfaces. */
  public ConfirmBooking(
      Database database, Clock clock, DomainEvents events, Collaborators collaborators) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.events = Objects.requireNonNull(events, "events");
    this.modules = Objects.requireNonNull(collaborators, "collaborators");
    this.sagas = new BookingSagas(database, clock);
  }

  @Override
  public Result<Confirmed, ConfirmFailure> confirm(UUID holdId, long customerId) {
    Objects.requireNonNull(holdId, "holdId");
    UUID bookingId = UUID.randomUUID();
    // T1: the lock and the saga row commit together, or neither does.
    Result<Saga, ConfirmFailure> started =
        database.inTransaction(connection ->
            modules
                .inventory()
                .lockForPayment(holdId, customerId, bookingId)
                .<ConfirmFailure>mapError(refusal -> new ConfirmFailure.Refused(refusal.reason()))
                .map(seat -> {
                  Saga held = sagas.begin(bookingId, holdId, customerId, seat, offerFor(seat));
                  return sagas.move(held, SagaState.PAYMENT_REQUESTED);
                }));
    return started.flatMap(this::finish);
  }

  @Override
  public List<Result<Confirmed, ConfirmFailure>> resumeStuckSagas(Duration olderThan) {
    Objects.requireNonNull(olderThan, "olderThan");
    List<Result<Confirmed, ConfirmFailure>> finished = new ArrayList<>();
    for (Saga stuck : sagas.stuck(clock.instant().minus(olderThan))) {
      finished.add(finish(stuck));
    }
    return List.copyOf(finished);
  }

  /** Drives a saga from its current state to a final one; the sweeper's entry and the confirm's. */
  private Result<Confirmed, ConfirmFailure> finish(Saga saga) {
    return switch (saga.state()) {
      case SEAT_HELD -> lockThenCharge(saga);
      case PAYMENT_REQUESTED -> charge(saga);
      case PAYMENT_SUCCEEDED -> sell(saga);
      case PAYMENT_FAILED -> giveBack(saga, "payment failed; the seat went back on sale");
      case REFUND_REQUESTED -> refund(saga);
      case PAYMENT_REFUNDED -> giveBack(saga, "payment refunded; the seat went back on sale");
      case COMPLETED -> Result.success(new Confirmed(saga.bookingId(), saga.seat().seatLabel()));
      case COMPENSATED ->
          Result.error(new ConfirmFailure.Failed(saga.bookingId(), "compensated earlier"));
    };
  }

  // Only a saga carried over from Module 28's services is found in SEAT_HELD: T1 never commits it.
  private Result<Confirmed, ConfirmFailure> lockThenCharge(Saga saga) {
    Result<Saga, ConfirmFailure> locked =
        database.inTransaction(connection ->
            modules
                .inventory()
                .lockForPayment(saga.holdId(), saga.customerId(), saga.bookingId())
                .map(seat -> sagas.move(saga, SagaState.PAYMENT_REQUESTED))
                .<ConfirmFailure>mapError(refusal -> {
                  sagas.move(saga, SagaState.COMPENSATED);
                  return new ConfirmFailure.Refused(refusal.reason());
                }));
    return locked.flatMap(this::charge);
  }

  // The pivot (Module 28): outside every transaction, because it calls the provider.
  private Result<Confirmed, ConfirmFailure> charge(Saga saga) {
    Result<Captured, Declined> charged =
        modules
            .payments()
            .charge(
                PROVIDER_KEY_PREFIX + saga.holdId(),
                saga.bookingId(),
                new Money(saga.offer().price(), saga.offer().currency()));
    return charged
        .map(captured -> sell(saga))
        .recoverError(declined -> giveBack(saga, declined.reason()));
  }

  // T2: confirm the seat, write the booking, complete the saga and tell the listeners, at once.
  private Result<Confirmed, ConfirmFailure> sell(Saga saga) {
    Instant confirmedAt = clock.instant();
    Saga after =
        database.inTransaction(connection -> {
          Saga paid =
              saga.state() == SagaState.PAYMENT_SUCCEEDED
                  ? saga
                  : sagas.move(saga, SagaState.PAYMENT_SUCCEEDED);
          Result<ClaimedSeat, ?> sold =
              modules.inventory().confirm(saga.holdId(), saga.bookingId());
          if (sold.isError()) {
            // Paid, and the seat is gone: Module 28's interview question; compensate the payment.
            return sagas.move(paid, SagaState.REFUND_REQUESTED);
          }
          sagas.recordBooking(paid, confirmedAt);
          Saga completed = sagas.move(paid, SagaState.COMPLETED);
          // Delivered now, inside this transaction: a listener that fails undoes the sale too.
          events.publish(
              new BookingConfirmed(
                  saga.bookingId(),
                  saga.customerId(),
                  saga.seat().eventId(),
                  saga.seat().seatLabel(),
                  saga.offer().showStartsAt(),
                  confirmedAt));
          return completed;
        });
    return finish(after);
  }

  // The refund is a provider call, so it runs between transactions, like the charge.
  private Result<Confirmed, ConfirmFailure> refund(Saga saga) {
    modules.payments().refund(PROVIDER_KEY_PREFIX + saga.holdId());
    return giveBack(saga, "the seat was taken away after payment; the payment was refunded");
  }

  // T2': release the seat and end the saga, passing through PAYMENT_FAILED after a decline and
  // PAYMENT_REFUNDED after a refund, as Module 28's transition table does.
  private Result<Confirmed, ConfirmFailure> giveBack(Saga saga, String reason) {
    database.inTransaction(connection -> {
      Saga undone = saga;
      if (saga.state() == SagaState.PAYMENT_REQUESTED) {
        undone = sagas.move(saga, SagaState.PAYMENT_FAILED);
      } else if (saga.state() == SagaState.REFUND_REQUESTED) {
        undone = sagas.move(saga, SagaState.PAYMENT_REFUNDED);
      }
      modules.inventory().release(saga.holdId(), saga.bookingId());
      return sagas.move(undone, SagaState.COMPENSATED);
    });
    return Result.error(new ConfirmFailure.Failed(saga.bookingId(), reason));
  }

  private SeatOffer offerFor(ClaimedSeat seat) {
    return modules
        .catalog()
        .offerFor(seat.eventId(), seat.seatLabel())
        // A held seat the catalog does not know is a bug; throwing rolls the lock back too.
        .orElseThrow(() -> new IllegalStateException("no offer for " + seat.seatLabel()));
  }
}
