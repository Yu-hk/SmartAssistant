BEGIN;
SET LOCAL lock_timeout='3s';
SET LOCAL statement_timeout='30s';
ALTER TABLE products ADD COLUMN IF NOT EXISTS identity_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE products ADD COLUMN IF NOT EXISTS identity_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS product_identity_audit (
    id BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(50) NOT NULL REFERENCES products(product_code),
    revision BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    before_metadata JSONB NOT NULL,
    after_metadata JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(product_code, revision)
);
COMMIT;
