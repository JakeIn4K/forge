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

## Local development

Requires JDK 21 and Maven.

```sh
mvn verify   # build + tests + coverage report (target/site/jacoco)
```
