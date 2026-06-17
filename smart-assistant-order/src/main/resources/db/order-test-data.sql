-- ============================================================
-- SmartAssistant Order Module - 测试数据
-- 覆盖全流程各状态的订单，用于功能验证
-- 执行方式: psql -U postgres -d a2a_system -f order-test-data.sql
-- ============================================================

-- 先执行 order-schema.sql 建表，再执行此脚本

-- -----------------------------
-- 1. 清理旧测试数据（不影响 seed_data）
-- -----------------------------
DELETE FROM order_logistics WHERE order_id LIKE 'TEST-%';
DELETE FROM order_refunds WHERE order_id LIKE 'TEST-%';
DELETE FROM approval_records WHERE order_id LIKE 'TEST-%';
DELETE FROM orders WHERE order_id LIKE 'TEST-%';

-- -----------------------------
-- 2. 测试订单数据（覆盖 6 种状态）
--    下单时间模拟为近期，便于测试
-- -----------------------------
INSERT INTO orders (order_id, user_id, product_name, amount, status, carrier, tracking_no,
                    product_type, contact_name, contact_phone, shipping_address, payment_method,
                    created_at, updated_at)
VALUES
-- TEST-001: 待付款（刚下单，未支付）
('TEST-001', 1, 'Apple iPhone 15 Pro Max 512GB', 11999.00, '待付款', '', '',
 '电子产品', '张三', '13800138001', '北京市海淀区中关村大街1号', '',
 NOW() - INTERVAL '1 hour', NOW() - INTERVAL '1 hour'),

-- TEST-002: 待发货（已支付，等待发货）
('TEST-002', 1, 'Samsung Galaxy Tab S9 Ultra', 8999.00, '待发货', '', '',
 '电子产品', '张三', '13800138001', '北京市海淀区中关村大街1号', '微信支付',
 NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day'),

-- TEST-003: 已发货（已发货，运输中）
('TEST-003', 1, 'Sony WH-1000XM5 降噪耳机', 2499.00, '已发货', '顺丰速运', 'SF-TEST-003',
 '电子产品', '李四', '13900139002', '上海市浦东新区陆家嘴环路1000号', '支付宝',
 NOW() - INTERVAL '3 days', NOW() - INTERVAL '5 hours'),

-- TEST-004: 已签收（已完成）
('TEST-004', 1, 'Dell XPS 15 笔记本', 15999.00, '已签收', '京东物流', 'JD-TEST-004',
 '电子产品', '王五', '13700137003', '广州市天河区珠江新城华夏路10号', '微信支付',
 NOW() - INTERVAL '5 days', NOW() - INTERVAL '1 days'),

-- TEST-005: 已取消（用户取消）
('TEST-005', 1, 'HUAWEI Mate 60 Pro', 6999.00, '已取消', '', '',
 '电子产品', '赵六', '13600136004', '深圳市南山区科技园南路', '',
 NOW() - INTERVAL '4 days', NOW() - INTERVAL '3 days'),

-- TEST-006: 退款中（已申请退款）
('TEST-006', 1, 'iPad Pro M4 11英寸', 8499.00, '退款中', '', '',
 '电子产品', '张三', '13800138001', '北京市海淀区中关村大街1号', '支付宝',
 NOW() - INTERVAL '6 days', NOW() - INTERVAL '2 hours'),

-- TEST-007: 待付款（新订单，定制商品）
('TEST-007', 2, '定制刻字保温杯（企业定制）', 299.00, '待付款', '', '',
 '定制商品', '企业采购部', '13500135005', '杭州市西湖区文三路500号', '',
 NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '30 minutes'),

-- TEST-008: 待付款（生鲜食品）
('TEST-008', 2, '智利进口车厘子 5斤装', 399.00, '待付款', '', '',
 '生鲜食品', '企业采购部', '13500135005', '杭州市西湖区文三路500号', '',
 NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes');

-- -----------------------------
-- 3. 测试物流轨迹数据
-- -----------------------------
INSERT INTO order_logistics (tracking_no, order_id, carrier, status, trajectory)
VALUES
('SF-TEST-003', 'TEST-003', '顺丰速运', 'in_transit',
 '[{"time":"' || (NOW() - INTERVAL '5 hours')::text || '","location":"深圳分拨中心","desc":"已揽收，包裹已被顺丰速运收取"},
   {"time":"' || (NOW() - INTERVAL '3 hours')::text || '","location":"深圳分拨中心","desc":"离开 深圳分拨中心，发往上海"}]'::jsonb),

('JD-TEST-004', 'TEST-004', '京东物流', 'delivered',
 '[{"time":"' || (NOW() - INTERVAL '2 days')::text || '","location":"广州萝岗分拣中心","desc":"已揽收，包裹已被京东物流收取"},
   {"time":"' || (NOW() - INTERVAL '1 day')::text || '","location":"广州天河配送站","desc":"已到达 广州天河配送站，配送中"},
   {"time":"' || (NOW() - INTERVAL '1 day' + INTERVAL '6 hours')::text || '","location":"广州天河区","desc":"已签收，签收人：本人"}]'::jsonb);

-- -----------------------------
-- 4. 测试退款记录
-- -----------------------------
INSERT INTO order_refunds (order_id, reason, amount, status, created_by, created_at)
VALUES
('TEST-006', '商品颜色与描述不符，申请全额退款', 8499.00, 'completed', 'user_1',
 NOW() - INTERVAL '2 hours');

-- -----------------------------
-- 5. 测试审批记录（退款已确认）
-- -----------------------------
INSERT INTO approval_records (order_id, action_type, reason, status, created_at, confirmed_at, consumed_at)
VALUES
('TEST-006', 'refund', '商品颜色与描述不符，申请全额退款', 'consumed',
 NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours');

-- -----------------------------
-- 6. 验证数据
-- -----------------------------
SELECT '========== 测试数据验证 ==========' AS msg;
SELECT order_id, status, product_name, amount::text,
       contact_name, contact_phone, payment_method,
       created_at::text AS created
FROM orders WHERE order_id LIKE 'TEST-%' ORDER BY order_id;
