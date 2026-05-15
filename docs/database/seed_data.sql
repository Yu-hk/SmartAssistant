-- ============================================================
-- SmartAssistant 客户服务示例数据
-- 注意：数据已迁移至 customer service 场景
-- 原 travel/attraction 数据已移除
-- ============================================================

-- -----------------------------
-- 重建基础表（如果不存在）
-- -----------------------------

-- 订单表
CREATE TABLE IF NOT EXISTS orders (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(50) NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL DEFAULT 1,
    product_name    VARCHAR(200) NOT NULL,
    amount          DECIMAL(10,2) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    carrier         VARCHAR(50),
    tracking_no     VARCHAR(100),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 商品表
CREATE TABLE IF NOT EXISTS products (
    id              BIGSERIAL PRIMARY KEY,
    product_code    VARCHAR(50) NOT NULL UNIQUE,
    product_name    VARCHAR(200) NOT NULL,
    price           DECIMAL(10,2) NOT NULL,
    stock           VARCHAR(20) NOT NULL DEFAULT '充足',
    spec            TEXT,
    colors          VARCHAR(200),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------
-- 示例订单数据
-- -----------------------------
INSERT INTO orders (order_id, user_id, product_name, amount, status, carrier, tracking_no, created_at)
VALUES
('ORD-2024001', 1, 'iPhone 15 Pro 256GB', 8999.00, '已发货', '顺丰速运', 'SF1234567890', '2026-05-10 10:30:00'),
('ORD-2024002', 1, 'AirPods Pro 第二代', 1999.00, '待发货', '', '', '2026-05-12 14:00:00'),
('ORD-2024003', 1, 'MacBook Air M3', 10999.00, '已签收', '圆通速递', 'YT987654321', '2026-05-08 09:15:00'),
('ORD-2024004', 1, 'Apple Watch Series 9', 3199.00, '退款中', '', '', '2026-05-13 16:45:00'),
('ORD-2024005', 1, 'iPad Air M2', 4799.00, '待付款', '', '', '2026-05-14 11:20:00');

-- -----------------------------
-- 示例商品数据
-- -----------------------------
INSERT INTO products (product_code, product_name, price, stock, spec, colors)
VALUES
('IPHONE-15-PRO',  'iPhone 15 Pro',      8999.00,  '充足', 'A17 Pro芯片、4800万像素、钛金属边框', '原色钛金属/蓝色钛金属/白色钛金属/黑色钛金属'),
('IPHONE-15',      'iPhone 15',          6999.00,  '充足', 'A16芯片、4800万像素、灵动岛', '粉色/黄色/绿色/蓝色/黑色'),
('AIRPODS-PRO',    'AirPods Pro 第二代', 1999.00,  '充足', '降噪、自适应音频、USB-C充电', '白色'),
('MACBOOK-AIR-M3', 'MacBook Air M3',     8999.00,  '紧张', '13.6英寸、M3芯片、18小时续航', '午夜色/星光色/深空灰色/银色'),
('IPAD-AIR-M2',    'iPad Air M2',        4799.00,  '充足', '11英寸、M2芯片、 Liquid Retina 显示屏', '星光色/深空灰色/紫色/蓝色'),
('APPLE-WATCH-S9', 'Apple Watch Series 9', 3199.00, '充足', 'S9芯片、全天候视网膜显示屏、血氧监测', '午夜色/星光色/银色/红色');

-- -----------------------------
-- 更新序列
-- -----------------------------
SELECT setval('orders_id_seq', COALESCE((SELECT MAX(id) FROM orders), 1));
SELECT setval('products_id_seq', COALESCE((SELECT MAX(id) FROM products), 1));
