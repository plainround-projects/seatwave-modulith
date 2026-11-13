package io.plainround.seatwave.modulith.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The module boundary, enforced by the database: every module's login is refused every other
 * module's tables, the deployable's login reads nothing until it takes a module's role, and every
 * login's search_path is empty. ModuleBoundaryTest enforces the same wall on the code.
 */
@Tag("stack")
class GrantsStackTest {

  // PostgreSQL's SQLSTATEs: insufficient_privilege and undefined_table.
  private static final String REFUSED = "42501";
  private static final String NO_SUCH_TABLE = "42P01";
  private static final String ALLOWED = "allowed";

  private static final Map<String, List<String>> TABLES_BY_MODULE = new TreeMap<>();

  @BeforeAll
  static void freshSchemas() throws SQLException, IOException {
    StackDatabase.resetToV1();
    try (Connection owner = StackDatabase.connect("seatwave_owner");
        Statement statement = owner.createStatement();
        ResultSet tables =
            statement.executeQuery(
                "SELECT schemaname, tablename FROM pg_catalog.pg_tables WHERE schemaname IN"
                    + " ('catalog', 'inventory', 'booking', 'payment', 'notification')")) {
      while (tables.next()) {
        TABLES_BY_MODULE
            .computeIfAbsent(tables.getString(1), schema -> new ArrayList<>())
            .add(tables.getString(1) + "." + tables.getString(2));
      }
    }
  }

  static Stream<Arguments> everyModuleAgainstEveryOther() {
    return StackDatabase.MODULES.stream()
        .flatMap(reader ->
            StackDatabase.MODULES.stream()
                .filter(owner -> !owner.equals(reader))
                .map(owner -> Arguments.of(reader, owner)));
  }

  @ParameterizedTest(name = "{0}_module is refused every table of {1}")
  @MethodSource("everyModuleAgainstEveryOther")
  void moduleLoginIsRefusedEveryTableOfAnotherModule(String reader, String owner)
      throws SQLException {
    try (Connection login = StackDatabase.connect(reader + "_module")) {
      assertThat(TABLES_BY_MODULE.get(owner)).isNotEmpty();
      for (String table : TABLES_BY_MODULE.get(owner)) {
        assertThat(read(login, table)).as(reader + " reads " + table).isEqualTo(REFUSED);
      }
    }
  }

  @Test
  void moduleLoginReadsEveryTableOfItsOwnSchema() throws SQLException {
    for (String module : StackDatabase.MODULES) {
      try (Connection login = StackDatabase.connect(module + "_module")) {
        for (String table : TABLES_BY_MODULE.get(module)) {
          assertThat(read(login, table)).as(module + " reads " + table).isEqualTo(ALLOWED);
        }
      }
    }
  }

  @Test
  void deployableLoginReadsNothingUntilItTakesAModulesRole() throws SQLException {
    try (Connection app = StackDatabase.connect("seatwave_app")) {
      for (List<String> tables : TABLES_BY_MODULE.values()) {
        for (String table : tables) {
          assertThat(read(app, table)).as("seatwave_app reads " + table).isEqualTo(REFUSED);
        }
      }
    }
  }

  @Test
  void deployableLoginInAModulesRoleReadsOnlyThatModulesTables() throws SQLException {
    try (Connection app = StackDatabase.connect("seatwave_app");
        Statement statement = app.createStatement()) {
      for (String module : StackDatabase.MODULES) {
        statement.execute("SET ROLE " + module + "_module");
        for (Map.Entry<String, List<String>> schema : TABLES_BY_MODULE.entrySet()) {
          String expected = schema.getKey().equals(module) ? ALLOWED : REFUSED;
          for (String table : schema.getValue()) {
            assertThat(read(app, table)).as(module + " role reads " + table).isEqualTo(expected);
          }
        }
        statement.execute("RESET ROLE");
      }
    }
  }

  @Test
  void everyLoginHasAnEmptySearchPath() throws SQLException {
    for (String login : StackDatabase.LOGINS) {
      try (Connection connection = StackDatabase.connect(login);
          Statement statement = connection.createStatement();
          ResultSet path = statement.executeQuery("SHOW search_path")) {
        path.next();
        assertThat(path.getString(1)).as(login + "'s search_path").isEqualTo("\"\"");
      }
    }
  }

  @Test
  void unqualifiedTableNameIsAnErrorEvenForItsOwner() throws SQLException {
    try (Connection inventory = StackDatabase.connect("inventory_module")) {
      assertThat(read(inventory, "holds")).isEqualTo(NO_SUCH_TABLE);
      assertThat(read(inventory, "inventory.holds")).isEqualTo(ALLOWED);
    }
  }

  // "allowed", or the SQLSTATE of the refusal; the table name comes from pg_tables or a constant.
  private static String read(Connection connection, String table) {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rows.next();
      return ALLOWED;
    } catch (SQLException refused) {
      return refused.getSQLState();
    }
  }
}
