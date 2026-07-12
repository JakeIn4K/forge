# Benchmark results

Honest numbers from real runs. Every figure here was produced by the scripts
in this directory against the docker-compose stack; nothing is extrapolated.

## Methodology

- **Hardware:** laptop — Intel i7-12700H (20 logical cores), 15 GB RAM allocated
  to the WSL2 VM, NVMe SSD. Everything (API, 3 workers, Postgres, Redis, k6)
  runs on the same machine, so numbers include that contention and are
  conservative relative to a real multi-host deployment.
- **Stack:** `docker compose up -d --scale worker=3` — 1 API container,
  3 worker containers (4 threads each), Postgres 16, Redis 7. Fresh volumes
  per scenario (`docker compose down -v`).
- **Rate limit raised for bench runs** (`FORGE_RATE_LIMIT_CAPACITY=1000000
  FORGE_RATE_LIMIT_REFILL_PER_SECOND=500000`): the limiter's Redis Lua call is
  still on the hot path and measured, but its policy doesn't cap the result.
- **Job type:** `sleep` with `millis: 0` — handlers return immediately, so the
  numbers measure the queue machinery, not job workloads.
- **Runs:** `k6 run bench/submit.js` (20 VUs, 60s steady), then
  `bench/drain.sh` against the backlog, and `k6 run bench/e2e.js`
  (5 VUs, 60s) on an idle queue.

## Results

| Metric | Baseline | Tuned | Change |
|---|---|---|---|
| Submission throughput (req/s) | 2,550 | TBD | |
| Submit latency p50 / p95 / p99 (ms) | 6.3 / 10.7 / 13.7 | TBD | |
| Drain throughput (jobs/s, 3 workers) | 659 | TBD | |
| End-to-end latency p50 / p95 / p99 (ms) | 483 / 544 / 703 | TBD | |

Baseline run: 192,531 jobs submitted over 75s, zero failed requests.

**Reading the baseline:** submission is healthy (p99 under 14ms including the
auth check, the rate-limiter Lua call, and the idempotency-safe insert). The
end-to-end median of ~483ms is not queue slowness — it is the 500ms worker
poll interval staring back: on an idle queue a new job waits, on average, half
a poll interval times the number of sleeping threads' phase offsets. Drain
throughput is bounded by per-job query round trips (claim + mark running +
mark done per job). Both observations drive the tuning below.

## Tuning log

Each change is its own `perf:` commit with before/after numbers in the message.

TBD

## Claim query plan

TBD — `EXPLAIN ANALYZE` output and reading.
