package io.plainround.seatwave.modulith;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The module boundaries, as tests that fail the build: no module uses another module's internals,
 * the modules form no cycle, no module's code names another module's table, and every table in the
 * schema has exactly one owner, the module the context map names. The database enforces the same
 * wall a second time with one role per module (the stack's GrantsStackTest).
 */
class ModuleBoundaryTest {

  private static final String BASE = "io.plainround.seatwave.modulith";
  private static final List<String> MODULES =
      List.of("catalog", "inventory", "booking", "payment", "notification");
  // Where the sources and the context map are, relative to the project folder; a run from another
  // folder passes the project folder as a system property.
  private static final Path PROJECT =
      Path.of(System.getProperty("seatwave.project", ".")).toAbsolutePath();
  private static final Path SOURCES =
      PROJECT.resolve("src/main/java/io/plainround/seatwave/modulith");
  // The production schema and its H2 stand-in: both must give every table the map's owner. The
  // tree after the extraction also has V2, which adds two tables.
  private static final List<Path> SCHEMAS =
      List.of(
          PROJECT.resolve("src/main/resources/db/postgresql/V1__module_schemas.sql"),
          PROJECT.resolve("src/main/resources/db/h2/V1__module_schemas.sql"));
  private static final List<Path> LATER_SCHEMAS =
      List.of(
          PROJECT.resolve("src/main/resources/db/postgresql/V2__extract_notification.sql"),
          PROJECT.resolve("src/main/resources/db/h2/V2__extract_notification.sql"));
  private static final Path CONTEXT_MAP = PROJECT.resolve("docs/context-map.txt");
  // A schema-qualified table name inside SQL, such as inventory.holds.
  private static final Pattern TABLE_NAME =
      Pattern.compile("\\b(catalog|inventory|booking|payment|notification)\\.([a-z_]+)\\b");
  private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE ([a-z_]+)\\.([a-z_]+)");
  private static final Pattern OWNER_LINE =
      Pattern.compile("^\\s+([a-z_]+)\\.([a-z_]+)\\s+owner: ([a-z]+)", Pattern.MULTILINE);

  private static final JavaClasses SEATWAVE =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  void noModuleUsesAnotherModulesInternals() {
    List<ArchRule> rules = new ArrayList<>();
    for (String module : MODULES) {
      rules.add(
          noClasses()
              .that()
              .resideOutsideOfPackage(BASE + "." + module + "..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage(BASE + "." + module + ".internal..")
              .as("only " + module + " uses " + module + ".internal"));
    }
    CompositeArchRule.of(rules).check(SEATWAVE);
  }

  @Test
  void modulesDependOnEachOtherWithoutCycles() {
    slices().matching(BASE + ".(*)..").should().beFreeOfCycles().check(SEATWAVE);
  }

  @Test
  void noModuleNamesAnotherModulesTables() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : MODULES) {
      if (!Files.isDirectory(SOURCES.resolve(module))) {
        // A module extracted into its own service has left this source tree.
        continue;
      }
      try (Stream<Path> files = Files.walk(SOURCES.resolve(module))) {
        for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
          Matcher table = TABLE_NAME.matcher(Files.readString(file, StandardCharsets.UTF_8));
          while (table.find()) {
            // "inventory.internal" in an import is a package, not a table.
            if (!table.group(1).equals(module) && !table.group(2).equals("internal")) {
              violations.add(SOURCES.relativize(file) + " names " + table.group());
            }
          }
        }
      }
    }
    assertThat(violations).as("tables named outside their owner module").isEmpty();
  }

  @Test
  void everyTableHasExactlyOneOwnerAndItIsTheModuleOfItsSchema() throws IOException {
    Map<String, String> ownerByTable = new TreeMap<>();
    Matcher owner = OWNER_LINE.matcher(Files.readString(CONTEXT_MAP, StandardCharsets.UTF_8));
    List<String> problems = new ArrayList<>();
    while (owner.find()) {
      String table = owner.group(1) + "." + owner.group(2);
      if (ownerByTable.put(table, owner.group(3)) != null) {
        problems.add(table + " is listed twice");
      }
      if (!owner.group(1).equals(owner.group(3))) {
        problems.add(
            table + " lives in schema " + owner.group(1) + " but is owned by " + owner.group(3));
      }
    }
    List<Path> schemas = new ArrayList<>(SCHEMAS);
    LATER_SCHEMAS.stream().filter(Files::exists).forEach(schemas::add);
    for (Path schema : schemas) {
      Matcher created = CREATE_TABLE.matcher(Files.readString(schema, StandardCharsets.UTF_8));
      while (created.find()) {
        String table = created.group(1) + "." + created.group(2);
        if (!ownerByTable.containsKey(table)) {
          problems.add(table + " in " + schema.getFileName() + " has no owner in the context map");
        }
      }
    }
    assertThat(problems).as("table ownership").isEmpty();
  }
}
