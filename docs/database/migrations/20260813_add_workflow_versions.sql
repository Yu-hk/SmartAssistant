-- Versioned workflow definitions consumed by the Router's LangGraph4j compiler.
-- Published rows are immutable snapshots. A partial unique index guarantees one
-- active published version per workflow key.

CREATE TABLE IF NOT EXISTS public.workflow_versions (
    workflow_key varchar(64) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    definition jsonb NOT NULL,
    checksum char(64),
    created_by bigint,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by bigint,
    published_at timestamp without time zone,
    CONSTRAINT workflow_versions_pkey PRIMARY KEY (workflow_key, version),
    CONSTRAINT workflow_versions_publish_metadata_ck CHECK (
        (status = 'PUBLISHED' AND checksum IS NOT NULL AND published_at IS NOT NULL)
        OR status <> 'PUBLISHED'
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_versions_one_published
    ON public.workflow_versions (workflow_key)
    WHERE status = 'PUBLISHED';

CREATE INDEX IF NOT EXISTS idx_workflow_versions_status
    ON public.workflow_versions (status, created_at DESC);
