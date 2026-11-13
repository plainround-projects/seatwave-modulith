-- Module 32, the extraction step: the notification module leaves the deployable and becomes
-- Module 27's notifications-email service. Three things appear: booking's outbox (Module 28), the
-- service's processed_messages (Module 27), and the service's own login in notification's role.
-- One thing disappears: the monolith's right to take notification's role. In production the
-- notification schema then moves to the service's own database; the lab keeps one PostgreSQL so
-- that one run can show the grants. Applied by seatwave_owner after V1. This is the H2 stand-in for
-- extractedModulithRun; H2 grants on schemas, so the monolith's login loses the whole schema.

CREATE TABLE booking.outbox (
  message_id UUID PRIMARY KEY,
  event_type VARCHAR(40) NOT NULL,
  aggregate_key VARCHAR(64) NOT NULL,
  payload VARCHAR(1000) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE
);

-- Module 27's claim table, with the columns this service needs; one effect per booking.
CREATE TABLE notification.processed_messages (
  consumer_group VARCHAR(64) NOT NULL,
  dedupe_key VARCHAR(100) NOT NULL,
  message_id UUID NOT NULL,
  done_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT one_effect_per_key PRIMARY KEY (consumer_group, dedupe_key)
);

-- The service's login holds notification's role and nothing else; the monolith loses it.
CREATE USER notification_service PASSWORD '';
GRANT SELECT, INSERT, UPDATE ON SCHEMA notification TO notification_service;
REVOKE SELECT, INSERT, UPDATE ON SCHEMA notification FROM seatwave_app;
