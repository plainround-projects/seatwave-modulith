package io.plainround.seatwave.notificationservice;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.modulith.StackSettings;
import io.plainround.seatwave.modulith.booking.Bookings;
import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.Inventory;
import io.plainround.seatwave.modulith.payment.Payments;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.DomainEvents;
import io.plainround.seatwave.modulith.platform.Migrations;
import io.plainround.seatwave.notificationservice.NotificationService.Outcome;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The extracted notification service on PostgreSQL and Kafka: a duplicate delivery queues one email
 * because of the claim, a sale commits while the service is down, and the database keeps the two
 * deployables in their own tables. Each test has a topic of its own.
 */
class ExtractionStackTest {

  private static final Duration DEADLINE = Duration.ofSeconds(30);
  private static final Duration POLL = Duration.ofMillis(200);
  private static final Duration HOLD_LENGTH = Duration.ofSeconds(300);
  private static final Instant NOW = Instant.parse("2026-11-20T19:00:00Z");
  private static final long ARENA_NIGHT = 1;
  private static final List<String> MIGRATIONS =
      List.of(
          "/db/postgresql/reset.sql",
          "/db/postgresql/V1__module_schemas.sql",
          "/db/postgresql/V2__extract_notification.sql");

  private StackSettings stack;
  private String topic;
  private Database monolith;
  private Inventory inventory;
  private Bookings bookings;
  private BookingsConsumer consumer;
  private KafkaProducer<String, String> producer;

  @BeforeEach
  void freshStack() throws Exception {
    stack =
        StackSettings.load(Path.of(System.getProperty("seatwave.stackSettings")))
            .discardError()
            .orElseThrow();
    try (Connection owner = connect("seatwave_owner")) {
      Migrations.apply(owner, MIGRATIONS);
    }
    topic = "seatwave.bookings.test-" + UUID.randomUUID();
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
          .all()
          .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    }
    BookingsConsumer.awaitLeaders(stack.kafkaBootstrapServers(), topic, DEADLINE);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    monolith = Database.open(stack.login("seatwave_app", Database.Access.ROLE_PER_MODULE));
    DomainEvents events = new DomainEvents();
    Catalog catalog = Catalog.create(monolith);
    inventory = Inventory.create(monolith, clock, HOLD_LENGTH, catalog);
    bookings =
        Bookings.create(
            monolith,
            clock,
            events,
            new Bookings.Collaborators(
                catalog, inventory, Payments.create(monolith, key -> false)));
    Bookings.publishThroughOutbox(events, monolith, clock);
    NotificationService service =
        new NotificationService(
            Database.open(stack.login("notification_service", Database.Access.ONE_LOGIN)), clock);
    consumer = new BookingsConsumer(stack.kafkaBootstrapServers(), topic, service, POLL);
    producer =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers()),
            new StringSerializer(),
            new StringSerializer());
  }

  @AfterEach
  void closeClients() {
    consumer.close();
    producer.close();
  }

  @Test
  void duplicateDeliveryAfterALostAcknowledgementQueuesOneEmail() throws SQLException {
    sell(1001, "A-7");
    AtomicBoolean loseTheAcknowledgement = new AtomicBoolean(true);
    BiConsumer<String, String> broker =
        (key, payload) -> {
          send(key, payload);
          if (loseTheAcknowledgement.getAndSet(false)) {
            throw new IllegalStateException("connection lost before the acknowledgement");
          }
        };
    relayIgnoringTheLostAcknowledgement(broker);
    Bookings.relayOutbox(monolith, Clock.fixed(NOW, ZoneOffset.UTC), broker);

    List<Outcome> outcomes = consumer.drain(DEADLINE);

    assertThat(outcomes).containsExactly(Outcome.HANDLED, Outcome.DUPLICATE);
    assertThat(count("notification.emails")).isEqualTo(1);
    assertThat(count("notification.processed_messages")).isEqualTo(1);
  }

  @Test
  void saleCommitsWhileTheServiceIsDownAndItsMessageWaits() throws SQLException {
    sell(1001, "A-7");
    Bookings.relayOutbox(monolith, Clock.fixed(NOW, ZoneOffset.UTC), this::send);

    long waiting = consumer.waiting();
    long emailsBefore = count("notification.emails");
    List<Outcome> outcomes = consumer.drain(DEADLINE);

    assertThat(waiting).isEqualTo(1);
    assertThat(emailsBefore).isZero();
    assertThat(outcomes).containsExactly(Outcome.HANDLED);
    assertThat(count("booking.bookings")).isEqualTo(1);
  }

  @Test
  void unreadableMessageIsParkedAndQueuesNothing() throws SQLException {
    send("bad", "not a booking event");

    assertThat(consumer.drain(DEADLINE)).containsExactly(Outcome.PARKED);
    assertThat(count("notification.emails")).isZero();
  }

  @Test
  void eachDeployableIsRefusedTheOthersTables() throws SQLException {
    assertThat(read("notification_service", "", "booking.bookings")).isEqualTo("42501");
    assertThat(read("notification_service", "", "notification.emails")).isEqualTo("allowed");
    assertThat(read("seatwave_app", "booking_module", "booking.bookings")).isEqualTo("allowed");
    assertThat(read("seatwave_app", "notification_module", "notification.emails"))
        .isEqualTo("42501");
  }

  private void sell(long fan, String seat) {
    UUID hold =
        inventory
            .hold(fan, ARENA_NIGHT, seat)
            .map(Inventory.Held::holdId)
            .discardError()
            .orElseThrow();
    assertThat(bookings.confirm(hold, fan).discardError()).isPresent();
  }

  private void relayIgnoringTheLostAcknowledgement(BiConsumer<String, String> broker) {
    try {
      Bookings.relayOutbox(monolith, Clock.fixed(NOW, ZoneOffset.UTC), broker);
    } catch (IllegalStateException lost) {
      // The broker stored the message; the relay did not hear so and marks nothing.
      assertThat(lost).hasMessageContaining("acknowledgement");
    }
  }

  private void send(String key, String payload) {
    try {
      producer
          .send(new ProducerRecord<>(topic, key, payload))
          .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("the broker did not store the message", failed);
    }
  }

  private Connection connect(String login) throws SQLException {
    Database.Settings settings = stack.login(login, Database.Access.ONE_LOGIN);
    return DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
  }

  private long count(String table) throws SQLException {
    try (Connection owner = connect("seatwave_owner");
        Statement statement = owner.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  // "allowed", or the SQLSTATE of the refusal; a role, when given, is taken first.
  private String read(String login, String role, String table) throws SQLException {
    try (Connection connection = connect(login);
        Statement statement = connection.createStatement()) {
      if (!role.isEmpty()) {
        statement.execute("SET ROLE " + role);
      }
      try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
        rows.next();
        return "allowed";
      }
    } catch (SQLException refused) {
      return refused.getSQLState();
    }
  }
}
