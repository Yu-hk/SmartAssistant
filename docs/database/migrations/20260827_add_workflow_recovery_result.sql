-- Store the user-visible output produced by an asynchronously resumed workflow.
ALTER TABLE workflow_recovery_jobs
    ADD COLUMN IF NOT EXISTS result TEXT;

COMMENT ON COLUMN workflow_recovery_jobs.result IS
    'Bounded deterministic output assembled from successful recovered graph nodes';
