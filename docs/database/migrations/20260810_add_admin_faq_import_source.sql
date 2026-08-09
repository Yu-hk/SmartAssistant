-- Additive, idempotent provenance fields for external knowledge imports.
-- PostgreSQL production migration; existing manually maintained rows remain manual.

ALTER TABLE admin_faq
    ADD COLUMN IF NOT EXISTS source_name VARCHAR(255);

ALTER TABLE admin_faq
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'manual';

UPDATE admin_faq
SET source_type = 'manual'
WHERE source_type IS NULL OR source_type = '';

COMMENT ON COLUMN admin_faq.source_name IS
    'Original file name for externally imported knowledge; NULL for manual entries';
COMMENT ON COLUMN admin_faq.source_type IS
    'Knowledge origin: manual, json, csv, or markdown';
