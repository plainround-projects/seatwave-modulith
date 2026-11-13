# seatwave-modulith

The project of Module 32 of the course *System Design Fundamentals* ("Architecture styles and domains": a modular monolith with five modules over a shared kernel, boundary tests, a coupling comparison, and the extraction of one module). Running case: SeatWave, a ticketing system.

## What it shows

- **Five modules in one deployable**: `catalog`, `inventory`, `booking`, `payment` and `notification`, each a top-level package with a public interface and an `internal` package, over the shared kernel `platform` (`Database`, `DomainEvents`, `Migrations`). Expected failures are result4j `Result` values with sealed error types.
- **One schema per module, one owner per table**, on PostgreSQL 18.6: Module 13's partial unique index `one_claim_per_seat`, savepoints around the inserts a unique key may refuse, one login per module granted its own schema only, and an empty `search_path` for every login. The deployable's login `seatwave_app` holds no rights of its own and takes the role of the module whose code runs (`SET ROLE`), so the database refuses a cross-module read even inside one shared transaction.
- **The boundary enforced twice**: by `ModuleBoundaryTest` on every build (ArchUnit on bytecode, a table scan of the sources, one owner per table against `docs/context-map.txt`) and by the grants on the stack (`GrantsStackTest`: every module's login is refused every other module's tables, pair by pair).
- **Module 28's booking saga inside one process**: its eight states, three local transactions for a confirmed booking, the compensations, and the stuck-saga sweeper.
- **Coupling counted**: `CouplingReport` (JDK only) on the modular code and on a layered fixture of the same confirm, before and after two feature changes.
- **The extraction of notification**: booking's outbox, a relay to Module 27's Kafka topic `seatwave.bookings`, the notification service with its own login and its claim in `processed_messages`.
- **Section 10's rollout**: `holds` moved from Module 13's schema to `inventory`'s with `ALTER TABLE ... SET SCHEMA`, a view for old readers, and the view dropped.

## Prerequisites

- JDK 25 (the build uses a toolchain; Eclipse Temurin 25.0.2 on the verification machine, the newest is 25.0.5).
- Docker for the stack (Docker 24.0.6 and Compose v2.23.0 on the verification machine). `./gradlew check` needs no Docker.
- `bash` and `openssl` for `scripts/stack.sh`.

## Dependencies

Every version is in `gradle/libs.versions.toml`, checked on Maven Central on 13 November 2026, and every resolved jar is pinned in `gradle.lockfile`.

| Coordinates | License | Why the JDK alone is not enough |
|---|---|---|
| `com.github.sviperll:result4j:1.2.0` | Apache-2.0 | a `Result` type for expected failures (code guidelines, rule 47) |
| `org.postgresql:postgresql:42.7.14` | BSD-2-Clause | PostgreSQL's wire protocol |
| `com.h2database:h2:2.5.252` | MPL-2.0 or EPL-1.0 | the in-memory stand-in of `./gradlew check` and `modulithRun` |
| `org.apache.kafka:kafka-clients:4.4.0` (the extracted tree only) | Apache-2.0 | Kafka's protocol (producer, consumer, admin) |
| `at.yawk.lz4:lz4-java:1.14.0`, `org.xerial.snappy:snappy-java:1.1.10.12` (constraints) | Apache-2.0 | kafka-clients 4.4.0 asks for 1.11.2 and 1.1.10.7, which OSV lists with advisories |
| `org.slf4j:slf4j-api:2.0.20`, `ch.qos.logback:logback-classic:1.6.6` | MIT, EPL-1.0/LGPL-2.1 | the Kafka client's logs, to standard error |
| `com.tngtech.archunit:archunit:1.5.1` | Apache-2.0 | dependency rules over bytecode, as tests |
| JUnit BOM 6.1.3, AssertJ 3.27.7 | EPL-2.0, Apache-2.0 | tests |
| Spotless 8.11.0 with google-java-format 2.0.0, JaCoCo 0.8.16 | Apache-2.0, Apache-2.0, EPL-2.0 | format and coverage gates |

Images (Docker Hub, checked 13 November 2026, each with an arm64 variant): `postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42` and `apache/kafka:4.4.0@sha256:833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f`.

OSV, every coordinate of `gradle.lockfile`, with a control that OSV must list, 13 November 2026:

```text
coordinates in gradle.lockfile: 33
control org.apache.logging.log4j:log4j-core:2.14.1: 8 advisories listed (found, as it must be)
project coordinates with advisories: 0
```

## Commands and what they printed

Build and test, without Docker (Temurin 25.0.2):

```text
> Task :spotlessJavaCheck
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport
BUILD SUCCESSFUL in 4s
16 actionable tasks: 16 executed
test: 39 tests in 6 classes, 0 failed
extractedBoundaryTest: 4 tests in 1 classes, 0 failed
ModuleBoundaryTest: 4 tests in 742 ms (JUnit's report, load average 1.00 at the start, under the measurement lock)
line coverage checked by ./gradlew check: 523 of 535 lines, 0.978 (floor 0.80; the programs excluded)
```

```text
$ ./gradlew spotlessCheck --rerun-tasks
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck
BUILD SUCCESSFUL in 510ms
```

The unit test task, 40 times:

```text
./gradlew test --rerun-tasks, 40 runs one after another (39 tests each): 0 runs failed
```

The deployable on H2 (`./gradlew modulithRun`), twice with identical output; the same lines are the golden file `src/test/resources/golden/ModulithCli.txt`:

```text
$ ./gradlew -q modulithRun
SeatWave as a modular monolith: 5 modules, 1 deployable, 1 database
Part 1. A fan confirms: lock, charge, sell
  fan 1001 holds A-7: held for 300 s
  fan 1001 confirms: confirmed A-7 (local transactions committed: 3)
Part 2. The card is declined: the seat goes back on sale
  fan 1002 holds A-8: held for 300 s
  fan 1002 confirms: failed: card declined by the provider (local transactions committed: 3)
Part 3. The hold expired before the confirm
  fan 1003 holds A-9: held for 300 s
  fan 1003 confirms: refused: hold is expired (local transactions committed: 1)
  fan 1004 holds A-9: held for 300 s
Part 4. One seat, one claim
  fan 1005 holds A-7: refused: seat A-7 is already claimed
Part 5. A listener fails inside the confirm's transaction
  fan 1004 confirms: error, rolled back: listener failed (simulated by the lab) (local transactions committed: 2)
  left behind             PAYMENT_REQUESTED A-9 CONFIRMING CAPTURED
Part 6. The stuck-saga sweeper finishes it (Module 28)
  sweeper finds sagas unmoved for over 300 s: [confirmed A-9] (local transactions committed: 3)
Tables after the run, each read in its owner's schema:
  inventory.holds         A-7 CONFIRMED | A-8 RELEASED | A-9 EXPIRED | A-9 CONFIRMED
  booking.bookings        A-7 80.00 USD | A-9 80.00 USD
  booking.booking_sagas   COMPENSATED | COMPLETED | COMPLETED
  payment.payments        CAPTURED 80.00 | CAPTURED 80.00 | DECLINED 80.00
  notification.emails     Seat A-7 for event 1 is yours. The show starts at 2026-12-08T20:20:51.783Z. | Seat A-9 for event 1 is yours. The show starts at 2026-12-08T20:20:51.783Z.
  notification.reminders  2026-12-07T20:20:51.783Z | 2026-12-07T20:20:51.783Z
modulithRun: second run identical
```

The coupling counter (`./gradlew couplingRun`):

```text
$ ./gradlew -q couplingRun   (CouplingReport deps src/main/java io.plainround.seatwave.modulith)
  18 files in 7 modules; 42 imports across modules
    (root)         -> booking        4
    (root)         -> catalog        1
    (root)         -> inventory      3
    (root)         -> notification   1
    (root)         -> payment        1
    (root)         -> platform       4
    booking        -> catalog        3
    booking        -> inventory      3
    booking        -> payment        4
    booking        -> platform       5
    catalog        -> platform       2
    inventory      -> catalog        2
    inventory      -> platform       2
    notification   -> booking        2
    notification   -> platform       3
    payment        -> platform       2
  cycles between modules: none
  imports of another module's internals: none
  tables named by more than one file: none (of 9 tables named)
```

The stack (`scripts/stack.sh up --run --yes`; `scripts/stack.sh` alone is a dry run), then the same program on PostgreSQL:

```text
identical: the H2 run and the PostgreSQL run printed the same 25 lines
```

The stack tests (`scripts/stack.sh test --run --yes` runs up, both tasks and down):

```text
$ ./gradlew stackTest extractedStackTest
stackTest ModulithOnPostgresStackTest: 1 tests, 0 failed
stackTest GrantsStackTest: 25 tests, 0 failed
stackTest PostgresRulesStackTest: 4 tests, 0 failed
stackTest RolloutStackTest: 1 tests, 0 failed
extractedStackTest ExtractionStackTest: 4 tests, 0 failed
stack tests: 35, failed: 0
BUILD SUCCESSFUL in 5s
```

The extraction on PostgreSQL and Kafka (`./gradlew extractionRun`):

```text
$ ./gradlew -q extractionRun   (four modules on PostgreSQL, the notification service beside them, Kafka between)
SeatWave after extracting notification: 4 modules in the deployable, 1 service beside it
Part 1. The sale writes its event to the outbox, in the sale's own transaction
  fan 1001 confirms A-7: confirmed (local transactions committed: 3)
  booking.outbox unsent rows: 1
  relay pass: published and marked 1
  notification service handled the broker's messages: [HANDLED]
Part 2. The relay loses the broker's acknowledgement once
  fan 1002 confirms A-8: confirmed (local transactions committed: 3)
  relay pass: stopped, connection lost before the broker's acknowledgement; the row stays unsent
  relay pass: published and marked 1
  notification service handled the broker's messages: [HANDLED, DUPLICATE]
Part 3. The notification service is down while a fan buys
  fan 1003 confirms A-9: confirmed (local transactions committed: 3)
  relay pass: published and marked 1
  messages waiting on the broker: 1; the sale did not wait for them
  notification service handled the broker's messages: [HANDLED]
Part 4. The database keeps each side in its own tables
  notification_service reads booking.bookings: refused, ERROR: permission denied for schema booking (SQLSTATE 42501)
  notification_service reads notification.emails: allowed, 3 rows
  seatwave_app reads notification.emails: refused, ERROR: permission denied for schema notification (SQLSTATE 42501)
  seatwave_app as notification_module reads notification.emails: refused, ERROR: permission denied to set role "notification_module" (SQLSTATE 42501)
Tables after the run:
  booking.outbox rows: 3, unsent: 0
  notification.processed_messages rows: 3
  notification.emails rows: 3
```

The mutants: each breaks one rule on a copy of the repository and is caught by the tests named (`_tools/mutants-module-32.py` in the course folder):

```text
internal: booking casts inventory to its internal HoldStore; ./gradlew test failed 1: ModuleBoundaryTest.noModuleUsesAnotherModulesInternals()
table: booking's code names inventory's table; ./gradlew test failed 1: ModuleBoundaryTest.noModuleNamesAnotherModulesTables()
cycle: catalog takes booking's event as a parameter; ./gradlew test failed 1: ModuleBoundaryTest.modulesDependOnEachOtherWithoutCycles()
sweeper: the stuck-saga sweeper skips REFUND_REQUESTED; ./gradlew test failed 1: ConfirmBookingTest.[5] REFUND_REQUESTED
grant: V1 grants booking's role a read of inventory's schema; ./gradlew stackTest failed 2: GrantsStackTest.deployableLoginInAModulesRoleReadsOnlyThatModulesTables(), GrantsStackTest."booking"_module is refused every table of "inventory"
search-path: inventory_module's search_path is 'inventory', not empty (ALTER ROLE on the running stack, the init script's line changed); ./gradlew stackTest failed 2: GrantsStackTest.unqualifiedTableNameIsAnErrorEvenForItsOwner(), GrantsStackTest.everyLoginHasAnEmptySearchPath()
savepoint: the refused insert runs without a savepoint; ./gradlew stackTest failed 1: PostgresRulesStackTest.refusedClaimInsideALargerTransactionLeavesTheTransactionUsable()
full-index: one_claim_per_seat is a full unique index, not a partial one; ./gradlew stackTest failed 3: ModulithOnPostgresStackTest.labOnPostgresPrintsWhatTheH2RunPrints(), PostgresRulesStackTest.oneClaimPerSeatIsAPartialUniqueIndexOnPostgres(), PostgresRulesStackTest.partialIndexIgnoresAHoldThatHasEnded()
no-role: the deployable never takes a module's role on PostgreSQL; ./gradlew stackTest failed 4: ModulithOnPostgresStackTest.labOnPostgresPrintsWhatTheH2RunPrints(), PostgresRulesStackTest.refusedClaimInsideALargerTransactionLeavesTheTransactionUsable(), PostgresRulesStackTest.secondLiveClaimIsRefusedAsAnAnswerNotAnError(), PostgresRulesStackTest.partialIndexIgnoresAHoldThatHasEnded()
no-claim: the extracted service queues the email without its claim; ./gradlew extractedStackTest failed 1: ExtractionStackTest.duplicateDeliveryAfterALostAcknowledgementQueuesOneEmail()
```

The stack down, and nothing left:

```text
$ scripts/stack.sh down --run --yes
plan: down the Compose project seatwave-modulith
  docker compose -p seatwave-modulith down -v --remove-orphans; remove .secrets; check nothing of seatwave-modulith is left
nothing of seatwave-modulith is left
$ docker ps -a, docker network ls, docker volume ls, each filtered by label com.docker.compose.project=seatwave-modulith
containers: 0, networks: 0, volumes: 0
```

## Ports

PostgreSQL on `127.0.0.1:23210`, Kafka on `127.0.0.1:23290`; nothing else is published.

## Project structure

```text
src/main/java/io/plainround/seatwave/modulith/   platform, catalog, inventory, booking, payment, notification, ModulithCli, StackSettings
src/main/resources/db/postgresql/                V1 (the production schema and grants) and reset.sql (lab only)
src/main/resources/db/h2/                        V1's H2 stand-in
src/test/java/                                   ModuleBoundaryTest and the unit tests; stack/ and *StackTest: the tests tagged "stack"
tools/src/                                       CouplingReport, the JDK-only coupling counter (source set "tools")
fixtures/layered-seatwave.javas                  the same confirm by technical layer, for the comparison
extraction/after/                                the files that change when notification is extracted (source set "extracted",
                                                 assembled into build/extracted), and their stack test
db/init/10-roles.sh                              the logins and their empty search_path (run once by the postgres image)
db/rollout/                                      Section 10's table move, R0 to R2
docs/                                            the event-storming board and the context map
compose.yaml, scripts/stack.sh, config/stack.properties
```

## What is simplified

- The payment provider is an in-process stand-in that answers at once; Module 7 and Module 28 have the real call with its timeout and its unknown outcome.
- The database handle opens one connection per transaction with `DriverManager`; a service would use a bounded pool (Module 10).
- Migrations are applied by a small splitter (`platform/Migrations`) at the start of each program and test; production applies them once per version with a migration tool.
- The extracted service shares one PostgreSQL with the monolith so one run can show both logins' grants; in production its schema moves to its own database.
- One Kafka broker, not three: replication is Module 27's subject.

## No CI on purpose

The course's projects have no CI workflow: every gate (`./gradlew check`, the stack tests, the mutants, the repeated runs) was run on the verification machine, and the commands are above.

## The Compose path

`compose.yaml` and `scripts/stack.sh` were run for every stack output above; the stack is brought down with `down -v` after each run.

## License

MIT, see `LICENSE`.
