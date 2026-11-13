package io.plainround.seatwave.modulith;

import io.plainround.seatwave.modulith.booking.Bookings;
import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.Inventory;
import io.plainround.seatwave.modulith.notification.Notifications;
import io.plainround.seatwave.modulith.payment.Payments;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.DomainEvents;
import io.plainround.seatwave.modulith.platform.Migrations;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The five modules wired as ModulithCli wires them, over a fresh H2 database of their own, with a
 * clock the test moves and a provider stand-in the test steers. The owner's connection keeps the
 * database alive and lets a test read or arrange any table directly. Not thread-safe.
 */
public final class H2Deployable implements AutoCloseable {

  /** Module 2's hold length. */
  public static final Duration HOLD_LENGTH = Duration.ofSeconds(300);

  /** Arena Night, the lab catalog's event with seats A-7, A-8 and A-9 at 80.00 USD. */
  public static final long ARENA_NIGHT = 1;

  private static final Instant ON_SALE_STARTS = Instant.parse("2026-11-20T19:00:00Z");

  private final Connection owner;
  private final TestClock clock = new TestClock(ON_SALE_STARTS);
  private final Set<String> decliningKeys = ConcurrentHashMap.newKeySet();
  private final Database database;
  private final DomainEvents events = new DomainEvents();
  private final Inventory inventory;
  private final Payments payments;
  private final Bookings bookings;

  private H2Deployable(Connection owner, String url, Predicate<String> beforeProviderAnswers) {
    this.owner = owner;
    this.database =
        Database.open(new Database.Settings(url, "seatwave_app", "", Database.Access.ONE_LOGIN));
    Catalog catalog = Catalog.create(database);
    this.inventory = Inventory.create(database, clock, HOLD_LENGTH, catalog);
    this.payments =
        Payments.create(
            database, key -> beforeProviderAnswers.test(key) || decliningKeys.contains(key));
    this.bookings =
        Bookings.create(
            database, clock, events, new Bookings.Collaborators(catalog, inventory, payments));
    Notifications.subscribeTo(events, database);
  }

  /** A deployable over a new database whose provider stand-in captures every key not declined. */
  public static H2Deployable start() throws SQLException, IOException {
    return start(key -> false);
  }

  /**
   * The same, with a hook the provider stand-in runs on every charge before it answers; the hook's
   * answer true means "decline".
   */
  public static H2Deployable start(Predicate<String> beforeProviderAnswers)
      throws SQLException, IOException {
    String url = "jdbc:h2:mem:test-" + UUID.randomUUID();
    Connection owner = DriverManager.getConnection(url, "seatwave_owner", "");
    Migrations.apply(owner, List.of("/db/h2/V1__module_schemas.sql"));
    return new H2Deployable(owner, url, beforeProviderAnswers);
  }

  public Database database() {
    return database;
  }

  public DomainEvents events() {
    return events;
  }

  public Inventory inventory() {
    return inventory;
  }

  public Payments payments() {
    return payments;
  }

  public Bookings bookings() {
    return bookings;
  }

  /** Makes the provider stand-in decline this key. */
  public void decline(String providerKey) {
    decliningKeys.add(providerKey);
  }

  /** Moves the clock forward. */
  public void advance(Duration step) {
    clock.advance(step);
  }

  /** The clock's current instant. */
  public Instant now() {
    return clock.instant();
  }

  /** A fan's hold on a seat of Arena Night; fails the test if refused. */
  public UUID holdSeat(long customerId, String seatLabel) {
    return inventory
        .hold(customerId, ARENA_NIGHT, seatLabel)
        .map(Inventory.Held::holdId)
        .discardError()
        .orElseThrow(() -> new AssertionError("hold of " + seatLabel + " refused"));
  }

  /** Runs one statement as the owner, with parameters. */
  public int execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = owner.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      return statement.executeUpdate();
    }
  }

  /** The first column of every row the query returns, as text, in the query's order. */
  public List<String> column(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = owner.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        List<String> values = new ArrayList<>();
        while (rows.next()) {
          values.add(String.valueOf(rows.getObject(1)));
        }
        return List.copyOf(values);
      }
    }
  }

  @Override
  public void close() throws SQLException {
    owner.close();
  }

  /** A clock the test moves by hand. Not thread-safe. */
  private static final class TestClock extends Clock {

    private Instant now;

    TestClock(Instant start) {
      this.now = start;
    }

    void advance(Duration step) {
      now = now.plus(step);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("the test clock is always UTC");
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
