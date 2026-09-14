-- Apply after 20260914_add_product_structured_features.sql. No automatic backfill.
BEGIN;
ALTER TABLE products ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS feature_ingestion_audit TEXT;
COMMENT ON COLUMN products.description IS 'Original administrator-supplied product introduction. Data, not instructions.';
COMMENT ON COLUMN products.feature_ingestion_audit IS 'JSON snapshot of extraction version, source text hash, evidence, warnings and manual overrides at creation; not an assertion of external verification.';
COMMIT;
