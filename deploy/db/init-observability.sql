BEGIN;

CREATE TABLE IF NOT EXISTS routing_call_log (
    id                     BIGSERIAL PRIMARY KEY,
    request_id             VARCHAR(128),
    session_id             VARCHAR(128),
    user_input             TEXT,
    routed_agent           VARCHAR(100),
    route_method           VARCHAR(50),
    match_score            DECIMAL(8,6),
    matched_rule_id        BIGINT,
    llm_received_question  TEXT,
    response_summary       VARCHAR(500),
    latency_ms             BIGINT,
    status                 VARCHAR(20) DEFAULT 'SUCCESS',
    error_message          TEXT,
    created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS request_id VARCHAR(128);
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS session_id VARCHAR(128);
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS user_input TEXT;
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS routed_agent VARCHAR(100);
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS route_method VARCHAR(50);
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS match_score DECIMAL(8,6);
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS matched_rule_id BIGINT;
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS llm_received_question TEXT;
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS response_summary VARCHAR(500);
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS latency_ms BIGINT;
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'SUCCESS';
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS idx_routing_call_log_request_id
    ON routing_call_log(request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_routing_call_log_session_id
    ON routing_call_log(session_id);
CREATE INDEX IF NOT EXISTS idx_routing_call_log_routed_agent
    ON routing_call_log(routed_agent);
CREATE INDEX IF NOT EXISTS idx_routing_call_log_status
    ON routing_call_log(status);
CREATE INDEX IF NOT EXISTS idx_routing_call_log_created_at
    ON routing_call_log(created_at);

CREATE TABLE IF NOT EXISTS conversation_feedback (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(128),
    user_id         BIGINT,
    rating          INTEGER CHECK (rating >= 1 AND rating <= 5),
    feedback_text   TEXT,
    intent_tag      VARCHAR(50),
    agent_name      VARCHAR(100),
    metadata        JSONB,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS session_id VARCHAR(128);
ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS rating INTEGER;
ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS feedback_text TEXT;
ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS intent_tag VARCHAR(50);
ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS agent_name VARCHAR(100);
ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS metadata JSONB;
ALTER TABLE conversation_feedback ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_feedback_user
    ON conversation_feedback(user_id);
CREATE INDEX IF NOT EXISTS idx_feedback_session
    ON conversation_feedback(session_id);
CREATE INDEX IF NOT EXISTS idx_feedback_rating
    ON conversation_feedback(rating);
CREATE INDEX IF NOT EXISTS idx_feedback_agent
    ON conversation_feedback(agent_name);
CREATE INDEX IF NOT EXISTS idx_feedback_created
    ON conversation_feedback(created_at);

COMMIT;
