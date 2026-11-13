package io.plainround.seatwave.modulith;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.booking.Bookings;
import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.Inventory;
import io.plainround.seatwave.modulith.payment.Payments;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.DomainEvents;
import io.plainround.seatwave.modulith.platform.Migrations;
import io.plainround.seatwave.notificationservice.BookingsConsumer;
import io.plainround.seatwave.notificationservice.NotificationService;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * The deployable after the notification module was extracted, on the stack: four modules in the
 * monolith on PostgreSQL, the notification service beside it with its own login, and Module 27's
 * Kafka topic {@code seatwave.bookings} between them. Its argument is the stack's settings file.
 */
public final class ExtractionCli {

  private static final List<String> MIGRATIONS =
      List.of(
          "/db/postgresql/reset.sql",
          "/db/postgresql/V1__module_schemas.sql",
          "/db/postgresql/V2__extract_notification.sql");
  private static final Duration HOLD_LENGTH = Duration.ofSeconds(300);
  private static final long ARENA_NIGHT = 1;
  // Bounds on every wait for the broker, far above what one broker on loopback needs.
  private static final Duration BROKER_DEADLINE = Duration.ofSeconds(30);
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);
  private static final int USAGE_ERROR = 2;

  private ExtractionCli() {}

  /** Runs the four parts; exits non-zero on a wrong argument or settings problem. */
  public static void main(String[] arguments)
      throws SQLException, IOException, InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: ExtractionCli config/stack.properties");
      System.exit(USAGE_ERROR);
    }
    Result<StackSettings, List<String>> settings = StackSettings.load(Path.of(arguments[0]));
    Optional<StackSettings> loaded = settings.discardError();
    if (loaded.isEmpty()) {
      settings.peekError(problems -> problems.forEach(System.err::println));
      System.exit(1);
    }
    run(loaded.get());
  }

  private static void run(StackSettings stack)
      throws SQLException, IOException, InterruptedException {
    Database.Settings ownerLogin = stack.login("seatwave_owner", Database.Access.ONE_LOGIN);
    BookingsConsumer.awaitLeaders(
        stack.kafkaBootstrapServers(), BookingsConsumer.TOPIC, BROKER_DEADLINE);
    try (Connection owner =
            DriverManager.getConnection(
                ownerLogin.jdbcUrl(), ownerLogin.user(), ownerLogin.password());
        KafkaProducer<String, String> producer =
            new KafkaProducer<>(
                // Kafka's defaults otherwise: acks=all and an idempotent producer.
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers()),
                new StringSerializer(),
                new StringSerializer())) {
      Migrations.apply(owner, MIGRATIONS);
      Clock clock = Clock.fixed(Instant.parse("2026-11-20T19:00:00Z"), ZoneOffset.UTC);
      Database monolith =
          Database.open(stack.login("seatwave_app", Database.Access.ROLE_PER_MODULE));
      DomainEvents events = new DomainEvents();
      Catalog catalog = Catalog.create(monolith);
      Inventory inventory = Inventory.create(monolith, clock, HOLD_LENGTH, catalog);
      Bookings bookings =
          Bookings.create(
              monolith,
              clock,
              events,
              new Bookings.Collaborators(
                  catalog, inventory, Payments.create(monolith, key -> false)));
      Bookings.publishThroughOutbox(events, monolith, clock);
      NotificationService notifications =
          new NotificationService(
              Database.open(stack.login("notification_service", Database.Access.ONE_LOGIN)), clock);
      AtomicBoolean dropNextAcknowledgement = new AtomicBoolean(false);
      BiConsumer<String, String> broker =
          (key, payload) -> {
            sendAndWait(producer, key, payload);
            if (dropNextAcknowledgement.getAndSet(false)) {
              // The broker stored the message, but its acknowledgement never reached the relay.
              throw new IllegalStateException(
                  "connection lost before the broker's acknowledgement");
            }
          };
      try (BookingsConsumer consumer =
          new BookingsConsumer(
              stack.kafkaBootstrapServers(), BookingsConsumer.TOPIC, notifications, POLL_TIMEOUT)) {
        System.out.println(
            "SeatWave after extracting notification: 4 modules in the deployable, 1 service beside"
                + " it");
        System.out.println(
            "Part 1. The sale writes its event to the outbox, in the sale's own transaction");
        sell(inventory, bookings, monolith, 1001, "A-7");
        System.out.println(
            "  booking.outbox unsent rows: "
                + count(owner, "booking.outbox WHERE published_at IS NULL"));
        relay(monolith, clock, broker);
        drain(consumer);
        System.out.println("Part 2. The relay loses the broker's acknowledgement once");
        sell(inventory, bookings, monolith, 1002, "A-8");
        dropNextAcknowledgement.set(true);
        relay(monolith, clock, broker);
        relay(monolith, clock, broker);
        drain(consumer);
        System.out.println("Part 3. The notification service is down while a fan buys");
        sell(inventory, bookings, monolith, 1003, "A-9");
        relay(monolith, clock, broker);
        System.out.println(
            "  messages waiting on the broker: "
                + consumer.waiting()
                + "; the sale did not wait for them");
        drain(consumer);
      }
      System.out.println("Part 4. The database keeps each side in its own tables");
      tryRead(stack, "notification_service", "", "booking.bookings");
      tryRead(stack, "notification_service", "", "notification.emails");
      tryRead(stack, "seatwave_app", "", "notification.emails");
      tryRead(stack, "seatwave_app", "notification_module", "notification.emails");
      System.out.println("Tables after the run:");
      System.out.println(
          "  booking.outbox rows: "
              + count(owner, "booking.outbox")
              + ", unsent: "
              + count(owner, "booking.outbox WHERE published_at IS NULL"));
      System.out.println(
          "  notification.processed_messages rows: "
              + count(owner, "notification.processed_messages"));
      System.out.println("  notification.emails rows: " + count(owner, "notification.emails"));
    }
  }

  private static void sendAndWait(
      KafkaProducer<String, String> producer, String key, String payload) {
    try {
      producer
          .send(new ProducerRecord<>(BookingsConsumer.TOPIC, key, payload))
          .get(BROKER_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the broker", interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("the broker did not store the message: " + failed, failed);
    }
  }

  private static void sell(
      Inventory inventory, Bookings bookings, Database monolith, long fan, String seat) {
    UUID holdId =
        inventory
            .hold(fan, ARENA_NIGHT, seat)
            .map(Inventory.Held::holdId)
            .discardError()
            .orElseThrow(
                () -> new IllegalStateException("the lab's seat " + seat + " was refused"));
    int commitsBefore = monolith.commits();
    String outcome =
        bookings
            .confirm(holdId, fan)
            .map(confirmed -> "confirmed")
            .recoverError(failure -> "not confirmed: " + failure.reason());
    System.out.println(
        String.format(
            Locale.ROOT,
            "  fan %d confirms %s: %s (local transactions committed: %d)",
            fan,
            seat,
            outcome,
            monolith.commits() - commitsBefore));
  }

  private static void relay(Database monolith, Clock clock, BiConsumer<String, String> broker) {
    try {
      System.out.println(
          "  relay pass: published and marked " + Bookings.relayOutbox(monolith, clock, broker));
    } catch (IllegalStateException failure) {
      // The relay stops at the first failure and leaves the row unmarked, so the next pass repeats
      // it.
      System.out.println(
          "  relay pass: stopped, " + failure.getMessage() + "; the row stays unsent");
    }
  }

  private static void drain(BookingsConsumer consumer) {
    System.out.println(
        "  notification service handled the broker's messages: " + consumer.drain(BROKER_DEADLINE));
  }

  // The role, when not empty, is taken first (SET ROLE), as the monolith's Database would.
  private static void tryRead(StackSettings stack, String user, String role, String table) {
    Database.Settings login = stack.login(user, Database.Access.ONE_LOGIN);
    String reader = role.isEmpty() ? user : user + " as " + role;
    try (Connection connection =
            DriverManager.getConnection(login.jdbcUrl(), login.user(), login.password());
        Statement statement = connection.createStatement()) {
      if (!role.isEmpty()) {
        statement.execute("SET ROLE " + role);
      }
      try (ResultSet row = statement.executeQuery("SELECT count(*) FROM " + table)) {
        row.next();
        System.out.println(
            String.format(
                Locale.ROOT, "  %s reads %s: allowed, %d rows", reader, table, row.getLong(1)));
      }
    } catch (SQLException refused) {
      System.out.println(
          String.format(
              Locale.ROOT,
              "  %s reads %s: refused, %s (SQLSTATE %s)",
              reader,
              table,
              refused.getMessage().lines().findFirst().orElse("no message"),
              refused.getSQLState()));
    }
  }

  private static long count(Connection owner, String tableAndCondition) throws SQLException {
    try (Statement statement = owner.createStatement();
        ResultSet row = statement.executeQuery("SELECT count(*) FROM " + tableAndCondition)) {
      row.next();
      return row.getLong(1);
    }
  }
}
