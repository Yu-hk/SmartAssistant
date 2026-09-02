-- ============================================================
-- SmartAssistant 数据库初始化脚本
-- 基于项目 Java 实体 / MyBatis Plus 注解生成
-- 生成日期: 2026-07-10
-- ============================================================

-- ══════════════════════════════════════════════════════════════
-- 扩展
-- ══════════════════════════════════════════════════════════════
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA public;

-- ══════════════════════════════════════════════════════════════
-- 1. 用户表 (User.java)
--    smart-assistant-user 模块 / MyBatis Plus
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(100) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    email       VARCHAR(255),
    role        VARCHAR(50) NOT NULL DEFAULT 'ROLE_USER',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 第三方登录身份映射；平台身份与本地 users 解耦，禁止按未验证邮箱自动合并。
CREATE TABLE IF NOT EXISTS user_external_identities (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider     VARCHAR(20) NOT NULL,
    subject      VARCHAR(191) NOT NULL,
    union_id     VARCHAR(191),
    display_name VARCHAR(200),
    avatar_url   TEXT,
    email        VARCHAR(255),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_external_identity_subject UNIQUE (provider, subject)
);
CREATE INDEX IF NOT EXISTS idx_external_identity_user_id
    ON user_external_identities(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_identity_union_id
    ON user_external_identities(provider, union_id) WHERE union_id IS NOT NULL;

-- ══════════════════════════════════════════════════════════════
-- 2. 用户会话表 (UserSession.java)
--    smart-assistant-user 模块 / MyBatis Plus @TableName("user_sessions")
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS user_sessions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_id        VARCHAR(100) NOT NULL UNIQUE,
    device_info     JSONB,
    ip_address      VARCHAR(50),
    user_agent      TEXT,
    is_active       BOOLEAN DEFAULT TRUE,
    is_revoked      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_active_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP,
    revoked_at      TIMESTAMP
);
CREATE INDEX idx_user_sessions_token ON user_sessions(token_id);
CREATE INDEX idx_user_sessions_user ON user_sessions(user_id);

-- ══════════════════════════════════════════════════════════════
-- 3. 订单表 (OrderEntity.java)
--    smart-assistant-order 模块 / MyBatis Plus @TableName("orders")
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS orders (
    id                BIGSERIAL PRIMARY KEY,
    order_id          VARCHAR(50) NOT NULL UNIQUE,
    user_id           BIGINT NOT NULL DEFAULT 1,
    product_name      VARCHAR(200) NOT NULL,
    amount            DECIMAL(10,2) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT '待付款',
    carrier           VARCHAR(50),
    tracking_no       VARCHAR(100),
    product_type      VARCHAR(50),
    delivered_date    TIMESTAMP,
    contact_name      VARCHAR(100),
    contact_phone     VARCHAR(30),
    shipping_address  TEXT,
    payment_method    VARCHAR(50),
    request_id        VARCHAR(100),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_orders_order_id ON orders(order_id);
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);

-- ══════════════════════════════════════════════════════════════
-- 4. 物流轨迹表 (OrderLogisticsEntity.java)
--    smart-assistant-order 模块 / MyBatis Plus @TableName("order_logistics")
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS order_logistics (
    id              BIGSERIAL PRIMARY KEY,
    tracking_no     VARCHAR(100) NOT NULL,
    order_id        VARCHAR(50) NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    carrier         VARCHAR(50),
    status          VARCHAR(20) DEFAULT 'pending',
    trajectory      TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_logistics_order ON order_logistics(order_id);
CREATE INDEX idx_logistics_tracking ON order_logistics(tracking_no);

-- ══════════════════════════════════════════════════════════════
-- 5. 退款记录表 (OrderRefundEntity.java)
--    smart-assistant-order 模块 / MyBatis Plus @TableName("order_refunds")
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS order_refunds (
    id          BIGSERIAL PRIMARY KEY,
    order_id    VARCHAR(50) NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    reason      TEXT NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_by  VARCHAR(100),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_refunds_order ON order_refunds(order_id);

-- ══════════════════════════════════════════════════════════════
-- 6. 审批记录表 (ApprovalRecordEntity.java)
--    smart-assistant-order 模块 / MyBatis Plus @TableName("approval_records")
--    状态机：PENDING → CONFIRMED → CONSUMED
--                        ↘ CANCELLED
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS approval_records (
    id            BIGSERIAL PRIMARY KEY,
    order_id      VARCHAR(50) NOT NULL,
    action_type   VARCHAR(50) NOT NULL,
    reason        TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    operator      VARCHAR(100),
    operator_ip   VARCHAR(50),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirmed_at  TIMESTAMP,
    consumed_at   TIMESTAMP
);
CREATE INDEX idx_approval_order ON approval_records(order_id, action_type);
CREATE INDEX idx_approval_status ON approval_records(status);

-- ══════════════════════════════════════════════════════════════
-- 7. 优惠券表 (CouponEntity.java)
--    smart-assistant-order 模块 / MyBatis Plus @TableName("user_coupons")
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS user_coupons (
    id                BIGSERIAL PRIMARY KEY,
    coupon_id         VARCHAR(50) NOT NULL UNIQUE,
    user_id           BIGINT NOT NULL DEFAULT 1,
    coupon_type       VARCHAR(30) NOT NULL,   -- FULL_REDUCTION / DISCOUNT / CASH
    title             VARCHAR(200),
    value             DECIMAL(10,2) NOT NULL,
    condition_amount  DECIMAL(10,2),
    used              BOOLEAN DEFAULT FALSE,
    expire_at         TIMESTAMP,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_coupons_user ON user_coupons(user_id);
CREATE INDEX idx_coupons_type ON user_coupons(coupon_type);

-- ══════════════════════════════════════════════════════════════
-- 8. 路由调用日志表 (RoutingCallLog.java)
--    smart-assistant-consumer 模块 / MyBatis Plus @TableName("routing_call_log")
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS routing_call_log (
    id                     BIGSERIAL PRIMARY KEY,
    session_id             VARCHAR(100),
    user_id                BIGINT,
    user_input             TEXT,
    routed_agent           VARCHAR(100),
    route_method           VARCHAR(50),       -- keyword_match / semantic / llm_fallback
    match_score            DECIMAL(5,4),
    matched_rule_id        BIGINT,
    llm_received_question  TEXT,
    response_summary       VARCHAR(500),
    latency_ms             BIGINT,
    prompt_tokens          BIGINT,            -- NULL = usage telemetry not captured
    completion_tokens      BIGINT,            -- measured zero remains 0
    total_tokens           BIGINT,
    tool_calls             TEXT,              -- JSON telemetry; NULL = not captured
    status                 VARCHAR(20) DEFAULT 'SUCCESS',  -- SUCCESS / FAILED / TIMEOUT
    error_message          TEXT,
    created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_routing_log_session ON routing_call_log(session_id);
CREATE INDEX idx_routing_log_user_created ON routing_call_log(user_id, created_at DESC);
CREATE INDEX idx_routing_log_user_session ON routing_call_log(user_id, session_id);
CREATE INDEX idx_routing_log_agent ON routing_call_log(routed_agent);
CREATE INDEX idx_routing_log_created ON routing_call_log(created_at);

-- ══════════════════════════════════════════════════════════════
-- 9. 反馈评价表 (无实体, 被 AdminService/HybridDataQueryService 引用)
--    conversation_feedback — 对话反馈记录
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS conversation_feedback (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(100),
    user_id         BIGINT,
    rating          INTEGER CHECK (rating >= 1 AND rating <= 5),
    feedback_text   TEXT,
    intent_tag      VARCHAR(50),
    agent_name      VARCHAR(100),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_feedback_user ON conversation_feedback(user_id);
CREATE INDEX idx_feedback_session ON conversation_feedback(session_id);
CREATE INDEX idx_feedback_rating ON conversation_feedback(rating);

-- Conversation-level state is separate from routing_call_log.status, which
-- describes a single model/tool invocation rather than the whole conversation.
CREATE TABLE IF NOT EXISTS conversation_session_state (
    user_id      BIGINT NOT NULL,
    session_id   VARCHAR(100) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE_IDLE',
    closed_at    TIMESTAMP NULL,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id)
);
CREATE INDEX IF NOT EXISTS idx_conversation_session_state_status
    ON conversation_session_state(status, updated_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_one_active_per_user
    ON conversation_session_state(user_id)
    WHERE status IN ('ACTIVE_IDLE', 'ACTIVE_RUNNING');
CREATE INDEX IF NOT EXISTS idx_conversation_suspended_fifo
    ON conversation_session_state(user_id, updated_at)
    WHERE status = 'SUSPENDED';

-- Persistent FAQ / knowledge entries used by the administration console.
CREATE TABLE IF NOT EXISTS admin_faq (
    id           BIGSERIAL PRIMARY KEY,
    category     VARCHAR(50) NOT NULL DEFAULT 'general',
    question     VARCHAR(500) NOT NULL UNIQUE,
    answer       TEXT NOT NULL,
    keywords     VARCHAR(1000),
    source_name  VARCHAR(255),
    source_type  VARCHAR(32) NOT NULL DEFAULT 'manual',
    hit_count    BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ══════════════════════════════════════════════════════════════
-- 10. 商品表 (无实体, 被 TextToSqlTool 引用 / InMemoryProductBackend 提供数据)
--     products — 商品信息表
-- ══════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS products (
    id              BIGSERIAL PRIMARY KEY,
    product_code    VARCHAR(50) NOT NULL UNIQUE,
    product_name    VARCHAR(200) NOT NULL,
    price           DECIMAL(10,2) NOT NULL,
    stock           VARCHAR(20) NOT NULL DEFAULT '充足',
    category        VARCHAR(50) NOT NULL DEFAULT '其他',
    spec            TEXT,
    color           VARCHAR(200),
    market_price    DECIMAL(10,2),
    sales_30d       BIGINT NOT NULL DEFAULT 0,
    rating          DECIMAL(2,1),
    review_count    BIGINT NOT NULL DEFAULT 0,
    metrics_source  VARCHAR(32) NOT NULL DEFAULT 'catalog',
    metrics_updated_at TIMESTAMP,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_products_code ON products(product_code);

-- Versioned e-commerce user profile snapshot.
CREATE TABLE IF NOT EXISTS user_profile_snapshot (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    profile_version BIGINT NOT NULL DEFAULT 1 CHECK (profile_version > 0),
    schema_version VARCHAR(64) NOT NULL,
    report JSONB NOT NULL,
    reliable BOOLEAN NOT NULL DEFAULT FALSE,
    purchase_stage VARCHAR(32),
    purchase_intent_score SMALLINT CHECK (
        purchase_intent_score IS NULL OR purchase_intent_score BETWEEN 0 AND 100
    ),
    churn_risk VARCHAR(16),
    source_max_message_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_profile_snapshot_stage
    ON user_profile_snapshot(purchase_stage, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_profile_snapshot_churn
    ON user_profile_snapshot(churn_risk, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_profile_snapshot_report_gin
    ON user_profile_snapshot USING GIN(report);

-- Immutable audit trail for material profile changes.
CREATE TABLE IF NOT EXISTS user_profile_change_log (
    event_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id VARCHAR(128),
    base_version BIGINT NOT NULL CHECK (base_version >= 0),
    new_version BIGINT NOT NULL CHECK (new_version > 0),
    action VARCHAR(16) NOT NULL CHECK (action IN ('CREATE', 'UPDATE')),
    changed_fields JSONB NOT NULL DEFAULT '[]'::JSONB,
    removed_fields JSONB NOT NULL DEFAULT '[]'::JSONB,
    reason TEXT,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::JSONB,
    model_name VARCHAR(128),
    prompt_version VARCHAR(32) NOT NULL,
    before_hash VARCHAR(64),
    after_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_profile_change_log_user
    ON user_profile_change_log(user_id, new_version DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_profile_change_log_request
    ON user_profile_change_log(user_id, request_id) WHERE request_id IS NOT NULL;
