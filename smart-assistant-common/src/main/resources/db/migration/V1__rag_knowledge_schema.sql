-- ============================================================================
-- SmartAssistant RAG 生产化改造 — Flyway 迁移 V1
-- 复用 a2a_system 库（同 PG 实例，不新建库）。
-- 首行必须启用 pgvector 扩展；随后建 4 张表：
--   knowledge_docs          （扩展既有列）
--   knowledge_index_meta    （active_index_version 单行）
--   knowledge_review_queue  （脏数据/废件复核队列）
--   compliance_audit_log    （生成后合规校验审计）
-- ============================================================================

-- 启用 pgvector 扩展（幂等）
CREATE EXTENSION IF NOT EXISTS vector;

-- ───────────────────────── knowledge_docs ─────────────────────────
-- 向量维度由运行时 BGE 模型动态决定（bge-large-zh-v1.5 = 1024）。
-- 这里使用 vector(1024) 作为默认占位；若实际模型维度不同，
-- PgVectorKnowledgeBase.initSchema() 会在应用启动时按模型维度重建/迁移。
CREATE TABLE IF NOT EXISTS knowledge_docs (
    id               VARCHAR(128) PRIMARY KEY,
    title            TEXT        NOT NULL,
    content          TEXT        NOT NULL,
    category         VARCHAR(64),
    keywords         TEXT,
    effective_at     BIGINT      DEFAULT -1,
    expire_at        BIGINT      DEFAULT -1,
    tenant_id        VARCHAR(64) DEFAULT '',
    version          VARCHAR(32) DEFAULT 'v1',
    source_url       VARCHAR(1024) DEFAULT '',
    chunk_index      INT         DEFAULT -1,
    authority_level  INT         DEFAULT 3,
    document_status  VARCHAR(16) DEFAULT 'ACTIVE',
    security_level   INT         DEFAULT 0,
    authorized_roles TEXT[]      DEFAULT '{}',
    authorized_users TEXT[]      DEFAULT '{}',
    parent_doc_id    VARCHAR(128) DEFAULT '',
    source_type      VARCHAR(32) DEFAULT '',
    raw_checksum     VARCHAR(128) DEFAULT '',
    ingest_batch_id  VARCHAR(64) DEFAULT '',
    index_version    VARCHAR(32) DEFAULT 'v1',
    embedding        vector(1024),
    created_at       BIGINT      NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_hnsw
    ON knowledge_docs USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_knowledge_tenant_acl
    ON knowledge_docs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_acl_roles
    ON knowledge_docs USING gin (authorized_roles);
CREATE INDEX IF NOT EXISTS idx_knowledge_acl_users
    ON knowledge_docs USING gin (authorized_users);
CREATE INDEX IF NOT EXISTS idx_knowledge_index_version
    ON knowledge_docs (index_version);

-- ─────────────────────── knowledge_index_meta ───────────────────────
CREATE TABLE IF NOT EXISTS knowledge_index_meta (
    id                     INT    PRIMARY KEY DEFAULT 1,
    active_index_version   VARCHAR(32) NOT NULL DEFAULT 'v1',
    bumped_at              BIGINT NOT NULL DEFAULT 0
);

INSERT INTO knowledge_index_meta (id, active_index_version, bumped_at)
VALUES (1, 'v1', 0)
ON CONFLICT (id) DO NOTHING;

-- ────────────────────── knowledge_review_queue ──────────────────────
CREATE TABLE IF NOT EXISTS knowledge_review_queue (
    id           VARCHAR(64) PRIMARY KEY,
    raw_payload  JSONB,
    reason       TEXT,
    source_type  VARCHAR(32) DEFAULT '',
    submitted_by VARCHAR(128) DEFAULT '',
    status       VARCHAR(16) DEFAULT 'REVIEW',   -- REVIEW / APPROVED / REJECTED
    reviewed_by  VARCHAR(128),
    reviewed_at  BIGINT,
    created_at   BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_review_queue_status
    ON knowledge_review_queue (status);

-- ────────────────────── compliance_audit_log ───────────────────────
CREATE TABLE IF NOT EXISTS compliance_audit_log (
    id                VARCHAR(64) PRIMARY KEY,
    rule_id           VARCHAR(32),
    severity          VARCHAR(16),
    strategy_applied  VARCHAR(16),
    original_snippet  TEXT,
    rewritten_snippet TEXT,
    tenant_id         VARCHAR(64) DEFAULT '',
    created_at        BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_compliance_audit_created
    ON compliance_audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_compliance_audit_rule
    ON compliance_audit_log (rule_id);
