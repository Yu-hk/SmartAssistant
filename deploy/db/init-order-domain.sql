BEGIN;

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

CREATE INDEX IF NOT EXISTS idx_orders_order_id ON orders(order_id);
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_request_id
    ON orders(request_id) WHERE request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS order_logistics (
    id              BIGSERIAL PRIMARY KEY,
    tracking_no     VARCHAR(100) NOT NULL UNIQUE,
    order_id        VARCHAR(50) NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    carrier         VARCHAR(50),
    status          VARCHAR(20) DEFAULT 'pending',
    trajectory      TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_logistics_order ON order_logistics(order_id);
CREATE INDEX IF NOT EXISTS idx_logistics_tracking ON order_logistics(tracking_no);

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

CREATE INDEX IF NOT EXISTS idx_refunds_order ON order_refunds(order_id);

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

CREATE INDEX IF NOT EXISTS idx_approval_order
    ON approval_records(order_id, action_type);
CREATE INDEX IF NOT EXISTS idx_approval_status ON approval_records(status);

CREATE TABLE IF NOT EXISTS user_coupons (
    id                BIGSERIAL PRIMARY KEY,
    coupon_id         VARCHAR(50) NOT NULL UNIQUE,
    user_id           BIGINT NOT NULL DEFAULT 1,
    coupon_type       VARCHAR(30) NOT NULL,
    title             VARCHAR(200),
    value             DECIMAL(10,2) NOT NULL,
    condition_amount  DECIMAL(10,2),
    used              BOOLEAN DEFAULT FALSE,
    expire_at         TIMESTAMP,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_coupons_user ON user_coupons(user_id);
CREATE INDEX IF NOT EXISTS idx_coupons_type ON user_coupons(coupon_type);

INSERT INTO orders (
    order_id, user_id, product_name, amount, status, carrier, tracking_no,
    product_type, delivered_date, contact_name, contact_phone,
    shipping_address, payment_method, created_at
) VALUES
    (
        'ORD-2024001', 1, 'iPhone 15 Pro 256GB', 8999.00, '已发货',
        '顺丰速运', 'SF1234567890', '电子产品', NULL, '张三',
        '138****1234', '北京市朝阳区建国路88号', '微信支付',
        '2026-05-10 10:30:00'
    ),
    (
        'ORD-2024002', 1, 'AirPods Pro 第二代', 1999.00, '待发货',
        NULL, NULL, '电子产品', NULL, '张三', '138****1234',
        '北京市朝阳区建国路88号', '支付宝', '2026-05-12 14:00:00'
    ),
    (
        'ORD-2024003', 1, 'MacBook Air M3', 10999.00, '已签收',
        '圆通速递', 'YT987654321', '电子产品', '2026-05-12 14:30:00',
        '张三', '138****1234', '北京市朝阳区建国路88号', '微信支付',
        '2026-05-08 09:15:00'
    )
ON CONFLICT (order_id) DO NOTHING;

INSERT INTO order_logistics (
    tracking_no, order_id, carrier, status, trajectory
) VALUES
    (
        'SF1234567890', 'ORD-2024001', '顺丰速运', 'in_transit',
        '[{"time":"2026-05-15 08:00","location":"北京分拨中心","desc":"到达北京分拨中心，运输中"},{"time":"2026-05-14 18:00","location":"杭州分拨中心","desc":"已揽收"}]'
    ),
    (
        'YT987654321', 'ORD-2024003', '圆通速递', 'delivered',
        '[{"time":"2026-05-12 14:30","location":"北京市朝阳区","desc":"已签收，签收人：前台"}]'
    )
ON CONFLICT (tracking_no) DO NOTHING;

COMMIT;
