CREATE TABLE IF NOT EXISTS product_relations (
    id BIGSERIAL PRIMARY KEY,
    source_product_code VARCHAR(50) NOT NULL REFERENCES products(product_code) ON DELETE CASCADE,
    target_product_code VARCHAR(50) NOT NULL REFERENCES products(product_code) ON DELETE CASCADE,
    relation_type VARCHAR(32) NOT NULL,
    weight NUMERIC(5,4) NOT NULL DEFAULT 0.5 CHECK (weight >= 0 AND weight <= 1),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_relation UNIQUE (source_product_code, target_product_code, relation_type)
);

CREATE INDEX IF NOT EXISTS idx_product_relations_source
    ON product_relations(source_product_code) WHERE enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_product_relations_target
    ON product_relations(target_product_code) WHERE enabled = TRUE;
