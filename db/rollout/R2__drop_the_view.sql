-- Section 10's rollout, step 3 (contract): when no code has read the view for two weeks, drop it
-- and the old login's last right in the old schema. Until this file runs, the move is reversible.
DROP VIEW seatwave.holds;
REVOKE USAGE ON SCHEMA seatwave FROM seatwave_app;
