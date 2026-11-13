-- SeatWave as a modular monolith (Module 32), on PostgreSQL 18.6: Module 13's one schema
-- `seatwave` becomes one schema per module, and every table has exactly one owner, the module
-- whose schema holds it. No foreign key crosses a schema: a key between two modules' tables would
-- let one module's change break the other's writes, which is the coupling this file removes.
-- Applied by seatwave_owner, which owns every schema and table; db/init/10-roles.sh created the
-- logins first (only a role with CREATEROLE may create roles or set their search_path).

CREATE SCHEMA catalog;
CREATE SCHEMA inventory;
CREATE SCHEMA booking;
CREATE SCHEMA payment;
CREATE SCHEMA notification;

-- catalog: what is on sale (Module 13's events, sections and seats; venues omitted here).
CREATE TABLE catalog.events (
  event_id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL
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
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  booking_id UUID,
  CONSTRAINT known_hold_state
    CHECK (state IN ('ACTIVE', 'CONFIRMING', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
  CONSTRAINT locked_holds_name_their_booking
    CHECK ((state IN ('CONFIRMING', 'CONFIRMED')) = (booking_id IS NOT NULL) OR state = 'RELEASED')
);
-- Module 13's one live claim per seat, counting CONFIRMING as Module 28 does: a partial unique
-- index ignores the rows of holds that have ended.
CREATE UNIQUE INDEX one_claim_per_seat ON inventory.holds (event_id, seat_label)
  WHERE state IN ('ACTIVE', 'CONFIRMING', 'CONFIRMED');

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
  show_starts_at TIMESTAMPTZ NOT NULL,
  state VARCHAR(20) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT one_saga_per_hold UNIQUE (hold_id),
  CONSTRAINT known_saga_state CHECK (state IN ('SEAT_HELD', 'PAYMENT_REQUESTED',
    'PAYMENT_SUCCEEDED', 'PAYMENT_FAILED', 'REFUND_REQUESTED', 'PAYMENT_REFUNDED', 'COMPLETED',
    'COMPENSATED'))
);
-- The stuck-saga query (Module 28, Section 10): not final, and older than the 300-second hold.
CREATE INDEX sagas_open_by_age ON booking.booking_sagas (updated_at)
  WHERE state NOT IN ('COMPLETED', 'COMPENSATED');
-- price_paid is Module 35's name for what the fan paid.
CREATE TABLE booking.bookings (
  booking_id UUID PRIMARY KEY,
  hold_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  price_paid NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  confirmed_at TIMESTAMPTZ NOT NULL,
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
  show_starts_at TIMESTAMPTZ NOT NULL,
  due_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(10) NOT NULL
);

-- One role per module, each granted its own schema and nothing else: the database refuses a
-- module's read of another module's table, on top of the boundary tests. No DELETE and no DDL, as
-- in Module 13.
GRANT USAGE ON SCHEMA catalog TO catalog_module;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA catalog TO catalog_module;
GRANT USAGE ON SCHEMA inventory TO inventory_module;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA inventory TO inventory_module;
GRANT USAGE ON SCHEMA booking TO booking_module;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA booking TO booking_module;
GRANT USAGE ON SCHEMA payment TO payment_module;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA payment TO payment_module;
GRANT USAGE ON SCHEMA notification TO notification_module;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA notification TO notification_module;

-- The deployable's one login, Module 13's seatwave_app. One deployable shares one transaction, so
-- it may take each module's role in turn (SET ROLE), but inherits none of their rights: logged in
-- and in no module's role, it reads nothing. The platform's Database takes the role of the module
-- whose code runs, so inside one shared transaction the database still tells the modules apart.
GRANT catalog_module, inventory_module, booking_module, payment_module, notification_module
  TO seatwave_app WITH INHERIT FALSE, SET TRUE;

-- The lab's catalog: Module 13's Arena Night (event 1), section A at 80.00 USD, three seats.
INSERT INTO catalog.events (event_id, name, starts_at)
  VALUES (1, 'Arena Night', TIMESTAMPTZ '2026-12-08 20:20:51.783+00');
INSERT INTO catalog.sections (event_id, section_code, price, currency) VALUES (1, 'A', 80.00, 'USD');
INSERT INTO catalog.seats (event_id, seat_label, section_code, seat_number)
  VALUES (1, 'A-7', 'A', 7), (1, 'A-8', 'A', 8), (1, 'A-9', 'A', 9);
