-- With retries, a failure is either transient (job returns to PENDING with a
-- future run_at) or terminal (DEAD). FAILED as a resting status no longer
-- exists, so fold any old rows into DEAD and tighten the constraint.
UPDATE jobs SET status = 'DEAD' WHERE status = 'FAILED';

ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_check
  CHECK (status IN ('PENDING', 'CLAIMED', 'RUNNING', 'SUCCEEDED', 'DEAD'));
