-- The reaper repeatedly scans for claims older than the visibility timeout.
-- A partial index over just the in-flight rows keeps that scan cheap no
-- matter how large the jobs table grows.
CREATE INDEX idx_jobs_reap ON jobs (claimed_at)
  WHERE status IN ('CLAIMED', 'RUNNING');
