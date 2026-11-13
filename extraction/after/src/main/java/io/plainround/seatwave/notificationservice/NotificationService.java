package io.plainround.seatwave.notificationservice;

import io.plainround.seatwave.modulith.platform.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The notification module after it left the deployable: Module 27's {@code notifications-email}
 * consumer. It reads {@code BookingConfirmed} from the broker, never from booking's tables; it logs
 * in as its own database user, which holds only notification's role; and it claims each effect in
 * {@code processed_messages} in the same transaction as the effect, because the relay delivers at
 * least once. It shares only the platform library ({@link Database}) with the monolith.
 * Thread-safe: no mutable state.
 */
public final class NotificationService {

  // Module 27's consumer group and dedupe key: one effect per booking, whatever the message id.
  static final String CONSUMER_GROUP = "notifications-email";
  private static final String DEDUPE_PREFIX = "confirmation/";
  private static final Duration REMINDER_LEAD = Duration.ofHours(24);
  private static final List<String> REQUIRED_FIELDS =
      List.of(
          "message_id",
          "booking_id",
          "customer_id",
          "event_id",
          "seat_label",
          "show_starts_at",
          "confirmed_at");
  private static final String CLAIM_SQL =
      """
      INSERT INTO notification.processed_messages (consumer_group, dedupe_key, message_id, done_at)
      VALUES (?, ?, ?, ?)
      """;
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
  private final Clock clock;

  /** Creates the service over its own database login. */
  public NotificationService(Database database, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Handles one message from {@code seatwave.bookings}; what happened is the answer. */
  public Outcome handle(String message) {
    Objects.requireNonNull(message, "message");
    Optional<Confirmed> parsed = parse(message);
    if (parsed.isEmpty()) {
      return Outcome.PARKED;
    }
    Confirmed confirmed = parsed.get();
    return database.inTransaction(connection -> {
      try (PreparedStatement claim = connection.prepareStatement(CLAIM_SQL)) {
        claim.setString(1, CONSUMER_GROUP);
        claim.setString(2, DEDUPE_PREFIX + confirmed.bookingId());
        claim.setObject(3, confirmed.messageId());
        claim.setObject(4, clock.instant().atOffset(ZoneOffset.UTC));
        // The claim and the email commit together; a second delivery finds the claim and stops.
        if (!Database.insertUnlessDuplicate(connection, claim)) {
          return Outcome.DUPLICATE;
        }
      }
      queueEmailAndReminder(connection, confirmed);
      return Outcome.HANDLED;
    });
  }

  /** What handling one message did. */
  public enum Outcome {
    /** The email and the reminder were queued. */
    HANDLED,
    /** This booking's email was queued before; nothing was done. */
    DUPLICATE,
    /** The message could not be read (Module 27's poison message); a dead-letter path takes it. */
    PARKED
  }

  private static Optional<Confirmed> parse(String message) {
    Map<String, String> fields = new HashMap<>();
    for (String pair : message.split(";")) {
      int separator = pair.indexOf('=');
      if (separator <= 0) {
        return Optional.empty();
      }
      fields.put(pair.substring(0, separator), pair.substring(separator + 1));
    }
    if (!"BookingConfirmed".equals(fields.get("type"))
        || !fields.keySet().containsAll(REQUIRED_FIELDS)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Confirmed(
              UUID.fromString(fields.get("message_id")),
              UUID.fromString(fields.get("booking_id")),
              Long.parseLong(fields.get("customer_id")),
              fields.get("event_id"),
              fields.get("seat_label"),
              Instant.parse(fields.get("show_starts_at")),
              Instant.parse(fields.get("confirmed_at"))));
    } catch (IllegalArgumentException | DateTimeParseException unreadable) {
      // A malformed id, number or time: park the message rather than guess.
      return Optional.empty();
    }
  }

  private static void queueEmailAndReminder(Connection connection, Confirmed confirmed)
      throws SQLException {
    Instant reminderDue = confirmed.showStartsAt().minus(REMINDER_LEAD);
    Instant dueAt =
        reminderDue.isBefore(confirmed.confirmedAt()) ? confirmed.confirmedAt() : reminderDue;
    String body =
        String.format(
            Locale.ROOT,
            "Seat %s for event %s is yours. The show starts at %s.",
            confirmed.seatLabel(),
            confirmed.eventId(),
            confirmed.showStartsAt());
    try (PreparedStatement email = connection.prepareStatement(EMAIL_SQL);
        PreparedStatement reminder = connection.prepareStatement(REMINDER_SQL)) {
      email.setObject(1, UUID.randomUUID());
      email.setObject(2, confirmed.bookingId());
      email.setLong(3, confirmed.customerId());
      email.setString(4, body);
      email.executeUpdate();
      reminder.setObject(1, confirmed.bookingId());
      reminder.setLong(2, confirmed.customerId());
      reminder.setObject(3, confirmed.showStartsAt().atOffset(ZoneOffset.UTC));
      reminder.setObject(4, dueAt.atOffset(ZoneOffset.UTC));
      reminder.executeUpdate();
    }
  }

  /** The fields of {@code BookingConfirmed} this service reads, its own copy of the contract. */
  private record Confirmed(
      UUID messageId,
      UUID bookingId,
      long customerId,
      String eventId,
      String seatLabel,
      Instant showStartsAt,
      Instant confirmedAt) {}
}
