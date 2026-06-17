-- ============================================================
-- SmartAssistant Order Module Schema
-- 订单模块数据库表定义
-- 执行方式: psql -U postgres -d a2a_system -f order-schema.sql
-- ============================================================

-- -----------------------------
-- 1. 扩展 orders 表
--    添加完整电商订单所需的字段
--    注意: orders 表在 seed_data.sql 中已定义，此处仅做 ALTER
-- -----------------------------
DO $$
BEGIN
    -- 添加 product_type 字段（如果不存在）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
        AND column_name = 'product_type'
    ) THEN
        ALTER TABLE orders ADD COLUMN product_type VARCHAR(50) DEFAULT '';
    END IF;

    -- 添加 delivered_date 字段（如果不存在）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
        AND column_name = 'delivered_date'
    ) THEN
        ALTER TABLE orders ADD COLUMN delivered_date TIMESTAMP;
    END IF;

    -- ⭐ 新增：收货人姓名
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
        AND column_name = 'contact_name'
    ) THEN
        ALTER TABLE orders ADD COLUMN contact_name VARCHAR(100) DEFAULT '';
    END IF;

    -- ⭐ 新增：收货人电话
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
        AND column_name = 'contact_phone'
    ) THEN
        ALTER TABLE orders ADD COLUMN contact_phone VARCHAR(20) DEFAULT '';
    END IF;

    -- ⭐ 新增：收货地址
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
        AND column_name = 'shipping_address'
    ) THEN
        ALTER TABLE orders ADD COLUMN shipping_address TEXT DEFAULT '';
    END IF;

    -- ⭐ 新增：支付方式
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
        AND column_name = 'payment_method'
    ) THEN
        ALTER TABLE orders ADD COLUMN payment_method VARCHAR(50) DEFAULT '';
    END IF;

    -- ⭐ 新增：更新时间
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
        AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE orders ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
    END IF;
END $$;

-- -----------------------------
-- 2. 退款记录表 order_refunds
-- -----------------------------
CREATE TABLE IF NOT EXISTS order_refunds (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(50) NOT NULL REFERENCES orders(order_id),
    reason          TEXT NOT NULL,
    amount          DECIMAL(10,2),
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_by      VARCHAR(50) DEFAULT 'system',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_refund_status CHECK (
        status IN ('pending', 'approved', 'rejected', 'completed')
    )
);

COMMENT ON TABLE order_refunds IS '退款记录表 - 记录每一笔退款申请的详细信息';
COMMENT ON COLUMN order_refunds.status IS '状态: pending=待审核, approved=已批准, rejected=已拒绝, completed=已完成(已打款)';

-- -----------------------------
-- 3. 物流轨迹表 order_logistics
-- -----------------------------
CREATE TABLE IF NOT EXISTS order_logistics (
    id              BIGSERIAL PRIMARY KEY,
    tracking_no     VARCHAR(100) NOT NULL UNIQUE,
    order_id        VARCHAR(50) REFERENCES orders(order_id),
    carrier         VARCHAR(50) NOT NULL DEFAULT '',
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    trajectory      TEXT DEFAULT '[]',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_logistics_status CHECK (
        status IN ('pending', 'shipped', 'in_transit', 'delivered', 'returned')
    )
);

COMMENT ON TABLE order_logistics IS '物流轨迹表 - 存储快递物流轨迹信息';
COMMENT ON COLUMN order_logistics.trajectory IS '物流轨迹 JSON 数组，格式: [{"time":"...","location":"...","desc":"..."}]';

CREATE INDEX IF NOT EXISTS idx_logistics_tracking_no ON order_logistics(tracking_no);
CREATE INDEX IF NOT EXISTS idx_logistics_order_id ON order_logistics(order_id);

-- -----------------------------
-- 4. 审批记录表 approval_records
--    用于 ApprovalService 持久化
-- -----------------------------
CREATE TABLE IF NOT EXISTS approval_records (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(50) NOT NULL,
    action_type     VARCHAR(50) NOT NULL,
    reason          TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirmed_at    TIMESTAMP,
    consumed_at     TIMESTAMP,
    CONSTRAINT chk_approval_status CHECK (
        status IN ('pending', 'confirmed', 'consumed', 'cancelled')
    )
);

COMMENT ON TABLE approval_records IS '审批记录表 - 敏感操作二阶段确认持久化';
COMMENT ON COLUMN approval_records.status IS '状态: pending=待确认, confirmed=已确认待消费, consumed=已消费, cancelled=已取消';

CREATE INDEX IF NOT EXISTS idx_approval_order_action ON approval_records(order_id, action_type);

-- -----------------------------
-- 5. 更新 seed_data 中的现有数据
--    为已有的 orders 补充 product_type, delivered_date 和新字段
-- -----------------------------
UPDATE orders SET product_type = '电子产品',   delivered_date = NULL,
    contact_name='张三', contact_phone='138****1234', shipping_address='北京市朝阳区建国路88号',
    payment_method='微信支付' WHERE order_id = 'ORD-2024001';
UPDATE orders SET product_type = '电子产品',   delivered_date = NULL,
    contact_name='张三', contact_phone='138****1234', shipping_address='北京市朝阳区建国路88号',
    payment_method='支付宝' WHERE order_id = 'ORD-2024002';
UPDATE orders SET product_type = '电子产品',   delivered_date = '2026-05-12'::TIMESTAMP,
    contact_name='张三', contact_phone='138****1234', shipping_address='北京市朝阳区建国路88号',
    payment_method='微信支付' WHERE order_id = 'ORD-2024003';
UPDATE orders SET product_type = '电子产品',   delivered_date = NULL,
    contact_name='张三', contact_phone='138****1234', shipping_address='北京市朝阳区建国路88号',
    payment_method='支付宝' WHERE order_id = 'ORD-2024004';
UPDATE orders SET product_type = '电子产品',   delivered_date = NULL,
    contact_name='张三', contact_phone='138****1234', shipping_address='北京市朝阳区建国路88号',
    payment_method='微信支付' WHERE order_id = 'ORD-2024005';

-- -----------------------------
-- 6. 物流轨迹示例数据
-- -----------------------------
INSERT INTO order_logistics (tracking_no, order_id, carrier, status, trajectory)
VALUES
('SF1234567890', 'ORD-2024001', '顺丰速运', 'in_transit',
 '[{"time":"2026-05-15 08:00","location":"北京分拨中心","desc":"到达 北京分拨中心，运输中"},
   {"time":"2026-05-14 22:00","location":"杭州分拨中心","desc":"离开 杭州分拨中心"},
   {"time":"2026-05-14 18:00","location":"杭州分拨中心","desc":"已揽收，包裹已被顺丰速运收取"}]'),

('YT987654321', 'ORD-2024003', '圆通速递', 'delivered',
 '[{"time":"2026-05-12 14:30","location":"北京朝阳区","desc":"已签收，签收人：前台"},
   {"time":"2026-05-12 09:00","location":"北京朝阳配送站","desc":"已到达 北京朝阳配送站，配送中"},
   {"time":"2026-05-11 20:00","location":"上海分拨中心","desc":"离开 上海分拨中心"},
   {"time":"2026-05-11 14:00","location":"上海分拨中心","desc":"已到达 上海分拨中心"},
   {"time":"2026-05-10 18:00","location":"深圳分拨中心","desc":"已揽收，包裹已被圆通速递收取"}]'),

('ZT123456789', 'ORD-C001', '中通快递', 'delivered',
 '[{"time":"2026-05-05 16:00","location":"北京海淀区","desc":"已签收，签收人：本人"},
   {"time":"2026-05-05 10:00","location":"北京海淀配送站","desc":"已到达 北京海淀配送站，配送中"},
   {"time":"2026-05-04 08:00","location":"广州分拨中心","desc":"离开 广州分拨中心"},
   {"time":"2026-05-03 20:00","location":"广州分拨中心","desc":"已到达 广州分拨中心"},
   {"time":"2026-05-03 14:00","location":"广州","desc":"已揽收"}]'),

('JD987654321', 'ORD-F001', '京东物流', 'delivered',
 '[{"time":"2026-05-11 11:00","location":"北京市","desc":"已签收，签收人：本人"},
   {"time":"2026-05-11 08:00","location":"北京通州分拣中心","desc":"已到达 北京通州分拣中心"},
   {"time":"2026-05-10 22:00","location":"天津分拣中心","desc":"离开 天津分拣中心"},
   {"time":"2026-05-10 18:00","location":"天津分拣中心","desc":"已到达 天津分拣中心"},
   {"time":"2026-05-10 14:00","location":"天津","desc":"已揽收"}]');

-- 注意：ORD-2024002 和 ORD-2024005 暂无物流信息

-- -----------------------------
-- 7. 更新序列
-- -----------------------------
SELECT setval('order_refunds_id_seq', COALESCE((SELECT MAX(id) FROM order_refunds), 1));
SELECT setval('order_logistics_id_seq', COALESCE((SELECT MAX(id) FROM order_logistics), 1));
SELECT setval('approval_records_id_seq', COALESCE((SELECT MAX(id) FROM approval_records), 1));
