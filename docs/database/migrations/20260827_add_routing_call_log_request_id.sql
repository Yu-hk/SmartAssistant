-- Associate each persisted conversation turn with its independent workflow execution.
ALTER TABLE routing_call_log
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_routing_call_log_request_id
    ON routing_call_log(request_id);

COMMENT ON COLUMN routing_call_log.request_id IS
    'Per-turn Router/LangGraph workflow execution id; null only for legacy rows';
