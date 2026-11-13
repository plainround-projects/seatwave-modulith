-- Section 10's rollout, before: Module 13's one schema as the tangle left it. holds lives in the
-- schema seatwave, and the deployable's login reads and writes it directly. Applied by
-- seatwave_owner in the database seatwave_rollout, which db/init/10-roles.sh created for this run.
CREATE SCHEMA seatwave;
CREATE SCHEMA inventory;
CREATE TABLE seatwave.holds (
  hold_id UUID PRIMARY KEY,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  state VARCHAR(10) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ
);
GRANT USAGE ON SCHEMA seatwave TO seatwave_app;
GRANT SELECT, INSERT, UPDATE ON seatwave.holds TO seatwave_app;
GRANT USAGE ON SCHEMA inventory TO inventory_module;
INSERT INTO seatwave.holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at)
  VALUES ('00000000-0000-4000-8000-000000000007', 1, 'A-7', 1001, 'ACTIVE',
          TIMESTAMPTZ '2026-11-20 19:00:00+00', TIMESTAMPTZ '2026-11-20 19:05:00+00'),
         ('00000000-0000-4000-8000-000000000008', 1, 'A-8', 1002, 'ACTIVE',
          TIMESTAMPTZ '2026-11-20 19:00:00+00', TIMESTAMPTZ '2026-11-20 19:05:00+00');
