-- Additive visit collection; no historical API logs are converted to page views.
CREATE TABLE IF NOT EXISTS site_visit_event (
    event_id VARCHAR(36) PRIMARY KEY,
    visitor_id VARCHAR(36) NOT NULL,
    user_id BIGINT,
    user_role VARCHAR(32) NOT NULL,
    module_code VARCHAR(64) NOT NULL,
    event_kind VARCHAR(24) NOT NULL,
    browser VARCHAR(32) NOT NULL,
    device VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_site_visit_time ON site_visit_event(created_at);
CREATE INDEX IF NOT EXISTS idx_site_visit_visitor ON site_visit_event(visitor_id, created_at);
CREATE INDEX IF NOT EXISTS idx_site_visit_module_time ON site_visit_event(module_code, created_at);
