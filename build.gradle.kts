// seatwave-modulith (Module 32). Versions are in gradle/libs.versions.toml, checked on Maven Central
// on 13 November 2026.
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Step 10's tree: the deployable after notification left it. It is assembled, never edited: the
// main sources without the notification module, with extraction/after laid over them, as a
// developer's branch would look. Its own tests (extraction/after/src/test) need the stack.
val extractedTree = layout.buildDirectory.dir("extracted")
val assembleExtractedTree = tasks.register<Sync>("assembleExtractedTree") {
    group = "seatwave"
    description = "Writes build/extracted: the main tree without notification, extraction/after over it."
    into(extractedTree)
    from("src/main") {
        into("src/main")
        exclude("java/io/plainround/seatwave/modulith/notification/**")
        // The overlay replaces these two; one path from two sources would be ambiguous.
        exclude("java/io/plainround/seatwave/modulith/ModulithCli.java")
        exclude("java/io/plainround/seatwave/modulith/booking/Bookings.java")
    }
    from("extraction/after/src/main") {
        into("src/main")
    }
    // The boundary test reads the context map beside the sources, as it does in the project.
    from("docs") {
        into("docs")
    }
}

sourceSets {
    // Section 10's rollout files, for the stack test that runs them (as /rollout/R0__... on the
    // class path).
    test {
        resources.srcDir("db")
        resources.exclude("init/**")
    }
    // The coupling counter is a tool for the build, not part of the deployable.
    create("tools") {
        java.srcDir("tools/src")
    }
    create("extracted") {
        java.setSrcDirs(listOf(extractedTree.map { it.dir("src/main/java") }))
        resources.setSrcDirs(listOf(extractedTree.map { it.dir("src/main/resources") }))
    }
    create("extractedTest") {
        java.srcDir("extraction/after/src/test/java")
        compileClasspath += sourceSets["extracted"].output
        runtimeClasspath += sourceSets["extracted"].output
    }
}
tasks.named("compileExtractedJava") { dependsOn(assembleExtractedTree) }
tasks.named("processExtractedResources") { dependsOn(assembleExtractedTree) }

configurations.named("extractedTestImplementation") {
    extendsFrom(configurations.getByName("extractedImplementation"))
}
configurations.named("extractedTestRuntimeOnly") {
    extendsFrom(configurations.getByName("extractedRuntimeOnly"))
}

dependencies {
    // Expected failures are values (code guidelines, rule 47); the JDK has no Result type.
    implementation(libs.result4j)
    // The JDK speaks JDBC but ships no database and no driver: PostgreSQL 18.6 is the stack's
    // database, H2 the in-memory stand-in of ./gradlew check and modulithRun.
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // Rules over bytecode: the JDK cannot list which class depends on which.
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)

    // After the extraction, booking's relay and the notification service speak Kafka's protocol,
    // which the JDK lacks; the monolith before it never does.
    "extractedImplementation"(libs.result4j)
    "extractedImplementation"(libs.kafka.clients)
    constraints {
        // kafka-clients 4.4.0 asks for versions OSV lists with advisories (lz4-java 1.11.2,
        // snappy-java 1.1.10.7; Module 27), rechecked 13 November 2026. Both compressors are used
        // only when a producer compresses, which this one does not.
        "extractedImplementation"(libs.lz4.java) { because("lz4-java 1.11.2 has advisories; fixed in 1.11.4") }
        "extractedImplementation"(libs.snappy.java) { because("snappy-java 1.1.10.7 has advisories; fixed in 1.1.10.9") }
    }
    "extractedImplementation"(libs.slf4j.api)
    "extractedRuntimeOnly"(libs.postgresql)
    "extractedRuntimeOnly"(libs.h2)
    "extractedRuntimeOnly"(libs.logback.classic)
    "extractedTestImplementation"(platform(libs.junit.bom))
    "extractedTestImplementation"(libs.junit.jupiter)
    "extractedTestImplementation"(libs.assertj.core)
    "extractedTestRuntimeOnly"(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        target("src/**/*.java", "tools/**/*.java", "extraction/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// The tests tagged "stack" need PostgreSQL 18.6 of compose.yaml; ./gradlew check never runs them,
// so a reader without Docker can still build and test the rest. Roles, grants, an empty
// search_path, a partial unique index and savepoints are the subject, and H2 has them differently
// or not at all.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    // The boundary tests read the sources and the context map from the project folder.
    systemProperty("seatwave.project", projectDir.absolutePath)
    testLogging {
        events("failed")
    }
    finalizedBy(tasks.jacocoTestReport)
}

val stackSettings = file("config/stack.properties").absolutePath

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against PostgreSQL; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    systemProperty("seatwave.project", projectDir.absolutePath)
    systemProperty("seatwave.stackSettings", stackSettings)
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The same boundary test on the tree after the extraction: notification has left it, and the four
// modules that stay must still keep every rule. It needs no Docker, so check runs it.
val extractedBoundaryTest = tasks.register<Test>("extractedBoundaryTest") {
    group = "verification"
    description = "Runs ModuleBoundaryTest on the tree after notification was extracted."
    dependsOn(assembleExtractedTree)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    val mainClasses = sourceSets.main.get().output
    classpath = sourceSets.test.get().output + sourceSets["extracted"].output +
        configurations["testRuntimeClasspath"].filter { file -> !mainClasses.contains(file) }
    useJUnitPlatform {
        excludeTags("stack")
    }
    filter {
        includeTestsMatching("io.plainround.seatwave.modulith.ModuleBoundaryTest")
    }
    systemProperty("seatwave.project", extractedTree.get().asFile.absolutePath)
    testLogging {
        events("failed")
    }
}

tasks.register<Test>("extractedStackTest") {
    group = "verification"
    description = "Runs the extracted notification service against PostgreSQL and Kafka."
    testClassesDirs = sourceSets["extractedTest"].output.classesDirs
    classpath = sourceSets["extractedTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("seatwave.stackSettings", stackSettings)
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The programs are measured by the golden-output test and by hand; the floor is over the rest.
val programs = listOf("**/*Cli*")

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(
        tasks.jacocoTestCoverageVerification,
        extractedBoundaryTest,
        tasks.named("compileExtractedTestJava"),
        tasks.named("compileToolsJava"),
    )
}

// One task per program, so a reader runs one command and sees one result: ./gradlew modulithRun
// couplingRun. The stack's programs read the settings file and need scripts/stack.sh up first.
tasks.register<JavaExec>("modulithRun") {
    group = "seatwave"
    description = "Runs the five modules in one deployable on H2 in memory."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.modulith.ModulithCli"
}
tasks.register<JavaExec>("modulithStackRun") {
    group = "seatwave"
    description = "Runs the same program on the stack's PostgreSQL, one role per module."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.modulith.ModulithCli"
    args(stackSettings)
}
tasks.register<JavaExec>("couplingRun") {
    group = "seatwave"
    description = "Counts the dependencies between the modules of src/main/java."
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass = "io.plainround.seatwave.modulith.tools.CouplingReport"
    args("deps", "src/main/java", "io.plainround.seatwave.modulith")
}
tasks.register<JavaExec>("extractedModulithRun") {
    group = "seatwave"
    description = "Runs the deployable after the extraction, on H2 in memory."
    classpath = sourceSets["extracted"].runtimeClasspath
    mainClass = "io.plainround.seatwave.modulith.ModulithCli"
}
tasks.register<JavaExec>("extractionRun") {
    group = "seatwave"
    description = "Runs the four modules and the extracted notification service on PostgreSQL and Kafka."
    classpath = sourceSets["extracted"].runtimeClasspath
    mainClass = "io.plainround.seatwave.modulith.ExtractionCli"
    args(stackSettings)
}
