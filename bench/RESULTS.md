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
| Submission throughput (req/s) | 2,550 | 2,584 | — (not targeted) |
| Submit latency p50 / p95 / p99 (ms) | 6.3 / 10.7 / 13.7 | 6.1 / 10.2 / 12.6 | — (within noise) |
| Drain throughput (jobs/s, 3 workers) | 659 | **1,149** | **+74%** |
| End-to-end latency p50 / p95 / p99 (ms) | 483 / 544 / 703 | **59 / 108 / 113** | **~7x lower** |

Baseline run: 192,531 jobs submitted over 75s, zero failed requests. Tuned
runs: 195,130 and 197,395 submissions, zero failures; 9,636 e2e completions
in 60s (vs 1,432 at baseline — each iteration finishes sooner).

**Reading the baseline:** submission is healthy (p99 under 14ms including the
auth check, the rate-limiter Lua call, and the idempotency-safe insert). The
end-to-end median of ~483ms is not queue slowness — it is the 500ms worker
poll interval staring back: on an idle queue a new job waits, on average, half
a poll interval times the number of sleeping threads' phase offsets. Drain
throughput is bounded by per-job query round trips (claim + mark running +
mark done per job). Both observations drive the tuning below.

## Tuning log

Each change is its own `perf:` commit with before/after numbers in the message.

1. **Batch claiming** (`perf: claim jobs in batches of ten`) — one claim query
   takes up to 10 jobs instead of 1, amortizing the round trip. Drain
   659 → 1,149 jobs/sec (+74%). Tradeoffs: a crashed worker strands up to a
   batch until the reaper reclaims it; jobs within a batch run serially on one
   thread, so batch size should stay modest relative to job duration.
2. **Poll interval 500ms → 100ms** (`perf: poll every 100ms instead of 500ms`)
   — the baseline e2e median *was* the poll interval: an idle worker sleeps
   500ms between checks, so fresh jobs sat waiting for someone to wake up.
   E2e p50 483 → 59ms, p99 703 → 113ms. Cost: an idle thread runs 10 empty
   claim queries/sec instead of 2; each is a sub-millisecond indexed lookup.
3. **Connection pool 10 → 20 (API): no measurable change** (2,584 → 2,619
   req/s, ~1%, within run-to-run noise) — the pool is not the bottleneck at
   this load, so the default stays 10. Kept as a compose knob (`FORGE_DB_POOL`)
   for the deployment to tune against managed-Postgres connection limits.

## Claim query plan

`EXPLAIN (ANALYZE, BUFFERS)` on the batch claim with **50,000 pending rows**
(workers stopped, table vacuumed):

```
Update on jobs  (actual time=0.251..0.371 rows=10)
  ->  Nested Loop  (actual time=0.084..0.142 rows=10)
        ->  HashAggregate
              ->  Limit  (actual time=0.036..0.048 rows=10)
                    ->  LockRows  (actual time=0.036..0.046 rows=10)
                          ->  Index Scan using idx_jobs_claim on jobs
                                Index Cond: ((queue = 'default') AND (run_at <= now()))
                                Filter: (status = 'PENDING')
                                Buffers: shared hit=5
        ->  Index Scan using jobs_pkey (rows=1, loops=10)
Execution Time: 0.552 ms
```

The reading (interview version): the partial index `idx_jobs_claim (queue,
priority DESC, run_at) WHERE status = 'PENDING'` serves the query in **0.55ms
against 50k pending rows**, touching ~25 buffer pages. Two things matter:
there is **no Sort node** — the index returns rows already in
priority-then-age order, so `LIMIT 10` reads exactly 10 index entries and
never examines the other 49,990; and `LockRows` sits *under* the Limit, so
only the 10 chosen rows are locked (SKIP LOCKED skips any a concurrent worker
holds). Claim cost is effectively independent of backlog depth.
