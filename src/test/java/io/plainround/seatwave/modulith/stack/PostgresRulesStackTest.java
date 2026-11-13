package io.plainround.seatwave.modulith.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.modulith.catalog.Catalog;
import io.plainround.seatwave.modulith.inventory.Inventory;
import io.plainround.seatwave.modulith.platform.Database;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What H2 only imitates, on PostgreSQL itself: the partial unique index one_claim_per_seat, and the
 * savepoint that keeps a transaction usable after the index refuses an insert.
 */
@Tag("stack")
class PostgresRulesStackTest {

  private static final Duration HOLD_LENGTH = Duration.ofSeconds(300);
  private static final Instant NOW = Instant.parse("2026-11-20T19:00:00Z");
  private static final long ARENA_NIGHT = 1;

  private Database database;
  private Inventory inventory;

  @BeforeEach
  void freshSchemas() throws SQLException, IOException {
    StackDatabase.resetToV1();
    database =
        Database.open(
            StackDatabase.settings().login("seatwave_app", Database.Access.ROLE_PER_MODULE));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    inventory = Inventory.create(database, clock, HOLD_LENGTH, Catalog.create(database));
  }

  @Test
  void secondLiveClaimIsRefusedAsAnAnswerNotAnError() {
    inventory.hold(1001, ARENA_NIGHT, "A-7");

    String second =
        inventory
            .hold(1002, ARENA_NIGHT, "A-7")
            .map(held -> "held")
            .recoverError(refusal -> refusal.reason());

    assertThat(second).isEqualTo("seat A-7 is already claimed");
  }

  @Test
  void refusedClaimInsideALargerTransactionLeavesTheTransactionUsable() {
    inventory.hold(1001, ARENA_NIGHT, "A-7");

    // One transaction that holds two seats for one fan: the first is taken, the second is free.
    String secondSeat =
        database.inTransaction(connection -> {
          inventory.hold(1002, ARENA_NIGHT, "A-7");
          return inventory
              .hold(1002, ARENA_NIGHT, "A-8")
              .map(held -> "held")
              .recoverError(refusal -> refusal.reason());
        });
    String thirdFan =
        inventory
            .hold(1003, ARENA_NIGHT, "A-8")
            .map(held -> "held")
            .recoverError(refusal -> refusal.reason());

    assertThat(secondSeat).isEqualTo("held");
    assertThat(thirdFan)
        .as("the second seat's hold committed")
        .isEqualTo("seat A-8 is already claimed");
  }

  @Test
  void partialIndexIgnoresAHoldThatHasEnded() throws SQLException {
    UUID first =
        inventory
            .hold(1001, ARENA_NIGHT, "A-7")
            .map(Inventory.Held::holdId)
            .discardError()
            .orElseThrow();
    try (Connection owner = StackDatabase.connect("seatwave_owner");
        PreparedStatement release =
            owner.prepareStatement(
                "UPDATE inventory.holds SET state = 'RELEASED' WHERE hold_id = ?")) {
      release.setObject(1, first);
      release.executeUpdate();
    }

    boolean held = inventory.hold(1002, ARENA_NIGHT, "A-7").discardError().isPresent();

    assertThat(held).isTrue();
  }

  @Test
  void oneClaimPerSeatIsAPartialUniqueIndexOnPostgres() throws SQLException {
    try (Connection owner = StackDatabase.connect("seatwave_owner");
        Statement statement = owner.createStatement();
        ResultSet index =
            statement.executeQuery(
                "SELECT indexdef FROM pg_catalog.pg_indexes WHERE schemaname = 'inventory'"
                    + " AND indexname = 'one_claim_per_seat'")) {
      assertThat(index.next()).isTrue();
      assertThat(index.getString(1))
          .startsWith("CREATE UNIQUE INDEX one_claim_per_seat ON inventory.holds")
          .contains("WHERE");
    }
  }
}
