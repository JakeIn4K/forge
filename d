[33mcommit 609f4328cf822fe5c120198c29e4ab5578c92210[m[33m ([m[1;36mHEAD[m[33m -> [m[1;32mfeat/failure-recovery[m[33m)[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Fri Jul 10 23:21:11 2026 -0500

    test: cover retries, dead lettering, and reaper reclaim end to end
    
    Backoff unit tests pin the jitter window and the cap. Integration tests prove: a flaky handler succeeds after retries with attempts counted; a hopeless job dead-letters at max_attempts; a job claimed by a silent worker is reclaimed and rerun exactly once; a slow job on a heartbeating worker is never touched; a reclaim that consumes the last attempt goes straight to DEAD; dead jobs are listable per queue.

[33mcommit 89b422e1b220dcd80ff1f95c3f1eecaa4fa70c0e[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Fri Jul 10 23:17:31 2026 -0500

    feat: expose dead-lettered jobs per queue over the api
    
    GET /api/v1/queues/{queue}/dead lists jobs that exhausted their attempts, newest first, so a human can inspect what permanently failed and why via last_error.

[33mcommit 65b1cb5651a52f77b141a92e6782c0b86dabd138[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Fri Jul 10 23:16:37 2026 -0500

    feat: reaper reclaims jobs from workers that miss heartbeats
    
    Every worker runs the reaper on a schedule. A job is reclaimed only when its claim is older than the visibility timeout and its workers heartbeat key is gone from Redis; the conditional UPDATE keyed on claimed_by makes concurrent reapers safe without coordination. If Redis is unreachable the pass is skipped: reclaiming from a live worker is worse than waiting.

[33mcommit c298885e258cbd4f7b8eeda887b6d7970c977264[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Fri Jul 10 23:15:20 2026 -0500

    feat: workers publish liveness heartbeats to redis
    
    Each worker process refreshes a Redis key with a TTL of three beat intervals; a crashed process goes silent within one TTL. Claims now carry the process-level worker id, since liveness (and therefore reclaim eligibility) is a property of the process, not of a pool thread.

[33mcommit 7836688b4946553dec275be7e46121659e62ea22[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Fri Jul 10 23:14:07 2026 -0500

    feat: retry failed jobs with exponential backoff and full jitter
    
    A failed job goes back to PENDING with run_at pushed into the future by a randomized exponential delay; once attempts reach max_attempts it is dead-lettered as DEAD instead. FAILED as a resting status no longer exists (V3 retires it), and jobs with no registered handler dead-letter immediately since retrying cannot fix a missing class.

[33mcommit 748754975749733823dfc03e3e9bbb602ca043b5[m[33m ([m[1;31morigin/main[m[33m, [m[1;31morigin/HEAD[m[33m, [m[1;32mmain[m[33m)[m
Merge: 84c482a 897bd2c
Author: JakeIn4K <110363584+JakeIn4K@users.noreply.github.com>
Date:   Fri Jul 10 21:53:52 2026 -0500

    Merge pull request #4 from JakeIn4K/feat/worker
    
    worker's claim and execute jobs with FOR UPDATE SKIP LOCKED

[33mcommit 84c482a243225e4999f013c9c55f71a8d883dc61[m
Merge: b0f8222 00de49b
Author: JakeIn4K <110363584+JakeIn4K@users.noreply.github.com>
Date:   Fri Jul 10 21:40:39 2026 -0500

    Merge pull request #3 from JakeIn4K/chore/normalize-line-endings
    
    chore: enforce lf line endings via gitattributes

[33mcommit 897bd2c83f42b083aac7a85f3da6c7d904c39185[m[33m ([m[1;31morigin/feat/worker[m[33m)[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Thu Jul 9 20:09:48 2026 -0500

    docs: document worker claim semantics in readme

[33mcommit c035e157bbfecab87476cc4104fa6e866c91f191[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Thu Jul 9 20:09:48 2026 -0500

    test: prove concurrent workers never double-process a job

[33mcommit 210ed0d4be5ebcd965f844095b88dabaefb4faef[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Thu Jul 9 20:09:48 2026 -0500

    feat: run a dedicated worker container in docker compose

[33mcommit 35fcea8ad6e94262ca0e5bf4c5e1d48e470e88ac[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Thu Jul 9 20:09:48 2026 -0500

    feat: worker pool polls queue and executes jobs via typed handlers

[33mcommit 4d6506937039c678d6a4f1b013e1d4d7f9ca9b14[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Thu Jul 9 20:09:48 2026 -0500

    feat: claim jobs atomically with for update skip locked

[33mcommit 8683a7facdb2f900860fdad69cb2b45ec485fb2d[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Thu Jul 9 20:09:48 2026 -0500

    feat: add job type for routing jobs to handlers

[33mcommit 00de49bc0b66b85c39153bb93dfad63cc6daae24[m[33m ([m[1;31morigin/chore/normalize-line-endings[m[33m)[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Thu Jul 9 19:24:32 2026 -0500

    chore: enforce lf line endings via gitattributes

[33mcommit b0f822222db69e7cfc36c289a1c0cc59d0653ed4[m
Merge: 8404c98 581fb4a
Author: JakeIn4K <110363584+JakeIn4K@users.noreply.github.com>
Date:   Thu Jul 9 19:15:07 2026 -0500

    Merge pull request #2 from JakeIn4K/feat/job-submission
    
    job submission and persistence with idempotency keys

[33mcommit 581fb4a0d0fe2f7ff20226dc6a38ac24b8b53d8d[m[33m ([m[1;31morigin/feat/job-submission[m[33m)[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:17:06 2026 -0500

    docs: document job submission api in readme

[33mcommit ccbc46883e72bdd47436dbfae27b2233aad220f6[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:17:06 2026 -0500

    fix: bump testcontainers to 1.21.4 for docker engine 29 api compatibility

[33mcommit a79ef121a1ba72340b62ffe69535c62af5dc3a22[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:17:06 2026 -0500

    test: cover job submission, defaults, idempotency, and error responses

[33mcommit f2e857b4e00894f55c36a97fbe57ea1bce9a8f38[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:16:39 2026 -0500

    feat: submit and fetch jobs over REST with problem+json errors

[33mcommit ca899150e81dc1f56d59471be407392ef12f54a9[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:16:39 2026 -0500

    feat: redis fast-path cache for idempotency keys

[33mcommit bd1264ebd0d3b1319b57e20ae28b67f9298a5d6b[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:16:39 2026 -0500

    feat: job domain model and jdbc repository with atomic idempotent insert

[33mcommit c2ef93276a0a527ba85f82b92c6189039959ad01[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:16:39 2026 -0500

    feat: create jobs table with partial index for the claim query

[33mcommit 515a5416bee7cc987fd0c3222c3d4893e62a90bf[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 22:16:39 2026 -0500

    chore: add persistence, redis, validation, and testcontainers dependencies

[33mcommit 8404c98b7d833b089742f2556ec1083737d8e8dc[m
Merge: c3f900b 8f21801
Author: JakeIn4K <110363584+JakeIn4K@users.noreply.github.com>
Date:   Wed Jul 8 21:23:00 2026 -0500

    Merge pull request #1 from JakeIn4K/feat/project-skeleton
    
    project skeleton, docker-compose, and CI

[33mcommit 8f21801b0549752d524e98c7f9dc0ede142d8fab[m[33m ([m[1;31morigin/feat/project-skeleton[m[33m)[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 19:22:12 2026 -0500

    chore: ignore local planning and learning docs

[33mcommit bd42666f55ca0c99d85923ab0a7326e793b0e5fc[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 19:04:07 2026 -0500

    docs: describe project and add quickstart

[33mcommit 6eb801ac9f338368f74030ea6f832a3e11c1fb52[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 19:04:07 2026 -0500

    ci: build and test on every PR with coverage report

[33mcommit b3f137c7ac26d84c89e78b0d68707cff7bb04f2f[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 19:04:07 2026 -0500

    chore: containerize app with Postgres and Redis via docker-compose

[33mcommit 5fed64f39827ce5ad5c35ab1eb674cc6c84a8d8f[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 19:04:07 2026 -0500

    test: verify actuator health endpoint reports UP

[33mcommit 1f70b81ce16510a388b8417bbc448bece70c2e1e[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 19:04:07 2026 -0500

    chore: bootstrap Spring Boot 3.5 skeleton on Java 21

[33mcommit c3f900b479aa377e99c3f1fb9fdb66d948af961c[m
Author: Jake Salvatore <jakesalvatore43@gmail.com>
Date:   Wed Jul 8 18:35:26 2026 -0500

    docs: add README stub and gitignore
