#!/usr/bin/env bash
# Sustained drain throughput: with a large backlog already enqueued (run
# submit.js first), sample the SUCCEEDED count twice and report jobs/sec.
set -euo pipefail
cd "$(dirname "$0")/.."

WINDOW=${WINDOW:-30}

count() {
  docker compose exec -T postgres psql -U "${POSTGRES_USER:-forge}" -d "${POSTGRES_DB:-forge}" \
    -tAc "SELECT count(*) FROM jobs WHERE status = 'SUCCEEDED'"
}

BEFORE=$(count)
sleep "$WINDOW"
AFTER=$(count)
echo "drained $((AFTER - BEFORE)) jobs in ${WINDOW}s = $(( (AFTER - BEFORE) / WINDOW )) jobs/sec"
