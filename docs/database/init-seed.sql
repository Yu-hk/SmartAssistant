-- ============================================================
-- SmartAssistant 可重复执行的演示种子数据
-- 适用场景：本地开发、接口联调、项目演示
-- 安全性：所有业务键均采用 ON CONFLICT 或 NOT EXISTS 去重
-- ============================================================

BEGIN;

-- 演示账号。两者的初始密码均为 password，仅限本地/演示环境。
INSERT INTO users (username, password, email, role) VALUES
    ('test_user', '$2a$10$1dt1lB3qbi.OxD043Oo.guNZ1.DKhZDcd0.EhYozbXsbPefCLT9Bi', 'test@example.com', 'ROLE_USER'),
    ('admin', '$2a$10$1dt1lB3qbi.OxD043Oo.guNZ1.DKhZDcd0.EhYozbXsbPefCLT9Bi', 'admin@example.com', 'ROLE_ADMIN')
ON CONFLICT (username) DO UPDATE SET
    password = EXCLUDED.password,
    email = EXCLUDED.email,
    role = EXCLUDED.role,
    updated_at = CURRENT_TIMESTAMP;

-- 商品数据，与 TextToSqlTool 暴露的数据字典保持一致。
INSERT INTO products (product_code, product_name, price, stock, spec, colors) VALUES
    ('IPHONE-15-PRO', 'iPhone 15 Pro', 8999.00, '充足',
     '钛金属、A17 Pro 芯片、4800 万像素',
     '原色钛金属/蓝色钛金属/白色钛金属/黑色钛金属'),
    ('AIRPODS-PRO', 'AirPods Pro（第二代）', 1999.00, '充足',
     '降噪、自适应音频、USB-C 充电', '白色'),
    ('MACBOOK-AIR-M3', 'MacBook Air M3', 8999.00, '紧张',
     '13.6 英寸、M3 芯片、18 小时续航', '午夜色/星光色/深空灰色/银色'),
    ('IPAD-AIR-M2', 'iPad Air M2', 4799.00, '充足',
     '11 英寸、M2 芯片、Liquid Retina 显示屏', '星光色/深空灰色/紫色/蓝色'),
    ('APPLE-WATCH-S9', 'Apple Watch Series 9', 3199.00, '充足',
     'S9 芯片、全天候视网膜显示屏、运动健康监测', '午夜色/星光色/银色/红色')
ON CONFLICT (product_code) DO UPDATE SET
    product_name = EXCLUDED.product_name,
    price = EXCLUDED.price,
    stock = EXCLUDED.stock,
    spec = EXCLUDED.spec,
    colors = EXCLUDED.colors;

-- 覆盖待付款、待发货、已发货、已签收和退款中五种订单状态。
INSERT INTO orders (
    order_id, user_id, product_name, amount, status, carrier, tracking_no,
    product_type, delivered_date, contact_name, contact_phone,
    shipping_address, payment_method, created_at, updated_at
) VALUES
    ('ORD-1001', (SELECT id FROM users WHERE username = 'test_user'), 'iPhone 15 Pro', 8999.00, '待付款', NULL, NULL,
     '电子产品', NULL, '张三', '138****0001', '北京市朝阳区建国路88号', NULL,
     CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP - INTERVAL '1 hour'),
    ('ORD-1002', (SELECT id FROM users WHERE username = 'test_user'), 'AirPods Pro（第二代）', 1999.00, '待发货', NULL, NULL,
     '电子产品', NULL, '张三', '138****0001', '北京市朝阳区建国路88号', '微信支付',
     CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '20 hours'),
    ('ORD-1003', (SELECT id FROM users WHERE username = 'test_user'), 'MacBook Air M3', 8999.00, '已发货', '顺丰速运', 'SF202607100001',
     '电子产品', NULL, '李四', '139****0002', '上海市浦东新区陆家嘴环路1000号', '支付宝',
     CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '6 hours'),
    ('ORD-1004', (SELECT id FROM users WHERE username = 'test_user'), 'iPhone 15 Pro', 8999.00, '已签收', '顺丰速运', 'SF202607080001',
     '电子产品', CURRENT_TIMESTAMP - INTERVAL '2 days', '王五', '137****0003',
     '深圳市南山区科技园南路', '银行卡',
     CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('ORD-1005', (SELECT id FROM users WHERE username = 'test_user'), 'AirPods Pro（第二代）', 1999.00, '退款中', NULL, NULL,
     '电子产品', NULL, '赵六', '136****0004', '广州市天河区珠江新城', '微信支付',
     CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '1 day')
ON CONFLICT (order_id) DO NOTHING;

INSERT INTO order_logistics (tracking_no, order_id, carrier, status, trajectory) VALUES
    ('SF202607100001', 'ORD-1003', '顺丰速运', 'in_transit',
     '[{"time":"2026-07-09 18:00:00","location":"北京分拣中心","desc":"快件已发出"},{"time":"2026-07-10 06:30:00","location":"上海分拣中心","desc":"正在派送中"}]'),
    ('SF202607080001', 'ORD-1004', '顺丰速运', 'delivered',
     '[{"time":"2026-07-06 10:00:00","location":"深圳分拣中心","desc":"到达目的地"},{"time":"2026-07-06 14:30:00","location":"深圳科技园","desc":"已签收"}]')
ON CONFLICT (tracking_no) DO UPDATE SET
    status = EXCLUDED.status,
    trajectory = EXCLUDED.trajectory,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO order_refunds (order_id, reason, amount, status, created_by)
SELECT 'ORD-1005', '商品有瑕疵，屏幕显示异常', 1999.00, 'pending', 'demo-user'
WHERE NOT EXISTS (
    SELECT 1 FROM order_refunds WHERE order_id = 'ORD-1005' AND status = 'pending'
);

INSERT INTO user_coupons (
    coupon_id, user_id, coupon_type, title, value, condition_amount, used, expire_at
) VALUES
    ('CPN-001', (SELECT id FROM users WHERE username = 'test_user'), 'FULL_REDUCTION', '满5000减300', 300.00, 5000.00, FALSE,
     CURRENT_TIMESTAMP + INTERVAL '30 days'),
    ('CPN-002', (SELECT id FROM users WHERE username = 'test_user'), 'DISCOUNT', '全场9折', 0.90, NULL, FALSE,
     CURRENT_TIMESTAMP + INTERVAL '15 days'),
    ('CPN-003', (SELECT id FROM users WHERE username = 'test_user'), 'CASH', '新用户立减50', 50.00, NULL, FALSE,
     CURRENT_TIMESTAMP + INTERVAL '7 days')
ON CONFLICT (coupon_id) DO UPDATE SET
    title = EXCLUDED.title,
    value = EXCLUDED.value,
    condition_amount = EXCLUDED.condition_amount,
    used = EXCLUDED.used,
    expire_at = EXCLUDED.expire_at,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO conversation_feedback (
    session_id, user_id, rating, feedback_text, intent_tag, agent_name, metadata
)
SELECT seed.session_id, (SELECT id FROM users WHERE username = 'test_user'), seed.rating,
       seed.feedback_text, seed.intent_tag, seed.agent_name,
       '{"source":"demo-seed"}'::jsonb
FROM (VALUES
    ('SESS-001', 5, '查询速度很快，回答准确', 'QUERY_ORDER', 'order'),
    ('SESS-002', 4, '退款流程清晰明了', 'REFUND', 'order'),
    ('SESS-003', 3, '下单过程顺畅', 'CREATE_ORDER', 'order'),
    ('SESS-004', 5, '商品推荐很精准', 'PRODUCT_QUERY', 'product')
) AS seed(session_id, rating, feedback_text, intent_tag, agent_name)
WHERE NOT EXISTS (
    SELECT 1 FROM conversation_feedback f WHERE f.session_id = seed.session_id
);

COMMIT;
