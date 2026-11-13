package io.plainround.seatwave.modulith.inventory.internal;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.Inventory;
import io.plainround.seatwave.modulith.platform.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * The inventory module's table and rules. Every state change is one conditional update, so the
 * database, not this code, decides who wins a race. Public only so the module's interface can
 * create it; the boundary tests refuse any use of it from another module. Thread-safe: it holds no
 * mutable state.
 */
public final class HoldStore implements Inventory {

  // Lazy expiry, as in Module 13: an ACTIVE hold past its end stops counting as a claim the moment
  // someone else asks for the seat. A CONFIRMING hold is never touched: it is the semantic lock.
  private static final String EXPIRE_SQL =
      """
      UPDATE inventory.holds SET state = 'EXPIRED'
      WHERE event_id = ? AND seat_label = ? AND state = 'ACTIVE' AND expires_at <= ?
      """;
  // one_claim_per_seat (see the schema) refuses a second live claim on one seat.
  private static final String INSERT_SQL =
      """
      INSERT INTO inventory.holds
        (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at)
      VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
      """;
  private static final String LOCK_SQL =
      """
      UPDATE inventory.holds SET state = 'CONFIRMING', booking_id = ?
      WHERE hold_id = ? AND customer_id = ? AND state = 'ACTIVE' AND expires_at > ?
      """;
  private static final String CONFIRM_SQL =
      """
      UPDATE inventory.holds SET state = 'CONFIRMED'
      WHERE hold_id = ? AND state = 'CONFIRMING' AND booking_id = ?
      """;
  private static final String RELEASE_SQL =
      """
      UPDATE inventory.holds SET state = 'RELEASED'
      WHERE hold_id = ? AND state = 'CONFIRMING' AND booking_id = ?
      """;
  private static final String READ_SQL =
      """
      SELECT event_id, seat_label, customer_id, state, booking_id
      FROM inventory.holds WHERE hold_id = ?
      """;

  private final Database database;
  private final Clock clock;
  private final Duration holdLength;
  private final Catalog catalog;

  /**
   * Creates the store.
   *
   * @param holdLength how long a hold lasts; positive (300 s in SeatWave, Module 2)
   */
  public HoldStore(Database database, Clock clock, Duration holdLength, Catalog catalog) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.holdLength = Objects.requireNonNull(holdLength, "holdLength");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    if (holdLength.isNegative() || holdLength.isZero()) {
      throw new IllegalArgumentException("holdLength must be positive: " + holdLength);
    }
  }

  @Override
  public Result<Held, Refusal> hold(long customerId, long eventId, String seatLabel) {
    Objects.requireNonNull(seatLabel, "seatLabel");
    // Through the catalog's interface, never its tables: Module 13's foreign key from holds to
    // seats crossed what is now a module boundary.
    if (catalog.offerFor(eventId, seatLabel).isEmpty()) {
      return Result.error(new Refusal.NoSuchSeat(eventId, seatLabel));
    }
    Instant now = clock.instant();
    return database.inTransaction(connection -> {
      try (PreparedStatement expire = connection.prepareStatement(EXPIRE_SQL);
          PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
        expire.setLong(1, eventId);
        expire.setString(2, seatLabel);
        expire.setObject(3, now.atOffset(ZoneOffset.UTC));
        expire.executeUpdate();
        UUID holdId = UUID.randomUUID();
        Instant expiresAt = now.plus(holdLength);
        insert.setObject(1, holdId);
        insert.setLong(2, eventId);
        insert.setString(3, seatLabel);
        insert.setLong(4, customerId);
        insert.setObject(5, now.atOffset(ZoneOffset.UTC));
        insert.setObject(6, expiresAt.atOffset(ZoneOffset.UTC));
        // In a savepoint: a refused insert must not undo the expiry above on PostgreSQL.
        if (!Database.insertUnlessDuplicate(connection, insert)) {
          return Result.<Held, Refusal>error(new Refusal.SeatClaimed(seatLabel));
        }
        return Result.<Held, Refusal>success(new Held(holdId, expiresAt));
      }
    });
  }

  @Override
  public Result<ClaimedSeat, Refusal> lockForPayment(UUID holdId, long customerId, UUID bookingId) {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(bookingId, "bookingId");
    // The hold must still be ACTIVE after this instant for the lock to be granted.
    Instant lockDeadline = clock.instant();
    return database.inTransaction(connection -> {
      try (PreparedStatement lock = connection.prepareStatement(LOCK_SQL)) {
        lock.setObject(1, bookingId);
        lock.setObject(2, holdId);
        // The fan is checked next to the data (code guidelines, rule 60): another fan's hold id
        // locks nothing.
        lock.setLong(3, customerId);
        lock.setObject(4, lockDeadline.atOffset(ZoneOffset.UTC));
        int locked = lock.executeUpdate();
        return readLockOutcome(connection, holdId, customerId, bookingId, locked == 1);
      }
    });
  }

  @Override
  public Result<ClaimedSeat, Refusal> confirm(UUID holdId, UUID bookingId) {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(bookingId, "bookingId");
    return database.inTransaction(connection -> {
      try (PreparedStatement update = connection.prepareStatement(CONFIRM_SQL);
          PreparedStatement read = connection.prepareStatement(READ_SQL)) {
        update.setObject(1, holdId);
        update.setObject(2, bookingId);
        // Zero rows changed is a repeat (already CONFIRMED for this booking) or a hold taken away;
        // the read tells the two apart.
        update.executeUpdate();
        read.setObject(1, holdId);
        try (ResultSet row = read.executeQuery()) {
          if (!row.next()) {
            return Result.<ClaimedSeat, Refusal>error(new Refusal.NoSuchHold());
          }
          boolean soldToThisBooking =
              "CONFIRMED".equals(row.getString(4)) && bookingId.equals(row.getObject(5));
          return soldToThisBooking
              ? Result.<ClaimedSeat, Refusal>success(
                  new ClaimedSeat(row.getLong(1), row.getString(2)))
              : Result.<ClaimedSeat, Refusal>error(new Refusal.HoldUnusable(row.getString(4)));
        }
      }
    });
  }

  @Override
  public void release(UUID holdId, UUID bookingId) {
    // Zero rows changed means released earlier, or never locked by this booking: nothing to undo.
    changeState(RELEASE_SQL, holdId, bookingId);
  }

  private static Result<ClaimedSeat, Refusal> readLockOutcome(
      Connection connection, UUID holdId, long customerId, UUID bookingId, boolean lockedNow)
      throws SQLException {
    try (PreparedStatement read = connection.prepareStatement(READ_SQL)) {
      read.setObject(1, holdId);
      try (ResultSet row = read.executeQuery()) {
        if (!row.next() || row.getLong(3) != customerId) {
          return Result.error(new Refusal.NoSuchHold());
        }
        String state = row.getString(4);
        boolean lockedEarlier = "CONFIRMING".equals(state) && bookingId.equals(row.getObject(5));
        if (lockedNow || lockedEarlier) {
          return Result.success(new ClaimedSeat(row.getLong(1), row.getString(2)));
        }
        return Result.error(new Refusal.HoldUnusable("ACTIVE".equals(state) ? "expired" : state));
      }
    }
  }

  private int changeState(String sql, UUID holdId, UUID bookingId) {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(bookingId, "bookingId");
    return database.inTransaction(connection -> {
      try (PreparedStatement update = connection.prepareStatement(sql)) {
        update.setObject(1, holdId);
        update.setObject(2, bookingId);
        return update.executeUpdate();
      }
    });
  }
}
