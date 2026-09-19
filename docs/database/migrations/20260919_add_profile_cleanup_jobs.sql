-- Internal foundation only: no jobs are created and no existing user is paused by migration.
CREATE TABLE IF NOT EXISTS profile_cleanup_job (
    job_id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    state varchar(24) NOT NULL CHECK (state IN ('PAUSED','PARTIAL','ONLINE_CLEANED','STALE')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id,idempotency_key)
);
CREATE TABLE IF NOT EXISTS profile_cleanup_receipt (
    job_id uuid NOT NULL REFERENCES profile_cleanup_job(job_id) ON DELETE CASCADE,
    target varchar(32) NOT NULL CHECK (target IN ('POSTGRES_PROFILE','REDIS_INDEXED','LEGACY_STORAGE','DERIVED_COPIES','BACKUP_RESTORE')),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','RETRY','SUCCEEDED','BLOCKED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    error_code varchar(48),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(job_id,target)
);
CREATE INDEX IF NOT EXISTS idx_profile_cleanup_retry ON profile_cleanup_receipt(next_attempt_at)
    WHERE state IN ('PENDING','RETRY');
