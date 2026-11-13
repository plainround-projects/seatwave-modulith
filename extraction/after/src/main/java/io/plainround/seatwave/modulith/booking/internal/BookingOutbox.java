package io.plainround.seatwave.modulith.booking.internal;

import io.plainround.seatwave.modulith.booking.BookingConfirmed;
import io.plainround.seatwave.modulith.platform.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Module 28's transactional outbox, inside the booking module: the event row is written in the
 * confirm's own transaction, and a relay copies unsent rows to the broker afterwards. The payload
 * is Module 28's teaching format, {@code name=value} pairs joined by {@code ;}. Public only so the
 * module's interface can create it. Thread-safe: no mutable state; one relay runs per outbox.
 */
public final class BookingOutbox {

  private static final String APPEND_SQL =
      """
      INSERT INTO booking.outbox (message_id, event_type, aggregate_key, payload, created_at)
      VALUES (?, 'BookingConfirmed', ?, ?, ?)
      """;
  private static final String UNSENT_SQL =
      """
      SELECT message_id, aggregate_key, payload FROM booking.outbox
      WHERE published_at IS NULL ORDER BY created_at, message_id
      """;
  private static final String MARK_SQL =
      "UPDATE booking.outbox SET published_at = ? WHERE message_id = ?";

  private final Database database;
  private final Clock clock;

  /** Creates the outbox over the booking module's view of the deployable's database. */
  public BookingOutbox(Database database, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Writes the event's row inside the caller's transaction: both commit or neither. */
  public void append(BookingConfirmed confirmed) {
    Objects.requireNonNull(confirmed, "confirmed");
    UUID messageId = UUID.randomUUID();
    String payload =
        String.join(
            ";",
            "type=BookingConfirmed",
            "schema_version=1",
            "message_id=" + messageId,
            "booking_id=" + confirmed.bookingId(),
            "customer_id=" + confirmed.customerId(),
            "event_id=" + confirmed.eventId(),
            "seat_label=" + confirmed.seatLabel(),
            "show_starts_at=" + confirmed.showStartsAt(),
            "confirmed_at=" + confirmed.confirmedAt());
    database.inTransaction(connection -> {
      try (PreparedStatement append = connection.prepareStatement(APPEND_SQL)) {
        append.setObject(1, messageId);
        append.setString(2, confirmed.bookingId().toString());
        append.setString(3, payload);
        append.setObject(4, confirmed.confirmedAt().atOffset(ZoneOffset.UTC));
        return append.executeUpdate();
      }
    });
  }

  /**
   * One relay pass: publish each unsent row, then mark it. A broker that fails after taking a row
   * stops the pass before the mark, so that row is published again next pass: a duplicate, never a
   * loss.
   *
   * @return rows published and marked in this pass
   */
  public int relayTo(BiConsumer<String, String> broker) {
    Objects.requireNonNull(broker, "broker");
    List<UnsentRow> unsent =
        database.inTransaction(connection -> {
          List<UnsentRow> rows = new ArrayList<>();
          try (PreparedStatement read = connection.prepareStatement(UNSENT_SQL);
              ResultSet row = read.executeQuery()) {
            while (row.next()) {
              rows.add(
                  new UnsentRow(row.getObject(1, UUID.class), row.getString(2), row.getString(3)));
            }
          }
          return rows;
        });
    int published = 0;
    for (UnsentRow row : unsent) {
      // Publish first, mark second (Module 28, Section 3.20): the order that duplicates, not loses.
      broker.accept(row.key(), row.payload());
      database.inTransaction(connection -> {
        try (PreparedStatement mark = connection.prepareStatement(MARK_SQL)) {
          mark.setObject(1, clock.instant().atOffset(ZoneOffset.UTC));
          mark.setObject(2, row.messageId());
          return mark.executeUpdate();
        }
      });
      published++;
    }
    return published;
  }

  private record UnsentRow(UUID messageId, String key, String payload) {}
}
