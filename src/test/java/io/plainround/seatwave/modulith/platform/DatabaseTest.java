package io.plainround.seatwave.modulith.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The shared kernel's transactions on H2: joining, rollback, the savepoint and the settings. */
class DatabaseTest {

  private static final String TABLE_SQL = "CREATE TABLE marks (mark VARCHAR(10) PRIMARY KEY)";
  private static final String INSERT_SQL = "INSERT INTO marks (mark) VALUES (?)";

  private Connection keepAlive;
  private Database database;

  @BeforeEach
  void openDatabase() throws SQLException {
    String url = "jdbc:h2:mem:database-test-" + UUID.randomUUID();
    keepAlive = DriverManager.getConnection(url, "owner", "");
    try (Statement statement = keepAlive.createStatement()) {
      statement.execute(TABLE_SQL);
    }
    database = Database.open(new Database.Settings(url, "owner", "", Database.Access.ONE_LOGIN));
  }

  @AfterEach
  void closeDatabase() throws SQLException {
    keepAlive.close();
  }

  @Test
  void callMadeInsideATransactionJoinsItAndCommitsOnce() {
    Database moduleView = database.forModule("inventory_module");

    database.inTransaction(connection -> {
      insert(connection, "outer");
      return moduleView.inTransaction(joined -> insert(joined, "inner"));
    });

    assertThat(database.commits()).isEqualTo(1);
    assertThat(marks()).containsExactly("inner", "outer");
  }

  @Test
  void failureInsideAJoinedCallRollsBackTheWholeTransaction() {
    assertThatThrownBy(() ->
            database.inTransaction(connection -> {
              insert(connection, "outer");
              return database.inTransaction(joined -> insert(joined, "outer"));
            }))
        .hasMessage("statement failed inside a joined transaction");

    assertThat(database.commits()).isZero();
    assertThat(marks()).isEmpty();
  }

  @Test
  void refusedInsertInsideASavepointKeepsTheWorkBeforeIt() {
    boolean secondInserted =
        database.inTransaction(connection -> {
          insert(connection, "first");
          try (PreparedStatement again = connection.prepareStatement(INSERT_SQL)) {
            again.setString(1, "first");
            return Database.insertUnlessDuplicate(connection, again);
          }
        });

    assertThat(secondInserted).isFalse();
    assertThat(marks()).containsExactly("first");
  }

  @Test
  void errorOtherThanADuplicateIsNotSwallowedBySavepoint() {
    assertThatThrownBy(() ->
            database.inTransaction(connection -> {
              try (PreparedStatement tooLong = connection.prepareStatement(INSERT_SQL)) {
                tooLong.setString(1, "longer than ten");
                return Database.insertUnlessDuplicate(connection, tooLong);
              }
            }))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void isInTransactionOnlyWhileTheWorkRuns() {
    boolean inside = database.inTransaction(connection -> database.isInTransaction());

    assertThat(inside).isTrue();
    assertThat(database.isInTransaction()).isFalse();
  }

  @Test
  void roleNameThatIsNotAPlainIdentifierIsRefused() {
    assertThatThrownBy(() -> database.forModule("inventory; DROP SCHEMA booking"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a role name");
  }

  @Test
  void settingsNeverPrintThePassword() {
    Database.Settings settings =
        new Database.Settings(
            "jdbc:postgresql://127.0.0.1/x",
            "app",
            "secret-value",
            Database.Access.ROLE_PER_MODULE);

    assertThat(settings.toString()).doesNotContain("secret-value").contains("app");
  }

  @Test
  void settingsRefuseAnAddressThatIsNotJdbc() {
    assertThatThrownBy(
            () -> new Database.Settings("postgres://x", "app", "", Database.Access.ONE_LOGIN))
        .hasMessage("not a JDBC URL: 'postgres://x'");
  }

  @Test
  void missingMigrationIsNamedInTheError() {
    assertThatThrownBy(() -> Migrations.apply(keepAlive, List.of("/db/none.sql")))
        .isInstanceOf(IOException.class)
        .hasMessage("migration /db/none.sql is not on the class path");
  }

  private static int insert(Connection connection, String mark) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
      insert.setString(1, mark);
      return insert.executeUpdate();
    }
  }

  private List<String> marks() {
    return database.inTransaction(connection -> {
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT mark FROM marks ORDER BY mark")) {
        List<String> found = new ArrayList<>();
        while (rows.next()) {
          found.add(rows.getString(1));
        }
        return found;
      }
    });
  }
}
