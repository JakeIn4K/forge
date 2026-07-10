# Forge — Distributed Job Queue

A persistent, distributed job queue with a REST API. Producers submit jobs over HTTP; a pool of workers claims and executes them; jobs survive crashes and are retried with exponential backoff.

Built on PostgreSQL (`SELECT ... FOR UPDATE SKIP LOCKED` claim semantics) and Redis, with Spring Boot 3 on Java 21.

> Work in progress — currently at the project-skeleton stage. Architecture diagram, design notes, and benchmarks will land as the build progresses.

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

Submit a job:

```sh
curl -X POST localhost:8080/api/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{"queue": "default", "type": "sleep", "payload": {"millis": 2000}, "idempotencyKey": "demo-42"}'
```

Fetch it (id comes from the submit response):

```sh
curl localhost:8080/api/v1/jobs/<id>
```

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

## Local development

Requires JDK 21 and Maven.

```sh
mvn verify   # build + tests + coverage report (target/site/jacoco)
```
