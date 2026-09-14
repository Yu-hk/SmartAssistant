-- Nullable evidence fields; do not backfill unverified specifications from prose/demo values.
-- Safe to re-run. Deploy explicitly before loading verified catalog facts.
BEGIN;
ALTER TABLE products ADD COLUMN IF NOT EXISTS weight_grams NUMERIC(10, 3) CHECK (weight_grams > 0);
ALTER TABLE products ADD COLUMN IF NOT EXISTS battery_life_hours NUMERIC(8, 2) CHECK (battery_life_hours > 0);
ALTER TABLE products ADD COLUMN IF NOT EXISTS battery_life_scenario VARCHAR(32)
    CHECK (battery_life_scenario IN ('video_playback', 'audio_anc_on', 'audio_anc_off', 'mixed_use'));
ALTER TABLE products ADD COLUMN IF NOT EXISTS noise_cancelling BOOLEAN;
ALTER TABLE products ADD COLUMN IF NOT EXISTS feature_source TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS features_verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE products ADD COLUMN IF NOT EXISTS features_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS features_updated_by BIGINT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS features_updated_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN products.weight_grams IS 'Device net weight in grams; earbuds mean pair, without charging case, packaging or accessories. NULL means unknown.';
COMMENT ON COLUMN products.battery_life_hours IS 'Documented nominal runtime in hours for battery_life_scenario, not battery capacity or charging-case combined runtime. NULL means unknown.';
COMMENT ON COLUMN products.battery_life_scenario IS 'video_playback/audio_anc_on/audio_anc_off/mixed_use. Different scenarios must not be compared.';
COMMENT ON COLUMN products.noise_cancelling IS 'Active noise cancellation; NULL unknown, false explicitly unsupported. Not call-noise reduction.';
COMMENT ON COLUMN products.feature_source IS 'Evidence reference, e.g. vendor specification URL or verified catalog record; never inferred by the model.';
COMMENT ON COLUMN products.features_verified_at IS 'Time the specification source was verified; not a guarantee of real-world battery runtime.';
COMMENT ON COLUMN products.features_revision IS 'Optimistic concurrency version for full feature replacement; initially 0.';
COMMENT ON COLUMN products.features_updated_by IS 'Administrator user ID supplied by the authenticated gateway, never by the request body.';
COMMENT ON COLUMN products.features_updated_at IS 'Server-side time of the last parameter save, distinct from source verification time.';
COMMIT;
