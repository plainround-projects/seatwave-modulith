-- Returns the lab database to the moment before V1, so a program or a test starts from nothing:
-- the five schemas and everything in them, and the one role membership that V2 grants. Applied by
-- seatwave_owner, which owns the schemas and granted the membership. Lab only: production never
-- drops a schema.
DROP SCHEMA IF EXISTS catalog, inventory, booking, payment, notification CASCADE;
REVOKE notification_module FROM notification_service;
