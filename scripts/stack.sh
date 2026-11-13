#!/usr/bin/env bash
# Starts, tests and stops Module 32's lab stack (PostgreSQL 18.6 and one Kafka broker) under the
# Compose project name seatwave-modulith, its two ports on 127.0.0.1 only.
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print the plan, change nothing (and need no Docker)
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question
#
#   up      generate the nine lab passwords into .secrets, start PostgreSQL and Kafka, wait until
#           both are healthy and kafka-init has created seatwave.bookings
#   down    remove the project's container, network and volume (down -v) and the passwords of the
#           database that is now gone, then check none is left
#   test    up, ./gradlew stackTest extractedStackTest, then down, on every path
#   status  list the project's containers (read-only)
set -euo pipefail

readonly PROJECT="seatwave-modulith"
readonly WAIT_SECONDS=60
readonly DOCKER_TIMEOUT_SECONDS=10
readonly PASSWORD_BYTES=24
# One password per login of db/init/10-roles.sh, and the superuser's.
readonly LOGINS=(postgres seatwave_owner seatwave_app catalog_module inventory_module booking_module
  payment_module notification_module notification_service)

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|status) ;; *) usage ;; esac

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  write .secrets/<login>.password for ${#LOGINS[@]} logins if missing; ${compose[*]} up -d --wait postgres kafka; run kafka-init" ;;
  down) echo "  ${compose[*]} down -v --remove-orphans; remove .secrets; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest extractedStackTest, then down -v (always)" ;;
  status) echo "  ${compose[*]} ps --all" ;;
esac
# The dry run comes before the Docker check: it changes nothing, so it needs nothing.
if [[ "$mode" == "dry-run" && "$action" != "status" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi

# macOS has no timeout command, so the check runs in the background and is killed if it hangs.
docker_answers() {
  docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &
  local checker=$!
  local waited
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      wait "$checker"
      return $?
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  return 1
}
if ! command -v docker > /dev/null || ! docker_answers; then
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s); nothing was changed." >&2
  exit 1
fi
if [[ "$action" == "status" ]]; then
  "${compose[@]}" ps --all
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

secrets() {
  mkdir -p .secrets
  local name
  for name in "${LOGINS[@]}"; do
    if [[ ! -s ".secrets/$name.password" ]]; then
      # Readable by all, not owner-only: on a Linux host a Compose secret is a bind mount that keeps
      # the file's owner and mode, and 10-roles.sh reads it as the container's postgres user. Any
      # account on this machine can read these throwaway lab passwords; down deletes them.
      ( umask 022; openssl rand -hex "$PASSWORD_BYTES" > ".secrets/$name.password" )
    fi
  done
}

up() {
  secrets
  # --wait returns when the healthcheck passes, or fails after the timeout.
  "${compose[@]}" up -d --wait --wait-timeout "$WAIT_SECONDS" --quiet-pull postgres kafka
  # kafka-init has no healthcheck and exits when the topic exists, so it runs in the foreground.
  "${compose[@]}" up --quiet-pull --exit-code-from kafka-init kafka-init
  audit "up: started $PROJECT"
}

down() {
  "${compose[@]}" down -v --remove-orphans
  # The passwords belonged to the database in the volume just removed; the next up makes new ones.
  local name
  for name in "${LOGINS[@]}"; do
    rm -f ".secrets/$name.password"
  done
  audit "down: removed $PROJECT and its passwords"
  local left=""
  left+="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT") "
  left+="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT") "
  left+="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  if [[ -n "${left// /}" ]]; then
    echo "left behind by $PROJECT: $left" >&2
    return 1
  fi
  echo "nothing of $PROJECT is left"
}

case "$action" in
  up) up ;;
  down) down ;;
  test)
    status=0
    up || status=$?
    if [[ "$status" -eq 0 ]]; then
      ./gradlew stackTest extractedStackTest || status=$?
    fi
    down || status=$?
    exit "$status"
    ;;
esac
