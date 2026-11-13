package io.plainround.seatwave.modulith.booking.internal;

import io.plainround.seatwave.modulith.catalog.Catalog.SeatOffer;
import io.plainround.seatwave.modulith.inventory.Inventory.ClaimedSeat;
import io.plainround.seatwave.modulith.platform.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The booking module's tables: the saga rows and the bookings. Every method joins the caller's
 * transaction when there is one. Every transition is a conditional update from the state the caller
 * read, so a sweeper and a late confirm can never both move one saga. Thread-safe: no mutable
 * state.
 */
final class BookingSagas {

  // The sweeper reads at most this many stuck sagas per pass; the next pass takes the rest.
  private static final int MAX_STUCK_PER_PASS = 100;
  private static final String BEGIN_SQL =
      """
      INSERT INTO booking.booking_sagas (booking_id, hold_id, customer_id, event_id, seat_label,
        price, currency, show_starts_at, state, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SEAT_HELD', ?)
      """;
  private static final String MOVE_SQL =
      """
      UPDATE booking.booking_sagas SET state = ?, updated_at = ?
      WHERE booking_id = ? AND state = ?
      """;
  // Every state that is not final, by name: a state missing here is a saga nobody ever finishes.
  private static final String STUCK_SQL =
      """
      SELECT booking_id, hold_id, customer_id, event_id, seat_label, price, currency,
        show_starts_at, state
      FROM booking.booking_sagas
      WHERE state IN ('SEAT_HELD', 'PAYMENT_REQUESTED', 'PAYMENT_SUCCEEDED', 'PAYMENT_FAILED',
        'REFUND_REQUESTED', 'PAYMENT_REFUNDED')
        AND updated_at < ?
      ORDER BY updated_at, booking_id
      LIMIT ?
      """;
  private static final String INSERT_BOOKING_SQL =
      """
      INSERT INTO booking.bookings
        (booking_id, hold_id, event_id, seat_label, customer_id, price_paid, currency, confirmed_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final Database database;
  private final Clock clock;

  BookingSagas(Database database, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Writes a new saga in SEAT_HELD, with what it needs to be finished by anyone. */
  Saga begin(UUID bookingId, UUID holdId, long customerId, ClaimedSeat seat, SeatOffer offer) {
    return database.inTransaction(connection -> {
      try (PreparedStatement insert = connection.prepareStatement(BEGIN_SQL)) {
        insert.setObject(1, bookingId);
        insert.setObject(2, holdId);
        insert.setLong(3, customerId);
        insert.setLong(4, seat.eventId());
        insert.setString(5, seat.seatLabel());
        insert.setBigDecimal(6, offer.price());
        insert.setString(7, offer.currency());
        insert.setObject(8, offer.showStartsAt().atOffset(ZoneOffset.UTC));
        insert.setObject(9, clock.instant().atOffset(ZoneOffset.UTC));
        insert.executeUpdate();
        return new Saga(bookingId, holdId, customerId, seat, offer, SagaState.SEAT_HELD);
      }
    });
  }

  /**
   * Moves the saga from the state it is in to the next.
   *
   * @return the saga in its new state
   * @throws IllegalStateException if the row is no longer in {@code saga.state()}: another worker
   *     moved it, and this transaction must roll back
   */
  Saga move(Saga saga, SagaState next) {
    return database.inTransaction(connection -> {
      try (PreparedStatement move = connection.prepareStatement(MOVE_SQL)) {
        move.setString(1, next.name());
        move.setObject(2, clock.instant().atOffset(ZoneOffset.UTC));
        move.setObject(3, saga.bookingId());
        move.setString(4, saga.state().name());
        if (move.executeUpdate() != 1) {
          throw new IllegalStateException(
              "saga " + saga.bookingId() + " is no longer " + saga.state());
        }
        return saga.inState(next);
      }
    });
  }

  /** Writes the booking a COMPLETED saga creates, in the caller's transaction. */
  void recordBooking(Saga saga, Instant confirmedAt) {
    database.inTransaction(connection -> {
      try (PreparedStatement booking = connection.prepareStatement(INSERT_BOOKING_SQL)) {
        booking.setObject(1, saga.bookingId());
        booking.setObject(2, saga.holdId());
        booking.setLong(3, saga.seat().eventId());
        booking.setString(4, saga.seat().seatLabel());
        booking.setLong(5, saga.customerId());
        booking.setBigDecimal(6, saga.offer().price());
        booking.setString(7, saga.offer().currency());
        booking.setObject(8, confirmedAt.atOffset(ZoneOffset.UTC));
        return booking.executeUpdate();
      }
    });
  }

  /** The sagas not final whose last move is older than {@code movedBefore}, oldest first. */
  List<Saga> stuck(Instant movedBefore) {
    return database.inTransaction(connection -> {
      try (PreparedStatement query = connection.prepareStatement(STUCK_SQL)) {
        query.setObject(1, movedBefore.atOffset(ZoneOffset.UTC));
        query.setInt(2, MAX_STUCK_PER_PASS);
        try (ResultSet rows = query.executeQuery()) {
          List<Saga> stuck = new ArrayList<>();
          while (rows.next()) {
            stuck.add(sagaOf(rows));
          }
          return List.copyOf(stuck);
        }
      }
    });
  }

  private static Saga sagaOf(ResultSet row) throws SQLException {
    return new Saga(
        row.getObject(1, UUID.class),
        row.getObject(2, UUID.class),
        row.getLong(3),
        new ClaimedSeat(row.getLong(4), row.getString(5)),
        new SeatOffer(
            row.getBigDecimal(6),
            row.getString(7),
            row.getObject(8, OffsetDateTime.class).toInstant()),
        SagaState.valueOf(row.getString(9)));
  }

  /** One saga row, as read: enough to finish it without asking anyone what it was. */
  record Saga(
      UUID bookingId,
      UUID holdId,
      long customerId,
      ClaimedSeat seat,
      SeatOffer offer,
      SagaState state) {

    Saga inState(SagaState next) {
      return new Saga(bookingId, holdId, customerId, seat, offer, next);
    }
  }
}
