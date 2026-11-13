package io.plainround.seatwave.modulith;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.booking.BookingConfirmed;
import io.plainround.seatwave.modulith.booking.Bookings;
import io.plainround.seatwave.modulith.booking.Bookings.ConfirmFailure;
import io.plainround.seatwave.modulith.booking.Bookings.Confirmed;
import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.Inventory;
import io.plainround.seatwave.modulith.inventory.Inventory.Held;
import io.plainround.seatwave.modulith.inventory.Inventory.Refusal;
import io.plainround.seatwave.modulith.notification.Notifications;
import io.plainround.seatwave.modulith.payment.Payments;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.DomainEvents;
import io.plainround.seatwave.modulith.platform.Migrations;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs SeatWave's five modules in one deployable over one database and prints what each confirm
 * did: on H2 in memory with no argument, or on the stack's PostgreSQL 18.6 with the settings file
 * as the argument, where each module's statements run in the module's own role. Both print the same
 * lines. The only class that prints; it wires the modules through their interfaces and never
 * touches a module's internals, which the boundary tests check.
 */
public final class ModulithCli {

  private static final String H2_URL = "jdbc:h2:mem:seatwave_modulith";
  private static final String OWNER = "seatwave_owner";
  private static final String APP_USER = "seatwave_app";
  private static final List<String> H2_MIGRATIONS = List.of("/db/h2/V1__module_schemas.sql");
  private static final List<String> POSTGRES_MIGRATIONS =
      List.of("/db/postgresql/reset.sql", "/db/postgresql/V1__module_schemas.sql");
  // Module 2's hold length, and a moment just past it for the late confirm and the sweeper.
  private static final Duration HOLD_LENGTH = Duration.ofSeconds(300);
  private static final Duration PAST_THE_HOLD = Duration.ofSeconds(301);
  private static final Instant ON_SALE_STARTS = Instant.parse("2026-11-20T19:00:00Z");
  private static final long ARENA_NIGHT = 1;
  private static final int USAGE_ERROR = 2;

  private ModulithCli() {}

  /** Runs the six parts; exits non-zero on a wrong argument or settings problem. */
  public static void main(String[] arguments) throws SQLException, IOException {
    if (arguments.length == 0) {
      runOnH2(System.out);
    } else if (arguments.length == 1) {
      Result<StackSettings, List<String>> settings = StackSettings.load(Path.of(arguments[0]));
      Optional<StackSettings> loaded = settings.discardError();
      if (loaded.isEmpty()) {
        settings.peekError(problems -> problems.forEach(System.err::println));
        System.exit(1);
      }
      runOnPostgres(System.out, loaded.get());
    } else {
      System.err.println("usage: ModulithCli [config/stack.properties]");
      System.exit(USAGE_ERROR);
    }
  }

  /** The run on H2 in memory: one login with every schema, as Module 13's seatwave_app. */
  static void runOnH2(PrintStream out) throws SQLException, IOException {
    // The owner's connection keeps the in-memory database alive for the whole run, and is the
    // lab's inspector: it reads every schema, as a database administrator would.
    try (Connection owner = DriverManager.getConnection(H2_URL, OWNER, "")) {
      Migrations.apply(owner, H2_MIGRATIONS);
      run(out, owner, new Database.Settings(H2_URL, APP_USER, "", Database.Access.ONE_LOGIN));
    }
  }

  /** The run on PostgreSQL: the same login, which takes each module's role in turn. */
  static void runOnPostgres(PrintStream out, StackSettings stack) throws SQLException, IOException {
    Database.Settings ownerLogin = stack.login(OWNER, Database.Access.ONE_LOGIN);
    try (Connection owner =
        DriverManager.getConnection(
            ownerLogin.jdbcUrl(), ownerLogin.user(), ownerLogin.password())) {
      Migrations.apply(owner, POSTGRES_MIGRATIONS);
      run(out, owner, stack.login(APP_USER, Database.Access.ROLE_PER_MODULE));
    }
  }

  private static void run(PrintStream out, Connection owner, Database.Settings app)
      throws SQLException {
    LabClock clock = new LabClock(ON_SALE_STARTS);
    Database database = Database.open(app);
    DomainEvents events = new DomainEvents();
    Set<String> decliningKeys = ConcurrentHashMap.newKeySet();
    Catalog catalog = Catalog.create(database);
    Inventory inventory = Inventory.create(database, clock, HOLD_LENGTH, catalog);
    Payments payments = Payments.create(database, decliningKeys::contains);
    Bookings bookings =
        Bookings.create(
            database, clock, events, new Bookings.Collaborators(catalog, inventory, payments));
    Notifications.subscribeTo(events, database);
    AtomicBoolean listenerFailsNext = new AtomicBoolean(false);
    events.subscribe(BookingConfirmed.class, confirmed -> failIfAsked(listenerFailsNext));
    Lab lab = new Lab(out, database, inventory, bookings);

    out.println("SeatWave as a modular monolith: 5 modules, 1 deployable, 1 database");
    out.println("Part 1. A fan confirms: lock, charge, sell");
    UUID firstHold = lab.hold(1001, "A-7").orElseThrow();
    lab.confirm(firstHold, 1001);
    out.println("Part 2. The card is declined: the seat goes back on sale");
    UUID declinedHold = lab.hold(1002, "A-8").orElseThrow();
    decliningKeys.add("seatwave-hold-" + declinedHold);
    lab.confirm(declinedHold, 1002);
    out.println("Part 3. The hold expired before the confirm");
    UUID lateHold = lab.hold(1003, "A-9").orElseThrow();
    clock.advance(PAST_THE_HOLD);
    lab.confirm(lateHold, 1003);
    UUID secondChance = lab.hold(1004, "A-9").orElseThrow();
    out.println("Part 4. One seat, one claim");
    lab.hold(1005, "A-7");
    out.println("Part 5. A listener fails inside the confirm's transaction");
    listenerFailsNext.set(true);
    lab.confirm(secondChance, 1004);
    printRows(
        out,
        owner,
        "left behind",
        """
        SELECT sagas.state, sagas.seat_label, holds.state, payments.state
        FROM booking.booking_sagas sagas
        JOIN inventory.holds holds ON holds.hold_id = sagas.hold_id
        JOIN payment.payments payments ON payments.booking_id = sagas.booking_id
        WHERE sagas.state NOT IN ('COMPLETED', 'COMPENSATED')
        """);
    out.println("Part 6. The stuck-saga sweeper finishes it (Module 28)");
    clock.advance(PAST_THE_HOLD);
    lab.sweep();
    printTables(out, owner);
  }

  private static void failIfAsked(AtomicBoolean listenerFailsNext) {
    if (listenerFailsNext.getAndSet(false)) {
      // A stand-in for any listener bug: a full disk, a renamed column, a null.
      throw new IllegalStateException("listener failed (simulated by the lab)");
    }
  }

  private static void printTables(PrintStream out, Connection owner) throws SQLException {
    out.println("Tables after the run, each read in its owner's schema:");
    printRows(
        out,
        owner,
        "inventory.holds",
        "SELECT seat_label, state FROM inventory.holds ORDER BY created_at, seat_label, state");
    printRows(
        out,
        owner,
        "booking.bookings",
        "SELECT seat_label, price_paid, currency FROM booking.bookings ORDER BY seat_label");
    printRows(
        out,
        owner,
        "booking.booking_sagas",
        "SELECT state FROM booking.booking_sagas ORDER BY state");
    printRows(
        out,
        owner,
        "payment.payments",
        "SELECT state, amount FROM payment.payments ORDER BY state");
    printRows(
        out, owner, "notification.emails", "SELECT body FROM notification.emails ORDER BY body");
    printRows(
        out,
        owner,
        "notification.reminders",
        "SELECT due_at FROM notification.reminders ORDER BY due_at");
  }

  private static void printRows(PrintStream out, Connection owner, String label, String sql)
      throws SQLException {
    StringBuilder rows = new StringBuilder();
    try (Statement statement = owner.createStatement();
        ResultSet row = statement.executeQuery(sql)) {
      int columns = row.getMetaData().getColumnCount();
      while (row.next()) {
        StringBuilder cells = new StringBuilder();
        for (int column = 1; column <= columns; column++) {
          Object cell = row.getObject(column);
          // H2 returns a time with a zone as OffsetDateTime, PostgreSQL's driver as Timestamp.
          cells
              .append(column > 1 ? " " : "")
              .append(
                  switch (cell) {
                    case OffsetDateTime time -> time.toInstant();
                    case Timestamp time -> time.toInstant();
                    default -> cell;
                  });
        }
        rows.append(rows.isEmpty() ? "" : " | ").append(cells);
      }
    }
    out.println(String.format(Locale.ROOT, "  %-23s %s", label, rows.isEmpty() ? "(none)" : rows));
  }

  /** The lab's steps, each printing one line with the transactions it committed. */
  private record Lab(PrintStream out, Database database, Inventory inventory, Bookings bookings) {

    Optional<UUID> hold(long customerId, String seatLabel) {
      Result<Held, Refusal> outcome = inventory.hold(customerId, ARENA_NIGHT, seatLabel);
      String said =
          outcome
              .map(held -> "held for " + HOLD_LENGTH.toSeconds() + " s")
              .recoverError(refusal -> "refused: " + refusal.reason());
      out.println(String.format(Locale.ROOT, "  fan %d holds %s: %s", customerId, seatLabel, said));
      return outcome.map(Held::holdId).discardError();
    }

    void confirm(UUID holdId, long customerId) {
      int commitsBefore = database.commits();
      String result;
      try {
        result = describe(bookings.confirm(holdId, customerId));
      } catch (IllegalStateException failure) {
        // The lab reports the failure and carries on; the service would answer 500 and let the
        // stuck-saga sweeper finish this booking (Part 6).
        result = "error, rolled back: " + failure.getMessage();
      }
      out.println(
          String.format(
              Locale.ROOT,
              "  fan %d confirms: %s (local transactions committed: %d)",
              customerId,
              result,
              database.commits() - commitsBefore));
    }

    void sweep() {
      int commitsBefore = database.commits();
      List<String> finished =
          bookings.resumeStuckSagas(HOLD_LENGTH).stream().map(Lab::describe).toList();
      out.println(
          String.format(
              Locale.ROOT,
              "  sweeper finds sagas unmoved for over %d s: %s (local transactions committed: %d)",
              HOLD_LENGTH.toSeconds(),
              finished,
              database.commits() - commitsBefore));
    }

    private static String describe(Result<Confirmed, ConfirmFailure> outcome) {
      return outcome
          .map(confirmed -> "confirmed " + confirmed.seatLabel())
          .recoverError(failure ->
              switch (failure) {
                case ConfirmFailure.Failed failed -> "failed: " + failed.reason();
                case ConfirmFailure.Refused refused -> "refused: " + refused.reason();
              });
    }
  }

  /** A clock the lab moves forward by hand, so expiry happens without waiting. Not thread-safe. */
  private static final class LabClock extends Clock {

    private Instant now;

    LabClock(Instant start) {
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
      throw new UnsupportedOperationException("the lab clock is always UTC");
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
