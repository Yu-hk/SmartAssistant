-- Additive, idempotent admin-console persistence.
-- Safe to execute repeatedly before deploying the consumer service.

CREATE TABLE IF NOT EXISTS conversation_session_state (
    user_id      BIGINT NOT NULL,
    session_id   VARCHAR(100) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    closed_at    TIMESTAMP NULL,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_conversation_session_state_status
    ON conversation_session_state(status, updated_at);

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

INSERT INTO admin_faq (category, question, answer, keywords)
SELECT 'order', '怎么查询我的订单？',
       '请提供您的订单号（格式：ORD-xxx），我可以帮您查询订单状态和物流信息。',
       '订单查询,订单状态,物流'
WHERE NOT EXISTS (SELECT 1 FROM admin_faq WHERE question = '怎么查询我的订单？');

INSERT INTO admin_faq (category, question, answer, keywords)
SELECT 'order', '如何申请退款？',
       '请提供订单号，我可以帮您查询退款政策和流程。',
       '退款,退货,取消订单'
WHERE NOT EXISTS (SELECT 1 FROM admin_faq WHERE question = '如何申请退款？');

INSERT INTO admin_faq (category, question, answer, keywords)
SELECT 'product', '如何查询商品信息？',
       '请告诉我商品名称或编码，我可以帮您查询商品详情、价格和库存情况。',
       '商品查询,商品信息,价格'
WHERE NOT EXISTS (SELECT 1 FROM admin_faq WHERE question = '如何查询商品信息？');

INSERT INTO admin_faq (category, question, answer, keywords)
SELECT 'general', '你们有哪些服务？',
'我可以帮助查询订单、商品信息和常见问题。',
       '服务,功能,帮助'
WHERE NOT EXISTS (SELECT 1 FROM admin_faq WHERE question = '你们有哪些服务？');
