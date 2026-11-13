package io.plainround.seatwave.modulith;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.platform.Database;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Where the stack of {@code compose.yaml} is, read from {@code config/stack.properties}: the
 * PostgreSQL databases, the folder of the generated passwords, and the Kafka broker. Every key is
 * required, and every problem is reported at once.
 *
 * @param databaseUrl the JDBC address of the database {@code seatwave}
 * @param rolloutUrl the JDBC address of the database {@code seatwave_rollout} (Section 10)
 * @param secretsFolder where {@code scripts/stack.sh up} wrote one password file per login
 * @param kafkaBootstrapServers the broker's address, {@code host:port}
 */
public record StackSettings(
    String databaseUrl, String rolloutUrl, Path secretsFolder, String kafkaBootstrapServers) {

  private static final List<String> KEYS =
      List.of("db.url", "db.rolloutUrl", "db.secretsFolder", "kafka.bootstrapServers");
  private static final String POSTGRES_PREFIX = "jdbc:postgresql://";

  /** Refuses a missing field. */
  public StackSettings {
    Objects.requireNonNull(databaseUrl, "databaseUrl");
    Objects.requireNonNull(rolloutUrl, "rolloutUrl");
    Objects.requireNonNull(secretsFolder, "secretsFolder");
    Objects.requireNonNull(kafkaBootstrapServers, "kafkaBootstrapServers");
  }

  /**
   * Reads the settings file; a relative secrets folder is resolved against the file's folder.
   *
   * @return the settings, or every problem found, each in one line
   */
  public static Result<StackSettings, List<String>> load(Path file) {
    Objects.requireNonNull(file, "file");
    Properties values = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      values.load(reader);
    } catch (IOException unreadable) {
      return Result.error(List.of("cannot read the settings file " + file + ": " + unreadable));
    }
    List<String> problems = new ArrayList<>();
    for (String key : KEYS) {
      if (values.getProperty(key, "").isBlank()) {
        problems.add(file + ": " + key + " is missing");
      }
    }
    for (String key : List.of("db.url", "db.rolloutUrl")) {
      String url = values.getProperty(key, "");
      if (!url.isBlank() && !url.startsWith(POSTGRES_PREFIX)) {
        problems.add(file + ": " + key + " must start with " + POSTGRES_PREFIX + ", not " + url);
      }
    }
    if (!problems.isEmpty()) {
      return Result.error(List.copyOf(problems));
    }
    Path folder = file.toAbsolutePath().getParent();
    return Result.success(
        new StackSettings(
            values.getProperty("db.url"),
            values.getProperty("db.rolloutUrl"),
            folder.resolve(values.getProperty("db.secretsFolder")).normalize(),
            values.getProperty("kafka.bootstrapServers")));
  }

  /**
   * The settings to log in to the database {@code seatwave} as one login, with its password read
   * from {@code <secrets folder>/<login>.password} now.
   *
   * @throws IllegalStateException if the password file is missing: the stack is not up
   */
  public Database.Settings login(String user, Database.Access access) {
    return loginTo(databaseUrl, user, access);
  }

  /** The same, for the rollout database of Section 10. */
  public Database.Settings rolloutLogin(String user) {
    return loginTo(rolloutUrl, user, Database.Access.ONE_LOGIN);
  }

  private Database.Settings loginTo(String url, String user, Database.Access access) {
    Path passwordFile = secretsFolder.resolve(user + ".password");
    try {
      return new Database.Settings(
          url, user, Files.readString(passwordFile, StandardCharsets.UTF_8).strip(), access);
    } catch (IOException missing) {
      throw new IllegalStateException(
          "no password for " + user + " in " + passwordFile + "; run scripts/stack.sh up first",
          missing);
    }
  }
}
