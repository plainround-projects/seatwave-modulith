-- The H2 2.5.252 stand-in for db/postgresql/V1__module_schemas.sql, used by ./gradlew check and
-- modulithRun so that neither needs Docker. Same schemas, tables, names and rows; the differences
-- are where H2 cannot do what PostgreSQL does: no partial index (a generated claim key instead),
-- no SET ROLE (one login with every schema), and grants on a schema in one statement. The
-- ownership test reads both files, so they cannot drift apart.

CREATE SCHEMA catalog;
CREATE SCHEMA inventory;
CREATE SCHEMA booking;
CREATE SCHEMA payment;
CREATE SCHEMA notification;

-- catalog: what is on sale (Module 13's events, sections and seats; venues omitted here).
CREATE TABLE catalog.events (
  event_id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  starts_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE catalog.sections (
  event_id BIGINT NOT NULL,
  section_code VARCHAR(4) NOT NULL,
  price NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  CONSTRAINT sections_pkey PRIMARY KEY (event_id, section_code),
  CONSTRAINT section_of_a_real_event FOREIGN KEY (event_id) REFERENCES catalog.events (event_id)
);
CREATE TABLE catalog.seats (
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  section_code VARCHAR(4) NOT NULL,
  seat_number INT NOT NULL,
  CONSTRAINT seats_pkey PRIMARY KEY (event_id, seat_label),
  CONSTRAINT seat_in_a_real_section FOREIGN KEY (event_id, section_code)
    REFERENCES catalog.sections (event_id, section_code)
);

-- inventory: who claims which seat. Module 13's foreign key hold_names_a_real_seat is gone: the
-- seat is named by (event_id, seat_label), a value copied from the catalog, not a reference to it.
CREATE TABLE inventory.holds (
  hold_id UUID PRIMARY KEY,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  state VARCHAR(10) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  booking_id UUID,
  -- H2 has no partial index, so the claim is a generated key that is NULL once the hold has ended,
  -- and a unique constraint ignores NULLs.
  claim_key VARCHAR(40) GENERATED ALWAYS AS (
    CASE WHEN state IN ('ACTIVE', 'CONFIRMING', 'CONFIRMED')
      THEN CAST(event_id AS VARCHAR(20)) || '/' || seat_label END),
  CONSTRAINT one_claim_per_seat UNIQUE (claim_key),
  CONSTRAINT known_hold_state
    CHECK (state IN ('ACTIVE', 'CONFIRMING', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
  CONSTRAINT locked_holds_name_their_booking
    CHECK ((state IN ('CONFIRMING', 'CONFIRMED')) = (booking_id IS NOT NULL) OR state = 'RELEASED')
);

-- booking: the purchase and its saga row with Module 28's eight states. Module 13's
-- booking_matches_its_hold, a key into inventory's table, is gone; the confirm writes both rows in
-- one local transaction instead. The saga row keeps what the sweeper needs to finish it alone.
CREATE TABLE booking.booking_sagas (
  booking_id UUID PRIMARY KEY,
  hold_id UUID NOT NULL,
  customer_id BIGINT NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  price NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  show_starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
  state VARCHAR(20) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT one_saga_per_hold UNIQUE (hold_id),
  CONSTRAINT known_saga_state CHECK (state IN ('SEAT_HELD', 'PAYMENT_REQUESTED',
    'PAYMENT_SUCCEEDED', 'PAYMENT_FAILED', 'REFUND_REQUESTED', 'PAYMENT_REFUNDED', 'COMPLETED',
    'COMPENSATED'))
);
-- The stuck-saga query (Module 28, Section 10); PostgreSQL's index covers only open sagas.
CREATE INDEX sagas_open_by_age ON booking.booking_sagas (updated_at);
-- price_paid is Module 35's name for what the fan paid.
CREATE TABLE booking.bookings (
  booking_id UUID PRIMARY KEY,
  hold_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  price_paid NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT one_booking_per_hold UNIQUE (hold_id),
  CONSTRAINT one_booking_per_seat UNIQUE (event_id, seat_label)
);

-- payment: one row per provider key (Module 28's payments, without its outbox here).
CREATE TABLE payment.payments (
  provider_key VARCHAR(64) PRIMARY KEY,
  booking_id UUID NOT NULL,
  state VARCHAR(10) NOT NULL,
  amount NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  CONSTRAINT known_payment_state CHECK (state IN ('CAPTURED', 'DECLINED', 'REFUNDED'))
);

-- notification: what the fan is owed (Module 27's reminders, and the emails a sender delivers).
-- An email is not keyed by its booking: after the extraction, the claim in processed_messages is
-- what allows one email per booking, as in Module 27.
CREATE TABLE notification.emails (
  email_id UUID PRIMARY KEY,
  booking_id UUID NOT NULL,
  customer_id BIGINT NOT NULL,
  body VARCHAR(500) NOT NULL,
  status VARCHAR(10) NOT NULL
);
CREATE TABLE notification.reminders (
  booking_id UUID PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  show_starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
  due_at TIMESTAMP WITH TIME ZONE NOT NULL,
  status VARCHAR(10) NOT NULL
);

-- H2 cannot switch roles inside a transaction, so its one login, Module 13's seatwave_app, has
-- every module's schema: here only the boundary tests tell the modules apart.
CREATE USER seatwave_app PASSWORD '';
GRANT SELECT, INSERT, UPDATE ON SCHEMA catalog TO seatwave_app;
GRANT SELECT, INSERT, UPDATE ON SCHEMA inventory TO seatwave_app;
GRANT SELECT, INSERT, UPDATE ON SCHEMA booking TO seatwave_app;
GRANT SELECT, INSERT, UPDATE ON SCHEMA payment TO seatwave_app;
GRANT SELECT, INSERT, UPDATE ON SCHEMA notification TO seatwave_app;

-- The lab's catalog: Module 13's Arena Night (event 1), section A at 80.00 USD, three seats.
INSERT INTO catalog.events (event_id, name, starts_at)
  VALUES (1, 'Arena Night', TIMESTAMP WITH TIME ZONE '2026-12-08 20:20:51.783+00');
INSERT INTO catalog.sections (event_id, section_code, price, currency) VALUES (1, 'A', 80.00, 'USD');
INSERT INTO catalog.seats (event_id, seat_label, section_code, seat_number)
  VALUES (1, 'A-7', 'A', 7), (1, 'A-8', 'A', 8), (1, 'A-9', 'A', 9);
