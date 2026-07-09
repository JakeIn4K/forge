CREATE TABLE jobs (
  id              UUID PRIMARY KEY,
  queue           TEXT NOT NULL DEFAULT 'default',
  payload         JSONB NOT NULL,
  status          TEXT NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'CLAIMED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD')),
  priority        INT NOT NULL DEFAULT 0,
  run_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts        INT NOT NULL DEFAULT 0,
  max_attempts    INT NOT NULL DEFAULT 5,
  claimed_by      TEXT,
  claimed_at      TIMESTAMPTZ,
  idempotency_key TEXT UNIQUE,
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial index covering the claim query's exact shape (Phase 2):
-- pick PENDING jobs for a queue, highest priority first, oldest run_at first.
CREATE INDEX idx_jobs_claim ON jobs (queue, priority DESC, run_at)
  WHERE status = 'PENDING';
