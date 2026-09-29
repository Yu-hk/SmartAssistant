-- Administrators maintain aliases explicitly. No name or specification text is auto-promoted.
BEGIN;

ALTER TABLE products ADD COLUMN IF NOT EXISTS aliases_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS aliases_updated_by BIGINT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS aliases_updated_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS product_aliases (
    product_code VARCHAR(50) NOT NULL REFERENCES products(product_code) ON DELETE CASCADE,
    alias VARCHAR(200) NOT NULL CHECK (length(btrim(alias)) >= 2),
    normalized_alias VARCHAR(200) NOT NULL,
    PRIMARY KEY (product_code, normalized_alias)
);
CREATE INDEX IF NOT EXISTS idx_product_aliases_normalized
    ON product_aliases(normalized_alias, product_code);

COMMENT ON TABLE product_aliases IS 'Administrator-maintained names for discovery; never evidence of price, stock or suitability.';

COMMIT;
