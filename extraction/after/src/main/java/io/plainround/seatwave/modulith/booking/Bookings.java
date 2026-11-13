package io.plainround.seatwave.modulith.booking;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.booking.internal.BookingOutbox;
import io.plainround.seatwave.modulith.booking.internal.ConfirmBooking;
import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.Inventory;
import io.plainround.seatwave.modulith.payment.Payments;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.DomainEvents;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The booking module's public interface: turning a fan's hold into a paid booking. It owns the
 * schema {@code booking}, runs Module 28's booking saga with its eight states, and publishes {@link
 * BookingConfirmed}. Safe for concurrent use.
 */
public interface Bookings {

  /** The database role the module's statements run in; it is granted the schema booking only. */
  String DATABASE_ROLE = "booking_module";

  /**
   * Confirms the fan's hold: lock the seat, charge, then sell it, or release it if the card is
   * declined. Must be called outside any transaction, because the charge calls the provider.
   *
   * @return the sale, or why there is none: {@code Failed} (the seat went back on sale) or {@code
   *     Refused} (nothing changed)
   */
  Result<Confirmed, ConfirmFailure> confirm(UUID holdId, long customerId);

  /**
   * The stuck-saga sweeper (Module 28): finishes every saga that is not final and has not moved for
   * longer than {@code olderThan}, from the state it was left in, with the same provider key.
   *
   * @return how each saga it found ended, oldest first
   */
  List<Result<Confirmed, ConfirmFailure>> resumeStuckSagas(Duration olderThan);

  /** Creates the booking module; it uses the other modules only through their interfaces. */
  static Bookings create(
      Database database, Clock clock, DomainEvents events, Collaborators collaborators) {
    return new ConfirmBooking(database.forModule(DATABASE_ROLE), clock, events, collaborators);
  }

  /**
   * Writes every {@link BookingConfirmed} into the booking outbox (Module 28) inside the confirm's
   * transaction, for listeners outside this deployable.
   */
  static void publishThroughOutbox(DomainEvents events, Database database, Clock clock) {
    BookingOutbox outbox = new BookingOutbox(database.forModule(DATABASE_ROLE), clock);
    events.subscribe(BookingConfirmed.class, outbox::append);
  }

  /**
   * Publishes the outbox's unsent rows to the broker, oldest first, and marks each after the broker
   * took it: at least once (Module 28). The broker is given each row's key (the booking id, so one
   * booking's messages stay in order on one partition) and its payload, and throws if it cannot
   * confirm that it stored the row.
   *
   * @return the rows published in this pass
   */
  static int relayOutbox(Database database, Clock clock, BiConsumer<String, String> broker) {
    return new BookingOutbox(database.forModule(DATABASE_ROLE), clock).relayTo(broker);
  }

  /** The three modules the confirm calls, grouped so the factory keeps four parameters. */
  record Collaborators(Catalog catalog, Inventory inventory, Payments payments) {

    /** Refuses a missing module. */
    public Collaborators {
      Objects.requireNonNull(catalog, "catalog");
      Objects.requireNonNull(inventory, "inventory");
      Objects.requireNonNull(payments, "payments");
    }
  }

  /** The seat is sold and paid for. */
  record Confirmed(UUID bookingId, String seatLabel) {}

  /** Why a confirm ended without a sale; each case says it in words. */
  sealed interface ConfirmFailure {

    /** The failure in words. */
    String reason();

    /** The saga compensated: the seat went back on sale, and no money stayed taken. */
    record Failed(UUID bookingId, String reason) implements ConfirmFailure {}

    /** The confirm could not start (no such hold, or it had ended); nothing changed. */
    record Refused(String reason) implements ConfirmFailure {}
  }
}
