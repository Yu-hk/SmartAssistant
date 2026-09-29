-- Declared target audiences and use cases are separate from measurable product features.
-- Existing products are intentionally left untagged; no suitability is inferred from prose.
BEGIN;

ALTER TABLE products ADD COLUMN IF NOT EXISTS suitability_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS suitability_source TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS suitability_reviewed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE products ADD COLUMN IF NOT EXISTS suitability_updated_by BIGINT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS suitability_updated_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS product_suitability_tags (
    product_code VARCHAR(50) NOT NULL REFERENCES products(product_code) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('audience', 'use_case')),
    tag VARCHAR(40) NOT NULL CHECK (length(btrim(tag)) > 0),
    PRIMARY KEY (product_code, kind, tag)
);
CREATE INDEX IF NOT EXISTS idx_product_suitability_kind_tag
    ON product_suitability_tags(kind, tag, product_code);

COMMENT ON COLUMN products.suitability_source IS 'Administrator-recorded basis for declared audiences/use cases; not independently verified performance.';
COMMENT ON COLUMN products.suitability_reviewed_at IS 'Server time of administrator confirmation of the suitability declaration.';
COMMENT ON TABLE product_suitability_tags IS 'Declared audiences and use cases; absence means unknown, never unsuitable.';

COMMIT;
