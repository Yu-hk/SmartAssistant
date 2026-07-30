-- Scope shared pgvector rows by logical knowledge-base name.
-- Existing rows remain under "default"; applications explicitly seed their named base.
ALTER TABLE knowledge_docs
    ADD COLUMN IF NOT EXISTS knowledge_base VARCHAR(128) NOT NULL DEFAULT 'default';

CREATE INDEX IF NOT EXISTS idx_knowledge_base
    ON knowledge_docs (knowledge_base);
