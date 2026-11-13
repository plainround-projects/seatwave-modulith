package io.plainround.seatwave.modulith;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * ModulithCli prints what the module shows, byte for byte: the golden file is the output that
 * Section 8 embeds. The stack's ModulithOnPostgresStackTest compares PostgreSQL's run with it.
 */
class ProgramOutputTest {

  @Test
  void modulithCliOnH2PrintsTheGoldenOutput() throws SQLException, IOException {
    assertThat(runOnH2()).isEqualTo(golden("ModulithCli.txt"));
  }

  static String runOnH2() throws SQLException, IOException {
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8)) {
      ModulithCli.runOnH2(out);
    }
    return printed.toString(StandardCharsets.UTF_8);
  }

  static String golden(String name) throws IOException {
    try (InputStream file = ProgramOutputTest.class.getResourceAsStream("/golden/" + name)) {
      if (file == null) {
        throw new IOException("golden file " + name + " is missing");
      }
      return new String(file.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
