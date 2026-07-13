-- ============================================================
-- SmartAssistant 种子数据
-- 基于项目业务场景生成（订单客服系统）
-- 生成日期: 2026-07-10
-- ============================================================

-- ══════════════════════════════════════════════════════════════
-- 1. 用户数据
-- ══════════════════════════════════════════════════════════════
INSERT INTO users (username, password, email, role) VALUES
    ('test_user', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'test@example.com', 'ROLE_USER'),
    ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'admin@example.com', 'ROLE_ADMIN');

-- ══════════════════════════════════════════════════════════════
-- 2. 商品数据（匹配 InMemoryProductBackend 定义）
-- ══════════════════════════════════════════════════════════════
INSERT INTO products (product_code, product_name, price, stock, spec, color) VALUES
    ('IPHONE-15-PRO', 'iPhone 15 Pro', 8999.00, '充足',
     '钛金属、A17 Pro芯片、4800万像素',
     '原色钛金属/蓝色钛金属/白色钛金属/黑色钛金属'),
    ('AIRPODS-PRO', 'AirPods Pro（第二代）', 1999.00, '充足',
     '降噪、自适应音频、USB-C充电',
     '白色'),
    ('MACBOOK-AIR-M3', 'MacBook Air M3', 8999.00, '紧张',
     '13.6英寸、M3芯片、18小时续航',
     '午夜色/星光色/深空灰色/银色');

-- ══════════════════════════════════════════════════════════════
-- 3. 订单数据（覆盖完整生命周期）
-- ══════════════════════════════════════════════════════════════
INSERT INTO orders (order_id, user_id, product_name, amount, status,
                    contact_name, contact_phone, shipping_address, payment_method,
                    created_at) VALUES
    ('ORD-1001', 1, 'iPhone 15 Pro', 8999.00, '待付款',
     '张三', '13800138001', '北京市朝阳区建国路88号', NULL,
     CURRENT_TIMESTAMP - INTERVAL '1 hour'),

    ('ORD-1002', 1, 'AirPods Pro（第二代）', 1999.00, '待发货',
     '张三', '13800138001', '北京市朝阳区建国路88号', '微信支付',
     CURRENT_TIMESTAMP - INTERVAL '1 day'),

    ('ORD-1003', 1, 'MacBook Air M3', 8999.00, '已发货',
     '李四', '13900139002', '上海市浦东新区陆家嘴环路1000号', '支付宝',
     CURRENT_TIMESTAMP - INTERVAL '2 days'),

    ('ORD-1004', 1, 'iPhone 15 Pro', 8999.00, '已签收',
     '王五', '13700137003', '深圳市南山区科技园南路', '银行卡',
     CURRENT_TIMESTAMP - INTERVAL '5 days'),

    ('ORD-1005', 1, 'AirPods Pro（第二代）', 1999.00, '退款中',
     '赵六', '13600136004', '广州市天河区珠江新城', '微信支付',
     CURRENT_TIMESTAMP - INTERVAL '3 days');

-- ══════════════════════════════════════════════════════════════
-- 4. 物流记录
-- ══════════════════════════════════════════════════════════════
INSERT INTO order_logistics (tracking_no, order_id, carrier, status, trajectory) VALUES
    ('SF202607100001', 'ORD-1003', '顺丰速运', 'in_transit',
     '[{"time":"2026-07-09 18:00:00","location":"北京分拣中心","desc":"快件已到达北京分拣中心"},{"time":"2026-07-10 06:30:00","location":"上海分拣中心","desc":"快件已到达上海分拣中心，正在派送中"}]'),
    ('SF202607080001', 'ORD-1004', '顺丰速运', 'delivered',
     '[{"time":"2026-07-06 10:00:00","location":"深圳分拣中心","desc":"快件已到达深圳分拣中心"},{"time":"2026-07-06 14:30:00","location":"深圳科技园","desc":"已签收，签收人：王五"}]');

-- ══════════════════════════════════════════════════════════════
-- 5. 退款记录
-- ══════════════════════════════════════════════════════════════
INSERT INTO order_refunds (order_id, reason, amount, status, created_by) VALUES
    ('ORD-1005', '商品有瑕疵，屏幕显示异常', 1999.00, 'pending', '赵六');

-- ══════════════════════════════════════════════════════════════
-- 6. 优惠券数据
-- ══════════════════════════════════════════════════════════════
INSERT INTO user_coupons (coupon_id, user_id, coupon_type, title, value, condition_amount, expire_at) VALUES
    ('CPN-001', 1, 'FULL_REDUCTION', '满5000减300', 300.00, 5000.00,
     CURRENT_TIMESTAMP + INTERVAL '30 days'),
    ('CPN-002', 1, 'DISCOUNT', '全场9折', 0.90, NULL,
     CURRENT_TIMESTAMP + INTERVAL '15 days'),
    ('CPN-003', 1, 'CASH', '新用户立减50', 50.00, NULL,
     CURRENT_TIMESTAMP + INTERVAL '7 days');

-- ══════════════════════════════════════════════════════════════
-- 7. 对话反馈数据（AdminService 统计需要）
-- ══════════════════════════════════════════════════════════════
INSERT INTO conversation_feedback (session_id, user_id, rating, feedback_text, intent_tag, agent_name) VALUES
    ('SESS-001', 1, 5, '查询速度很快，回答准确', 'QUERY_ORDER', 'order'),
    ('SESS-002', 1, 4, '退款流程清晰明了', 'REFUND', 'order'),
    ('SESS-003', 1, 3, '下单过程还行，但支付方式太少', 'CREATE_ORDER', 'order'),
    ('SESS-004', 1, 5, '商品推荐很精准', 'PRODUCT_QUERY', 'product');
