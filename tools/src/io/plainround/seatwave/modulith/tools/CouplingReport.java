package io.plainround.seatwave.modulith.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Counts coupling in a Java source tree with the JDK alone, by reading its text.
 *
 * <ul>
 *   <li>{@code deps <source root> <base package>}: the dependencies between top-level packages
 *       ("modules") from import lines, imports of another module's {@code internal} package,
 *       cycles, and every table that the SQL of more than one file names.
 *   <li>{@code diff <before root> <after root>}: the files and modules a change touched.
 * </ul>
 *
 * <p>It sees imports, not same-package references or fully qualified names in code; the modular
 * code and the fixture import every type they use from another package, which the boundary test
 * checks on bytecode with ArchUnit.
 */
public final class CouplingReport {

  private static final Pattern PACKAGE_LINE =
      Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
  private static final Pattern IMPORT_LINE =
      Pattern.compile("^import (?:static )?([\\w.]+);", Pattern.MULTILINE);
  // A table after a SQL keyword: holds, or inventory.holds when schemas carry the module's name.
  private static final Pattern TABLE_USE =
      Pattern.compile("\\b(?:FROM|JOIN|INTO|UPDATE)\\s+([a-z_]+(?:\\.[a-z_]+)?)");
  private static final String ROOT_MODULE = "(root)";
  private static final String OUTSIDE = "(outside)";
  private static final int USAGE_ERROR = 2;

  private CouplingReport() {}

  /**
   * Runs one report; exits with status 2 on wrong arguments, 1 if a cycle or internal use is found.
   */
  public static void main(String[] arguments) throws IOException {
    if (arguments.length == 3 && arguments[0].equals("deps")) {
      boolean clean = printDependencies(Path.of(arguments[1]), arguments[2]);
      System.exit(clean ? 0 : 1);
    } else if (arguments.length == 3 && arguments[0].equals("diff")) {
      printDiff(Path.of(arguments[1]), Path.of(arguments[2]));
    } else {
      System.err.println(
          "usage: CouplingReport deps <source root> <base package>"
              + " | diff <before root> <after root>");
      System.exit(USAGE_ERROR);
    }
  }

  private static boolean printDependencies(Path root, String basePackage) throws IOException {
    Map<String, Map<String, Integer>> importsByModule = new TreeMap<>();
    Map<String, Set<String>> filesByTable = new TreeMap<>();
    List<String> internalUses = new ArrayList<>();
    List<Path> files = javaFiles(root);
    for (Path file : files) {
      String text = Files.readString(file, StandardCharsets.UTF_8);
      String module = moduleOf(packageOf(text, file), basePackage);
      importsByModule.computeIfAbsent(module, ignored -> new TreeMap<>());
      Matcher imported = IMPORT_LINE.matcher(text);
      while (imported.find()) {
        String target = imported.group(1);
        if (!target.startsWith(basePackage + ".")) {
          continue;
        }
        String targetModule = moduleOf(target, basePackage);
        if (targetModule.equals(module)) {
          continue;
        }
        importsByModule.get(module).merge(targetModule, 1, Integer::sum);
        if (target.contains(".internal.")) {
          internalUses.add(root.relativize(file) + " imports " + target);
        }
      }
      // The lab programs in the base package read every schema as the owner, like an administrator;
      // they are not module code, so their SQL is not counted.
      Matcher table = TABLE_USE.matcher(module.equals(ROOT_MODULE) ? "" : text);
      while (table.find()) {
        filesByTable
            .computeIfAbsent(table.group(1), ignored -> new TreeSet<>())
            .add(root.relativize(file).getFileName().toString());
      }
    }
    int crossModule =
        importsByModule.values().stream()
            .flatMap(targets -> targets.values().stream())
            .mapToInt(Integer::intValue)
            .sum();
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %d files in %d modules; %d imports across modules",
            files.size(),
            importsByModule.size(),
            crossModule));
    importsByModule.forEach((module, targets) ->
        targets.forEach((target, count) ->
            System.out.println(
                String.format(Locale.ROOT, "    %-14s -> %-14s %d", module, target, count))));
    List<List<String>> cycles = cycles(importsByModule);
    System.out.println("  cycles between modules: " + (cycles.isEmpty() ? "none" : cycles));
    System.out.println(
        "  imports of another module's internals: "
            + (internalUses.isEmpty() ? "none" : internalUses));
    StringBuilder sharedInCode = new StringBuilder();
    filesByTable.forEach((table, users) -> {
      if (users.size() > 1) {
        sharedInCode
            .append(sharedInCode.isEmpty() ? "" : "; ")
            .append(table)
            .append(" <- ")
            .append(users);
      }
    });
    System.out.println(
        String.format(
            Locale.ROOT,
            "  tables named by more than one file: %s (of %d tables named)",
            sharedInCode.isEmpty() ? "none" : sharedInCode,
            filesByTable.size()));
    return cycles.isEmpty() && internalUses.isEmpty();
  }

  private static void printDiff(Path before, Path after) throws IOException {
    Set<String> paths = new TreeSet<>();
    javaFiles(before).forEach(file -> paths.add(before.relativize(file).toString()));
    javaFiles(after).forEach(file -> paths.add(after.relativize(file).toString()));
    Set<String> modules = new TreeSet<>();
    int touched = 0;
    for (String path : paths) {
      Path old = before.resolve(path);
      Path changed = after.resolve(path);
      String verdict;
      if (!Files.exists(old)) {
        verdict = "added";
      } else if (!Files.exists(changed)) {
        verdict = "removed";
      } else if (Files.mismatch(old, changed) != -1) {
        verdict = "changed";
      } else {
        continue;
      }
      touched++;
      Path folder = Objects.requireNonNullElse(Path.of(path).getParent(), Path.of(ROOT_MODULE));
      String module =
          folder.getFileName().toString().equals("internal")
              ? folder.getParent().getFileName().toString()
              : folder.getFileName().toString();
      modules.add(module);
      System.out.println(String.format(Locale.ROOT, "    %-8s %s", verdict, path));
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "  files touched: %d; modules touched: %d %s",
            touched,
            modules.size(),
            modules));
  }

  private static List<Path> javaFiles(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static String packageOf(String text, Path file) {
    Matcher declared = PACKAGE_LINE.matcher(text);
    if (!declared.find()) {
      throw new IllegalArgumentException("no package line in " + file);
    }
    return declared.group(1);
  }

  /** The first package segment under the base, for example "booking"; the base itself is root. */
  private static String moduleOf(String name, String basePackage) {
    if (name.equals(basePackage)) {
      return ROOT_MODULE;
    }
    if (!name.startsWith(basePackage + ".")) {
      return OUTSIDE;
    }
    String rest = name.substring(basePackage.length() + 1);
    int dot = rest.indexOf('.');
    String first = dot < 0 ? rest : rest.substring(0, dot);
    // A class directly in the base package, such as the CLI, is named by an upper-case segment.
    return Character.isUpperCase(first.charAt(0)) ? ROOT_MODULE : first;
  }

  /** Every group of modules that reach each other (Tarjan's strongly connected components). */
  private static List<List<String>> cycles(Map<String, Map<String, Integer>> graph) {
    Tarjan search = new Tarjan(graph);
    graph.keySet().forEach(search::visitIfNew);
    return search.cycles;
  }

  /** One run of Tarjan's algorithm; confined to one call. */
  private static final class Tarjan {

    private final Map<String, Map<String, Integer>> graph;
    private final Map<String, Integer> order = new TreeMap<>();
    private final Map<String, Integer> lowest = new TreeMap<>();
    private final List<String> stack = new ArrayList<>();
    private final List<List<String>> cycles = new ArrayList<>();

    Tarjan(Map<String, Map<String, Integer>> graph) {
      this.graph = graph;
    }

    void visitIfNew(String module) {
      if (!order.containsKey(module)) {
        visit(module);
      }
    }

    private void visit(String module) {
      order.put(module, order.size());
      lowest.put(module, order.get(module));
      stack.add(module);
      for (String target : graph.getOrDefault(module, Map.of()).keySet()) {
        if (!order.containsKey(target)) {
          visit(target);
          lowest.put(module, Math.min(lowest.get(module), lowest.get(target)));
        } else if (stack.contains(target)) {
          lowest.put(module, Math.min(lowest.get(module), order.get(target)));
        }
      }
      if (lowest.get(module).equals(order.get(module))) {
        List<String> component = new ArrayList<>();
        String member;
        do {
          member = stack.removeLast();
          component.add(member);
        } while (!member.equals(module));
        if (component.size() > 1) {
          cycles.add(component.reversed());
        }
      }
    }
  }
}
