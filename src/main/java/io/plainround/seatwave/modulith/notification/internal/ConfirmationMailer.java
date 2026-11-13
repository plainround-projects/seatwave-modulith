package io.plainround.seatwave.modulith.notification.internal;

import io.plainround.seatwave.modulith.booking.BookingConfirmed;
import io.plainround.seatwave.modulith.platform.Database;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Queues what a confirmed booking owes the fan: one confirmation email and one reminder (Module
 * 27's {@code reminders} row). It writes rows only; a sender sends pending rows after they commit,
 * so a booking that rolls back never sends an email. Public only so the module's entry point can
 * create it. Thread-safe: no mutable state.
 */
public final class ConfirmationMailer {

  // Module 27: the reminder is due 24 hours before the show, or at once if the show is sooner.
  private static final Duration REMINDER_LEAD = Duration.ofHours(24);
  private static final String EMAIL_SQL =
      """
      INSERT INTO notification.emails (email_id, booking_id, customer_id, body, status)
      VALUES (?, ?, ?, ?, 'PENDING')
      """;
  private static final String REMINDER_SQL =
      """
      INSERT INTO notification.reminders
        (booking_id, customer_id, show_starts_at, due_at, status)
      VALUES (?, ?, ?, ?, 'PENDING')
      """;

  private final Database database;

  /** Creates the mailer over the module's view of the deployable's database. */
  public ConfirmationMailer(Database database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  /** Queues the email and the reminder for one booking, in the caller's transaction. */
  public void queueFor(BookingConfirmed confirmed) {
    Objects.requireNonNull(confirmed, "confirmed");
    Instant reminderDue = confirmed.showStartsAt().minus(REMINDER_LEAD);
    Instant dueAt =
        reminderDue.isBefore(confirmed.confirmedAt()) ? confirmed.confirmedAt() : reminderDue;
    database.inTransaction(connection -> {
      try (PreparedStatement email = connection.prepareStatement(EMAIL_SQL);
          PreparedStatement reminder = connection.prepareStatement(REMINDER_SQL)) {
        email.setObject(1, UUID.randomUUID());
        email.setObject(2, confirmed.bookingId());
        email.setLong(3, confirmed.customerId());
        email.setString(4, bodyOf(confirmed));
        email.executeUpdate();
        reminder.setObject(1, confirmed.bookingId());
        reminder.setLong(2, confirmed.customerId());
        reminder.setObject(3, confirmed.showStartsAt().atOffset(ZoneOffset.UTC));
        reminder.setObject(4, dueAt.atOffset(ZoneOffset.UTC));
        return reminder.executeUpdate();
      }
    });
  }

  private static String bodyOf(BookingConfirmed confirmed) {
    return String.format(
        Locale.ROOT,
        "Seat %s for event %d is yours. The show starts at %s.",
        confirmed.seatLabel(),
        confirmed.eventId(),
        confirmed.showStartsAt());
  }
}
