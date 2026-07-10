-- Workers route each job to a handler by type. Backfill existing rows to
-- 'sleep', then drop the default so new inserts must state a type explicitly.
ALTER TABLE jobs ADD COLUMN type TEXT NOT NULL DEFAULT 'sleep';
ALTER TABLE jobs ALTER COLUMN type DROP DEFAULT;
