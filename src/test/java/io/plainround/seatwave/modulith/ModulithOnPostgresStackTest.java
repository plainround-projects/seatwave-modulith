package io.plainround.seatwave.modulith;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The whole lab on PostgreSQL, every module in its own role inside the shared transactions: it
 * prints exactly what the H2 run prints, the golden file Section 8 embeds.
 */
@Tag("stack")
class ModulithOnPostgresStackTest {

  @Test
  void labOnPostgresPrintsWhatTheH2RunPrints() throws SQLException, IOException {
    StackSettings stack =
        StackSettings.load(Path.of(System.getProperty("seatwave.stackSettings")))
            .discardError()
            .orElseThrow();
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8)) {
      ModulithCli.runOnPostgres(out, stack);
    }

    assertThat(printed.toString(StandardCharsets.UTF_8))
        .isEqualTo(ProgramOutputTest.golden("ModulithCli.txt"));
  }
}
