-- Module 32, the extraction step: the notification module leaves the deployable and becomes
-- Module 27's notifications-email service. Three things appear: booking's outbox (Module 28), the
-- service's processed_messages (Module 27), and the service's own login in notification's role.
-- One thing disappears: the monolith's right to take notification's role. In production the
-- notification schema then moves to the service's own database; the lab keeps one PostgreSQL so
-- that one run can show the grants. Applied by seatwave_owner after V1.

CREATE TABLE booking.outbox (
  message_id UUID PRIMARY KEY,
  event_type VARCHAR(40) NOT NULL,
  aggregate_key VARCHAR(64) NOT NULL,
  payload VARCHAR(1000) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ
);
GRANT SELECT, INSERT, UPDATE ON booking.outbox TO booking_module;

-- Module 27's claim table, with the columns this service needs; one effect per booking.
CREATE TABLE notification.processed_messages (
  consumer_group VARCHAR(64) NOT NULL,
  dedupe_key VARCHAR(100) NOT NULL,
  message_id UUID NOT NULL,
  done_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT one_effect_per_key PRIMARY KEY (consumer_group, dedupe_key)
);
GRANT SELECT, INSERT, UPDATE ON notification.processed_messages TO notification_module;

-- The service's login holds notification's role and nothing else; the monolith loses it.
GRANT notification_module TO notification_service WITH INHERIT TRUE, SET FALSE;
REVOKE notification_module FROM seatwave_app;
