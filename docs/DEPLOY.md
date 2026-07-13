# Deploying Forge (Render + Neon + Upstash, $0)

The live demo runs on three free tiers: Render hosts the container, Neon
hosts Postgres, Upstash hosts Redis. Total cost: zero, no expiry. Two honest
caveats, also stated in the README: the free web service spins down after
~15 idle minutes (first request after that takes ~30–50s), and it runs the
API and worker in one container — the multi-process design is demonstrated by
docker-compose and `demo/chaos.sh`.

## 1. Neon (Postgres)

1. Create a project at neon.tech (free plan). Database name: `forge`.
2. From the connection details, note host, user, and password.
3. The JDBC URL is `jdbc:postgresql://<host>/forge?sslmode=require`.

Flyway runs the migrations automatically on first boot.

## 2. Upstash (Redis)

1. Create a Redis database at upstash.com (free plan, any region near
   Render's).
2. Copy the TLS connection URL — it starts with `rediss://` (double s).
   Spring's `spring.data.redis.url` accepts it directly and it takes
   precedence over the host/port settings.

Command budget: the free plan allows 500K commands/month. With 15s heartbeats
(the render.yaml default; ~172K/month) plus reaper checks, rate-limit calls,
and idempotency lookups, a demo deployment stays well under budget. Do not
lower the heartbeat interval on this plan.

## 3. Render (the app)

1. New → Blueprint, select this repo. Render reads `render.yaml`.
2. When prompted for the secret env vars:
   - `SPRING_DATASOURCE_URL` — the Neon JDBC URL
   - `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` — from Neon
   - `SPRING_DATA_REDIS_URL` — the Upstash `rediss://` URL
   - `FORGE_API_KEYS` — pick a demo key (this is what README examples use)
3. Deploy. First build takes a few minutes; the health check is
   `/actuator/health`.

## 4. Verify

```sh
API=https://<service>.onrender.com
curl "$API/actuator/health"
curl -X POST "$API/api/v1/jobs" \
  -H "X-API-Key: <your key>" -H 'Content-Type: application/json' \
  -d '{"type":"sleep","payload":{"millis":100}}'
curl -H "X-API-Key: <your key>" "$API/api/v1/jobs/<id from above>"   # SUCCEEDED
curl -H "X-API-Key: <your key>" "$API/api/v1/queues/default/stats"
```

Then put the live URL in the README header.
