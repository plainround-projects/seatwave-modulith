-- Section 10's rollout, step 1 (expand): move the table to its owner's schema in one statement;
-- the old name becomes a view, readable and not writable, for the code that still uses it. A
-- view runs with its owner's rights on the table, so old readers need no right on inventory.
ALTER TABLE seatwave.holds SET SCHEMA inventory;
REVOKE ALL ON inventory.holds FROM seatwave_app;
GRANT SELECT, INSERT, UPDATE ON inventory.holds TO inventory_module;
CREATE VIEW seatwave.holds AS
  SELECT hold_id, event_id, seat_label, customer_id, state, created_at, expires_at, ended_at
  FROM inventory.holds;
GRANT SELECT ON seatwave.holds TO seatwave_app;
