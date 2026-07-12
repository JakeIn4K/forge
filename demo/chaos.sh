#!/usr/bin/env bash
# Kill a worker, lose nothing.
#
# Enqueues a batch of sleep jobs, SIGKILLs one of three workers while they are
# mid-drain (no graceful shutdown — in-flight jobs die with it), and proves
# that every single job still completes: the dead worker's jobs are reclaimed
# by the reaper once its heartbeats expire, retried, and finished by the
# survivors.
set -euo pipefail
cd "$(dirname "$0")/.."

JOBS=${JOBS:-1000}
API=${API:-http://localhost:8080}
STARTED_AT=$(date -u +"%Y-%m-%d %H:%M:%S+00")

sql() {
  docker compose exec -T postgres psql -U "${POSTGRES_USER:-forge}" -d "${POSTGRES_DB:-forge}" -tAc "$1"
}

count_where() {
  sql "SELECT count(*) FROM jobs WHERE created_at >= '$STARTED_AT' AND $1"
}

echo "== starting stack with 3 workers"
docker compose up -d --build --scale worker=3

echo "== waiting for the API"
until curl -sf "$API/actuator/health" >/dev/null; do sleep 2; done

echo "== enqueueing $JOBS sleep jobs"
seq 1 "$JOBS" | xargs -P 8 -I. curl -sf -o /dev/null -X POST "$API/api/v1/jobs" \
  -H "X-API-Key: ${API_KEY:-dev-key}" \
  -H 'Content-Type: application/json' \
  -d '{"type":"sleep","payload":{"millis":250}}'

echo "== letting workers get busy"
sleep 5

VICTIM=$(docker compose ps -q worker | head -1)
echo "== SIGKILL worker ${VICTIM:0:12} with jobs in flight"
docker kill --signal SIGKILL "$VICTIM" >/dev/null

DEADLINE=$(( $(date +%s) + 600 ))
while true; do
  SUCCEEDED=$(count_where "status = 'SUCCEEDED'")
  IN_FLIGHT=$(count_where "status IN ('PENDING', 'CLAIMED', 'RUNNING')")
  DEAD=$(count_where "status = 'DEAD'")
  echo "   succeeded=$SUCCEEDED in_flight=$IN_FLIGHT dead=$DEAD / $JOBS"

  if [ "$SUCCEEDED" -eq "$JOBS" ]; then
    echo "== PASS: all $JOBS jobs succeeded, zero lost, despite a worker dying mid-run"
    exit 0
  fi
  if [ "$IN_FLIGHT" -eq 0 ]; then
    echo "== FAIL: nothing in flight but only $SUCCEEDED/$JOBS succeeded ($DEAD dead)"
    exit 1
  fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "== FAIL: timed out with $SUCCEEDED/$JOBS succeeded"
    exit 1
  fi
  sleep 5
done
