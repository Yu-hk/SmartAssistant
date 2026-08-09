-- PostgreSQL: provider-reported per-turn token usage for administration monitoring.
--
-- This migration is intentionally nullable and idempotent:
--   * NULL means historical/unknown telemetry.
--   * 0 means a measured zero-token turn (for example, an explicit no-LLM path).
-- Existing rows remain NULL; no estimate or destructive backfill is performed.

ALTER TABLE routing_call_log
    ADD COLUMN IF NOT EXISTS prompt_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS completion_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS total_tokens BIGINT;

COMMENT ON COLUMN routing_call_log.prompt_tokens IS
    'Provider-reported input tokens for this turn; NULL means not captured';
COMMENT ON COLUMN routing_call_log.completion_tokens IS
    'Provider-reported output tokens for this turn; NULL means not captured';
COMMENT ON COLUMN routing_call_log.total_tokens IS
    'Provider-reported total tokens for this turn; NULL means not captured, 0 is measured zero';
