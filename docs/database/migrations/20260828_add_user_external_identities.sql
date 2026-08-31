CREATE TABLE IF NOT EXISTS user_external_identities (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    subject VARCHAR(191) NOT NULL,
    union_id VARCHAR(191),
    display_name VARCHAR(200),
    avatar_url TEXT,
    email VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_external_identity_subject UNIQUE (provider, subject)
);

CREATE INDEX IF NOT EXISTS idx_external_identity_user_id
    ON user_external_identities(user_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_external_identity_union_id
    ON user_external_identities(provider, union_id)
    WHERE union_id IS NOT NULL;
