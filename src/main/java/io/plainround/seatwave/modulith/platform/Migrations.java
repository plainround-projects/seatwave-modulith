package io.plainround.seatwave.modulith.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Applies SQL files from the class path, in order, statement by statement. The files hold no
 * function bodies and no semicolon inside a string, so a statement ends at each semicolon; comment
 * lines are dropped first. A lab program's or a test's tool: production applies its migrations with
 * a migration tool, once per version (code guidelines, rule 70). Thread-safe: no state.
 */
public final class Migrations {

  private Migrations() {}

  /**
   * Runs every statement of every file on the connection, in auto-commit mode.
   *
   * @param resources class-path names such as {@code /db/h2/V1__module_schemas.sql}
   * @throws IOException if a file is missing or unreadable
   * @throws SQLException the first statement the database refused
   */
  public static void apply(Connection connection, List<String> resources)
      throws SQLException, IOException {
    Objects.requireNonNull(connection, "connection");
    try (Statement statement = connection.createStatement()) {
      for (String resource : List.copyOf(resources)) {
        for (String sql : statementsOf(read(resource))) {
          statement.execute(sql);
        }
      }
    }
  }

  private static String read(String resource) throws IOException {
    try (InputStream file = Migrations.class.getResourceAsStream(resource)) {
      if (file == null) {
        throw new IOException("migration " + resource + " is not on the class path");
      }
      return new String(file.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> statementsOf(String script) {
    StringBuilder withoutComments = new StringBuilder();
    for (String line : script.split("\n")) {
      if (!line.strip().startsWith("--")) {
        withoutComments.append(line).append('\n');
      }
    }
    return List.of(withoutComments.toString().split(";")).stream()
        .filter(sql -> !sql.isBlank())
        .toList();
  }
}
