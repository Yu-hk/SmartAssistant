-- Per-turn prompt/tool audit data for the administration console.
-- NULL keeps historical/uninstrumented turns distinguishable from measured
-- turns that invoked no tools (stored as {"complete":true,"calls":[]}).
ALTER TABLE routing_call_log
    ADD COLUMN IF NOT EXISTS tool_calls TEXT;

COMMENT ON COLUMN routing_call_log.tool_calls IS
    'Argument-free tool telemetry JSON; NULL means not captured';
