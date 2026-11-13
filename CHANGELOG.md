# Changelog

## 1.0.0 (13 November 2026)

Built from the code seed of Module 32 (`_tools/module-32-code/`, 27 files):

- Gradle build: wrapper 9.9.1 pinned by checksum, version catalog, lockfiles, `-Xlint:all -Werror`, Spotless with google-java-format 2.0.0, JaCoCo floor 0.80; source sets `tools`, `extracted` and `extractedTest`; tasks `modulithRun`, `couplingRun`, `modulithStackRun`, `extractedModulithRun`, `extractionRun`, `stackTest`, `extractedStackTest`, `extractedBoundaryTest`.
- The sealed outcome types became result4j `Result` values with sealed error types (`Inventory.Refusal`, `Bookings.ConfirmFailure`, `Payments.Declined`).
- PostgreSQL 18.6 schema with the partial unique index `one_claim_per_seat`, one login per module, the deployable's login taking each module's role, an empty `search_path` for every login; savepoints around refused inserts; the H2 file kept as the stand-in of `check`.
- Module 28's eight saga states, the refund path and the stuck-saga sweeper in `booking`; `bookings.price` renamed `price_paid` (Module 35).
- `notification.emails` keyed by `email_id`, so after the extraction the claim alone keeps one email per booking (Module 27).
- The extraction's in-memory broker replaced by Module 27's Kafka topic `seatwave.bookings`, with a consumer that commits after handling.
- Section 10's rollout (`db/rollout`), the stack (`compose.yaml`, `scripts/stack.sh`, `db/init/10-roles.sh`), the stack tests and ten mutants.
