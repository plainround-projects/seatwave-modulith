package io.plainround.seatwave.modulith.stack;

import io.plainround.seatwave.modulith.StackSettings;
import io.plainround.seatwave.modulith.platform.Database;
import io.plainround.seatwave.modulith.platform.Migrations;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * The stack's PostgreSQL for the tests tagged "stack": the settings of {@code
 * config/stack.properties}, a connection as any login, and a reset to a fresh V1. Thread-safe: no
 * state.
 */
final class StackDatabase {

  static final List<String> MODULES =
      List.of("catalog", "inventory", "booking", "payment", "notification");
  static final List<String> LOGINS =
      List.of(
          "seatwave_owner",
          "seatwave_app",
          "catalog_module",
          "inventory_module",
          "booking_module",
          "payment_module",
          "notification_module",
          "notification_service");

  private StackDatabase() {}

  static StackSettings settings() {
    String file = System.getProperty("seatwave.stackSettings");
    if (file == null) {
      throw new IllegalStateException("run the stack tests with ./gradlew stackTest");
    }
    return StackSettings.load(Path.of(file))
        .discardError()
        .orElseThrow(() -> new IllegalStateException("config/stack.properties is not valid"));
  }

  /** A new connection to the database seatwave as one login, in auto-commit mode. */
  static Connection connect(String login) throws SQLException {
    Database.Settings settings = settings().login(login, Database.Access.ONE_LOGIN);
    return DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
  }

  /** A new connection to the rollout database of Section 10 as one login. */
  static Connection connectToRollout(String login) throws SQLException {
    Database.Settings settings = settings().rolloutLogin(login);
    return DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
  }

  /** Drops the five schemas and applies V1 again, as the owner. */
  static void resetToV1() throws SQLException, IOException {
    try (Connection owner = connect("seatwave_owner")) {
      Migrations.apply(
          owner, List.of("/db/postgresql/reset.sql", "/db/postgresql/V1__module_schemas.sql"));
    }
  }
}
