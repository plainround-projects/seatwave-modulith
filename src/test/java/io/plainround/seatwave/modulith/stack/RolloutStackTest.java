package io.plainround.seatwave.modulith.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.modulith.platform.Migrations;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Section 10's rollout, run: holds moves from Module 13's schema to inventory's in one statement;
 * old code keeps reading it through a view, cannot write it, and sees the owner's new rows; when
 * the view is dropped, the old name is gone.
 */
@Tag("stack")
class RolloutStackTest {

  private static final String OLD_NAME = "seatwave.holds";
  private static final String NEW_ROW =
      """
      INSERT INTO inventory.holds (hold_id, event_id, seat_label, customer_id, state, created_at,
        expires_at) VALUES ('00000000-0000-4000-8000-000000000009', 1, 'A-9', 1003, 'ACTIVE',
        now(), now() + interval '300 seconds')
      """;

  @Test
  void tableMovesToItsOwnerWhileOldReadersKeepReadingThroughAView()
      throws SQLException, IOException {
    try (Connection owner = StackDatabase.connectToRollout("seatwave_owner")) {
      try (Statement reset = owner.createStatement()) {
        reset.execute("DROP SCHEMA IF EXISTS seatwave, inventory CASCADE");
      }
      Migrations.apply(owner, List.of("/rollout/R0__module13_holds.sql"));
      assertThat(count("seatwave_app", OLD_NAME)).isEqualTo("2");
      assertThat(write("seatwave_app")).isEqualTo("written");

      Migrations.apply(owner, List.of("/rollout/R1__move_holds_to_inventory.sql"));
      assertThat(count("seatwave_app", OLD_NAME)).isEqualTo("3");
      assertThat(write("seatwave_app")).isEqualTo("42501");
      assertThat(count("seatwave_app", "inventory.holds")).isEqualTo("42501");
      try (Connection inventory = StackDatabase.connectToRollout("inventory_module");
          Statement statement = inventory.createStatement()) {
        statement.executeUpdate(NEW_ROW);
      }
      assertThat(count("seatwave_app", OLD_NAME)).isEqualTo("4");

      Migrations.apply(owner, List.of("/rollout/R2__drop_the_view.sql"));
      assertThat(count("seatwave_app", OLD_NAME)).isEqualTo("42501");
      assertThat(count("inventory_module", "inventory.holds")).isEqualTo("4");
    }
  }

  // The row count, or the SQLSTATE of the refusal.
  private static String count(String login, String table) throws SQLException {
    try (Connection connection = StackDatabase.connectToRollout(login);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rows.next();
      return String.valueOf(rows.getLong(1));
    } catch (SQLException refused) {
      return refused.getSQLState();
    }
  }

  // Old code's write of a new hold through the old name: "written", or the SQLSTATE.
  private static String write(String login) throws SQLException {
    try (Connection connection = StackDatabase.connectToRollout(login);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO seatwave.holds (hold_id, event_id, seat_label, customer_id, state,"
              + " created_at, expires_at) VALUES (gen_random_uuid(), 1, 'A-1', 1000, 'ACTIVE',"
              + " now(), now() + interval '300 seconds')");
      return "written";
    } catch (SQLException refused) {
      return refused.getSQLState();
    }
  }
}
