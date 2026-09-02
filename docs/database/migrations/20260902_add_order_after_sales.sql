-- Generic after-sales requests created by the approved deterministic order workflow.
CREATE TABLE IF NOT EXISTS order_after_sales (
    id              BIGSERIAL PRIMARY KEY,
    request_id      VARCHAR(128) NOT NULL UNIQUE,
    order_id        VARCHAR(50) NOT NULL REFERENCES orders(order_id),
    user_id         BIGINT NOT NULL,
    request_type    VARCHAR(30) NOT NULL,
    reason          TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_after_sales_status CHECK (
        status IN ('pending', 'processing', 'approved', 'rejected',
                   'completed', 'cancelled')
    )
);

CREATE INDEX IF NOT EXISTS idx_after_sales_order_id ON order_after_sales(order_id);
CREATE INDEX IF NOT EXISTS idx_after_sales_user_id ON order_after_sales(user_id);
