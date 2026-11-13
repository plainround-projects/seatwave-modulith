#!/usr/bin/env bash
# Runs once inside the postgres container, as the superuser, when the data volume is new. Creates
# every login Module 32 names, so nothing in SeatWave ever logs in as the superuser:
#   seatwave_owner        owns the schemas and tables and applies the migrations; may grant the
#                         five module roles to others (ADMIN), but holds none of their rights
#   catalog_module ...    one login per module (catalog, inventory, booking, payment,
#   notification_module   notification); V1 grants each its own schema and nothing else
#   seatwave_app          the deployable's login; V1 lets it take each module's role (SET ROLE)
#                         without inheriting any of their rights, so alone it reads nothing
#   notification_service  the extracted service's login; V2 gives it notification's role
# Every login is NOSUPERUSER and NOBYPASSRLS and owns nothing but what the owner creates, and every
# login's search_path is empty: an unqualified table name is an error, so every query names the
# schema, which is to say the owner. Only a role with CREATEROLE may create roles or set their
# search_path, which is why this is not a migration. Passwords come from the Compose secrets, as
# psql variables, never pasted into SQL text.
set -euo pipefail

password() {
  cat "/run/secrets/$1"
}

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_password="$(password owner_password)" \
  -v app_password="$(password app_password)" \
  -v catalog_password="$(password catalog_password)" \
  -v inventory_password="$(password inventory_password)" \
  -v booking_password="$(password booking_password)" \
  -v payment_password="$(password payment_password)" \
  -v notification_password="$(password notification_password)" \
  -v service_password="$(password service_password)" <<'SQL'
CREATE ROLE seatwave_owner LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'owner_password';
CREATE ROLE catalog_module LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'catalog_password';
CREATE ROLE inventory_module LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'inventory_password';
CREATE ROLE booking_module LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'booking_password';
CREATE ROLE payment_module LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'payment_password';
CREATE ROLE notification_module LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'notification_password';
CREATE ROLE seatwave_app LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'app_password';
CREATE ROLE notification_service LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'service_password';
GRANT catalog_module, inventory_module, booking_module, payment_module, notification_module
  TO seatwave_owner WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
ALTER ROLE seatwave_owner SET search_path = '';
ALTER ROLE catalog_module SET search_path = '';
ALTER ROLE inventory_module SET search_path = '';
ALTER ROLE booking_module SET search_path = '';
ALTER ROLE payment_module SET search_path = '';
ALTER ROLE notification_module SET search_path = '';
ALTER ROLE seatwave_app SET search_path = '';
ALTER ROLE notification_service SET search_path = '';
REVOKE CONNECT ON DATABASE seatwave FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE seatwave TO seatwave_owner;
GRANT CONNECT ON DATABASE seatwave TO catalog_module, inventory_module, booking_module,
  payment_module, notification_module, seatwave_app, notification_service;
-- Section 10's rollout runs in a database of its own, which starts as Module 13's one schema.
CREATE DATABASE seatwave_rollout OWNER seatwave_owner;
REVOKE CONNECT ON DATABASE seatwave_rollout FROM PUBLIC;
GRANT CONNECT ON DATABASE seatwave_rollout TO seatwave_app, inventory_module;
SQL
