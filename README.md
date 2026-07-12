# Forge — Distributed Job Queue

A persistent, distributed job queue with a REST API. Producers submit jobs over HTTP; a pool of workers claims and executes them; jobs survive crashes and are retried with exponential backoff.

Built on PostgreSQL (`SELECT ... FOR UPDATE SKIP LOCKED` claim semantics) and Redis, with Spring Boot 3 on Java 21.

> Work in progress — failure recovery, rate limiting, metrics, and benchmarks are in. Architecture diagram and final design notes land with deployment.

**Measured** (laptop, everything in one docker-compose stack — see [bench/RESULTS.md](bench/RESULTS.md) for honest methodology): **2,580 submissions/sec** at **p99 12.6ms**, **1,149 jobs/sec** sustained drain across 3 workers, **p99 end-to-end latency 113ms**. Claim query: **0.55ms against a 50k backlog**, no sort, verified with `EXPLAIN ANALYZE`.

## Quickstart

```sh
cp .env.example .env
docker compose up --build
```

Then check the API is up:

```sh
curl localhost:8080/actuator/health
```

## API

All `/api/**` endpoints require an `X-API-Key` header (`FORGE_API_KEYS`, default `dev-key` locally; OAuth would be the production choice). Submit a job:

```sh
curl -X POST localhost:8080/api/v1/jobs \
  -H 'X-API-Key: dev-key' \
  -H 'Content-Type: application/json' \
  -d '{"queue": "default", "type": "sleep", "payload": {"millis": 2000}, "idempotencyKey": "demo-42"}'
```

Fetch it (id comes from the submit response):

```sh
curl -H 'X-API-Key: dev-key' localhost:8080/api/v1/jobs/<id>
```

Queue stats — depth by status, oldest pending age, jobs finished in the last minute:

```sh
curl -H 'X-API-Key: dev-key' localhost:8080/api/v1/queues/default/stats
```

Submission is rate limited per API key by a Redis token bucket (one atomic Lua script per request; continuous refill, so bursts up to `FORGE_RATE_LIMIT_CAPACITY` are absorbed and sustained load is capped at `FORGE_RATE_LIMIT_REFILL_PER_SECOND`). Over the limit you get `429` with a `Retry-After` header. If Redis is down the limiter fails open — it protects the database, it must not become the outage.

Submitting the same `idempotencyKey` twice returns the original job (200 instead of 201) — duplicates are detected via a Redis fast path, with a unique constraint in Postgres as the source of truth.

## Workers

`docker compose up` runs one worker alongside the API (same image, `worker` Spring profile). Each worker runs a configurable number of threads (`FORGE_WORKER_THREADS`, default 4), each looping: claim one job, execute it, repeat.

Claiming uses `SELECT ... FOR UPDATE SKIP LOCKED` inside a single atomic `UPDATE`, so concurrent workers never grab the same job — each claimer skips rows already locked by another transaction instead of blocking on them. Delivery is at-least-once: handlers must be idempotent, since a job can re-run after a crash.

Job types map to handler implementations: `sleep` (waits `payload.millis`) and `http-callback` (POSTs `payload.body` to `payload.url`). Watch a job get processed:

```sh
docker compose logs -f worker
```

Scale workers horizontally:

```sh
docker compose up -d --scale worker=3
```

## Failure recovery — kill a worker, lose nothing

When a job's handler throws, the job returns to `PENDING` with `run_at` pushed out by exponential backoff with full jitter (delay drawn uniformly from `[0, base * 2^attempt]`, capped). Once `attempts` reaches `max_attempts` the job is dead-lettered as `DEAD` and inspectable:

```sh
curl 'localhost:8080/api/v1/queues/default/dead'
```

When a worker *dies* — crash, OOM-kill, pulled plug — its in-flight jobs are not lost. Every worker publishes a heartbeat to Redis (TTL three beat intervals); a reaper running in every worker reclaims jobs whose claim is older than the visibility timeout **and** whose owner's heartbeat is gone. The reclaim is a single conditional `UPDATE` keyed on `claimed_by`, so any number of concurrent reapers is safe without coordination. A crash counts as an attempt, so a poison job that keeps killing workers ends up `DEAD` instead of looping forever.

See it happen — enqueue 1,000 jobs, SIGKILL one of three workers mid-drain, and watch every job still complete:

```sh
demo/chaos.sh
```

## Observability

Prometheus metrics at `localhost:8080/actuator/prometheus` (no API key — meant for scraping):

- `forge_queue_depth{queue,status}` — jobs per queue per status
- `forge_claim_duration{result="hit|empty"}` — latency of the `SKIP LOCKED` claim query
- `forge_job_duration{type,outcome}` — execution time percentiles per job type
- `forge_jobs_retried_total`, `forge_jobs_dead_total{reason}`, `forge_jobs_reclaimed_total{outcome}` — the failure-recovery machinery, visible

Priority and delayed jobs are first-class: higher `priority` wins, ties go to the oldest `run_at`, and a job with a future `run_at` is invisible to workers until it comes due — all served by one partial index shaped exactly like the claim query.

## Local development

Requires JDK 21 and Maven.

```sh
mvn verify   # build + tests + coverage report (target/site/jacoco)
```
