package io.plainround.seatwave.modulith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.modulith.platform.Database;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The stack's settings file: every problem at once, and the passwords read from their files. */
class StackSettingsTest {

  private static final String VALID =
      """
      db.url=jdbc:postgresql://127.0.0.1:23210/seatwave
      db.rolloutUrl=jdbc:postgresql://127.0.0.1:23210/seatwave_rollout
      db.secretsFolder=../.secrets
      kafka.bootstrapServers=127.0.0.1:23290
      """;

  @TempDir Path project;

  @Test
  void validFileGivesEveryLoginItsPasswordFromItsFile() throws IOException {
    Path settingsFile = write(VALID);
    Files.createDirectories(project.resolve(".secrets"));
    Files.writeString(project.resolve(".secrets/seatwave_app.password"), "app-password\n");

    StackSettings settings = StackSettings.load(settingsFile).discardError().orElseThrow();
    Database.Settings login = settings.login("seatwave_app", Database.Access.ROLE_PER_MODULE);

    assertThat(login.password()).isEqualTo("app-password");
    assertThat(login.jdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:23210/seatwave");
    assertThat(settings.rolloutLogin("seatwave_app").jdbcUrl()).endsWith("/seatwave_rollout");
  }

  @Test
  void everyMissingOrWrongKeyIsReportedTogether() throws IOException {
    Path settingsFile = write("db.url=jdbc:h2:mem:x\n");

    Result<StackSettings, List<String>> loaded = StackSettings.load(settingsFile);

    List<String> problems = loaded.map(settings -> List.<String>of()).recoverError(found -> found);
    assertThat(problems)
        .hasSize(4)
        .anyMatch(problem -> problem.endsWith("db.rolloutUrl is missing"))
        .anyMatch(problem -> problem.endsWith("kafka.bootstrapServers is missing"))
        .anyMatch(problem -> problem.contains("db.url must start with jdbc:postgresql://"));
  }

  @Test
  void unreadableFileIsOneProblemThatNamesIt() {
    Path missing = project.resolve("config/none.properties");

    List<String> problems =
        StackSettings.load(missing).map(settings -> List.<String>of()).recoverError(found -> found);

    assertThat(problems).singleElement().asString().contains("none.properties");
  }

  @Test
  void missingPasswordSaysToStartTheStack() throws IOException {
    StackSettings settings = StackSettings.load(write(VALID)).discardError().orElseThrow();

    assertThatThrownBy(() -> settings.login("seatwave_owner", Database.Access.ONE_LOGIN))
        .hasMessageContaining("no password for seatwave_owner")
        .hasMessageContaining("scripts/stack.sh up");
  }

  private Path write(String content) throws IOException {
    Path config = Files.createDirectories(project.resolve("config"));
    return Files.writeString(config.resolve("stack.properties"), content, StandardCharsets.UTF_8);
  }
}
