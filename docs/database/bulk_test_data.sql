-- ============================================================
-- SmartAssistant 大批量基础数据生成脚本
-- 生成 50+ 订单、20+ 商品、10+ 用户、30+ 优惠券
-- 执行方式: docker exec -i smart-postgres psql -U postgres -d a2a_system < bulk_test_data.sql
-- ============================================================

-- ========================================
-- 1. 扩展商品数据（新增 20 个商品）
-- ========================================
INSERT INTO products (product_code, product_name, price, stock, spec, colors) VALUES
-- 电子产品
('MBP-14-M4',     'MacBook Pro 14 M4',        14999.00, '充足', 'M4芯片、16GB内存、512GB SSD、Liquid Retina XDR', '深空黑色/银色'),
('IPHONE-16-PRO', 'iPhone 16 Pro Max',         9999.00, '紧张', 'A18 Pro芯片、4800万像素、5倍光学变焦', '原色钛金属/白色钛金属/黑色钛金属/沙漠钛金属'),
('AIRPODS-MAX',   'AirPods Max',               3999.00, '充足', '空间音频、主动降噪、Digital Crown', '深空灰色/银色/绿色/粉色/天蓝色'),
('IPAD-PRO-M4',   'iPad Pro M4 13英寸',        9499.00, '充足', 'M4芯片、Ultra Retina XDR、Apple Pencil Pro', '深空黑色/银色'),
('GALAXY-S25',    'Samsung Galaxy S25 Ultra',  8999.00, '充足', '骁龙8 Elite、2亿像素、S Pen', '钛黑/钛灰/钛白/钛紫'),
('XIAOMI-15',     '小米 15 Pro',                5299.00, '充足', '骁龙8 Elite、徕卡光学、5000mAh', '黑色/白色/岩石灰/雪山粉'),
('SONY-XM6',      'Sony WH-1000XM6',            2899.00, '充足', 'LDAC、主动降噪、40小时续航', '黑色/银色/铂金色'),
('SWITCH-2',      'Nintendo Switch 2',          2999.00, '紧张', '7.9英寸LCD、磁吸Joy-Con、DLSS', '黑白配色'),
('PS5-PRO',       'PlayStation 5 Pro',          5999.00, '紧张', 'AMD Zen2增强、16GB GDDR6、8K输出', '白色'),
('DELL-XPS-15',   'Dell XPS 15 9530',          12999.00, '充足', 'i9-13900H、32GB、1TB、RTX 4070', '铂金银/石墨黑'),

-- 服装
('NIKE-AJ1',      'Nike Air Jordan 1 High OG', 1599.00, '充足', '真皮鞋面、气垫、经典配色', '黑红/白蓝/黑白/芝加哥'),
('ADIDAS-ULTRA',  'Adidas Ultraboost 22',      1299.00, '充足', 'Primeknit+、BOOST中底、Continental大底', '黑色/白色/灰色/蓝色'),
('UNIQLO-UV',     '优衣库 UV防晒衣',              199.00, '充足', 'UPF50+、透气面料、可收纳', '白色/黑色/粉色/蓝色/绿色'),
('ZARA-COAT',     'ZARA 羊毛大衣',              1299.00, '紧张', '80%羊毛、双排扣、修身剪裁', '驼色/黑色/灰色/藏青色'),

-- 图书
('BOOK-AI',       '人工智能：现代方法（第4版）',  299.00, '充足', 'Stuart Russell著、精装、1200页', '精装'),
('BOOK-CSAPP',    '深入理解计算机系统（第3版）',  159.00, '充足', 'Randal E. Bryant著、平装、760页', '平装'),
('BOOK-DP',       '设计模式：可复用面向对象软件的基础', 89.00, '充足', 'GoF著、平装、395页', '平装'),

-- 生鲜食品
('CHERRIES-5KG',  '智利进口车厘子 5斤装',         399.00, '充足', 'JJJ级、空运直发、顺丰冷链', '红色'),
('STEAK-RIBEYE',  '澳洲M5和牛眼肉牛排 2片装',     299.00, '充足', 'M5等级、200g/片、-18℃冷冻', '原味'),
('COFFEE-BEANS',  '埃塞俄比亚耶加雪菲咖啡豆 1kg',  168.00, '充足', '水洗处理、中度烘焙、花果香', '500g*2'),

-- 定制商品
('CUSTOM-CUP',    '定制刻字保温杯',                99.00, '充足', '316不锈钢、500ml、免费刻字', '银色/黑色/粉色'),
('CUSTOM-PHONE',  '定制手机壳（照片印制）',         59.00, '充足', 'TPU材质、全包防摔、支持照片定制', '透明/黑色/白色')
ON CONFLICT (product_code) DO NOTHING;

-- ========================================
-- 2. 新增用户数据（5个测试用户）
-- ========================================
INSERT INTO users (username, password, email, phone, created_at) VALUES
('testuser2',  '$2a$10$dummyhash000000000000000000000000000000000000000000001', 'test2@example.com',  '13900139002', NOW() - INTERVAL '30 days'),
('testuser3',  '$2a$10$dummyhash000000000000000000000000000000000000000000002', 'test3@example.com',  '13900139003', NOW() - INTERVAL '25 days'),
('testuser4',  '$2a$10$dummyhash000000000000000000000000000000000000000000003', 'test4@example.com',  '13900139004', NOW() - INTERVAL '20 days'),
('testuser5',  '$2a$10$dummyhash000000000000000000000000000000000000000000004', 'test5@example.com',  '13900139005', NOW() - INTERVAL '15 days'),
('testuser6',  '$2a$10$dummyhash000000000000000000000000000000000000000000005', 'test6@example.com',  '13900139006', NOW() - INTERVAL '10 days')
ON CONFLICT (username) DO NOTHING;

-- ========================================
-- 3. 批量生成订单数据（50个订单）
--    覆盖所有状态、多个用户、多种商品类型
-- ========================================
INSERT INTO orders (order_id, user_id, product_name, amount, status, carrier, tracking_no,
                    product_type, contact_name, contact_phone, shipping_address, payment_method,
                    created_at, updated_at)
SELECT
    'BULK-' || LPAD(CAST(ROW_NUMBER() OVER () AS TEXT), 4, '0'),
    (ARRAY[1,1,1,2,2,3,3,4,4,5])[1 + (ROW_NUMBER() OVER () % 10)],
    product_name,
    price,
    (ARRAY['待付款','待发货','已发货','已签收','已取消','退款中'])[1 + (ROW_NUMBER() OVER () % 6)],
    CASE WHEN ROW_NUMBER() OVER () % 6 IN (2,3) THEN '顺丰速运' ELSE '' END,
    CASE WHEN ROW_NUMBER() OVER () % 6 IN (2,3) THEN 'SF-BULK-' || LPAD(CAST(ROW_NUMBER() OVER () AS TEXT), 6, '0') ELSE '' END,
    (ARRAY['电子产品','服装','图书','生鲜食品','定制商品'])[1 + (ROW_NUMBER() OVER () % 5)],
    (ARRAY['张三','李四','王五','赵六','钱七'])[1 + (ROW_NUMBER() OVER () % 5)],
    '138' || LPAD(CAST(ROW_NUMBER() OVER () AS TEXT), 8, '0'),
    (ARRAY['北京市海淀区中关村大街1号','上海市浦东新区陆家嘴环路1000号','广州市天河区珠江新城华夏路10号',
           '深圳市南山区科技园南路','杭州市西湖区文三路500号','成都市高新区天府大道中段1号',
           '武汉市江汉区解放大道688号','南京市鼓楼区中山北路12号'])[1 + (ROW_NUMBER() OVER () % 8)],
    (ARRAY['微信支付','支付宝','银行卡','信用卡',''])[1 + (ROW_NUMBER() OVER () % 5)],
    NOW() - (ROW_NUMBER() OVER () || ' hours')::INTERVAL,
    NOW() - (ROW_NUMBER() OVER () || ' hours')::INTERVAL + INTERVAL '1 hour'
FROM products
CROSS JOIN generate_series(1, 5)
WHERE product_code IN ('MBP-14-M4','IPHONE-16-PRO','AIRPODS-MAX','IPAD-PRO-M4','GALAXY-S25',
                        'XIAOMI-15','SONY-XM6','SWITCH-2','PS5-PRO','DELL-XPS-15',
                        'NIKE-AJ1','ADIDAS-ULTRA','UNIQLO-UV','ZARA-COAT',
                        'BOOK-AI','BOOK-CSAPP','BOOK-DP',
                        'CHERRIES-5KG','STEAK-RIBEYE','COFFEE-BEANS',
                        'CUSTOM-CUP','CUSTOM-PHONE')
LIMIT 50
ON CONFLICT (order_id) DO NOTHING;

-- ========================================
-- 4. 批量生成物流轨迹数据（为已发货订单）
-- ========================================
INSERT INTO order_logistics (tracking_no, order_id, carrier, status, trajectory)
SELECT
    'SF-BULK-' || LPAD(CAST(ROW_NUMBER() OVER () AS TEXT), 6, '0'),
    order_id,
    '顺丰速运',
    CASE WHEN status = '已签收' THEN 'delivered' ELSE 'in_transit' END,
    CASE WHEN status = '已签收' THEN
        jsonb_build_array(
            jsonb_build_object('time', (NOW() - INTERVAL '2 days')::text, 'location', '目的地配送站', 'desc', '已到达配送站，配送中'),
            jsonb_build_object('time', (NOW() - INTERVAL '1 day')::text, 'location', '目的地', 'desc', '已签收，签收人：本人')
        )
    ELSE
        jsonb_build_array(
            jsonb_build_object('time', (NOW() - INTERVAL '1 day')::text, 'location', '始发地分拨中心', 'desc', '已揽收'),
            jsonb_build_object('time', (NOW() - INTERVAL '12 hours')::text, 'location', '中转站', 'desc', '运输中')
        )
    END
FROM orders
WHERE order_id LIKE 'BULK-%' AND status IN ('已发货', '已签收')
ON CONFLICT (tracking_no) DO NOTHING;

-- ========================================
-- 5. 批量生成退款记录（为退款中订单）
-- ========================================
INSERT INTO order_refunds (order_id, reason, amount, status, created_by, created_at)
SELECT
    order_id,
    (ARRAY['商品与描述不符','质量问题','尺寸不合适','不喜欢/不想要','物流太慢'])[1 + (ROW_NUMBER() OVER () % 5)],
    amount,
    'pending',
    'user_' || user_id,
    created_at
FROM orders
WHERE order_id LIKE 'BULK-%' AND status = '退款中'
ON CONFLICT DO NOTHING;

-- ========================================
-- 6. 批量生成优惠券（为所有用户）
-- ========================================
INSERT INTO user_coupons (coupon_id, user_id, coupon_type, title, value, condition_amount, used, expire_at)
SELECT
    'BULK-CP-' || user_id || '-' || LPAD(CAST(ROW_NUMBER() OVER () AS TEXT), 3, '0'),
    user_id,
    (ARRAY['FULL_REDUCTION','DISCOUNT','CASH'])[1 + (ROW_NUMBER() OVER () % 3)],
    (ARRAY['满100减20券','9折优惠券','立减15元券','满300减80券','8.5折券','立减30元券'])[1 + (ROW_NUMBER() OVER () % 6)],
    (ARRAY[20.00, 0.90, 15.00, 80.00, 0.85, 30.00])[1 + (ROW_NUMBER() OVER () % 6)],
    (ARRAY[100.00, NULL, NULL, 300.00, NULL, NULL])[1 + (ROW_NUMBER() OVER () % 6)],
    false,
    NOW() + (ARRAY['3 months','1 month','3 months','6 months','15 days','3 months'])[1 + (ROW_NUMBER() OVER () % 6)]::INTERVAL
FROM (SELECT DISTINCT user_id FROM orders WHERE user_id > 0) t
CROSS JOIN generate_series(1, 3)
ON CONFLICT (coupon_id) DO NOTHING;

-- ========================================
-- 7. 生成审批记录（为退款中订单）
-- ========================================
INSERT INTO approval_records (order_id, action_type, reason, status, created_at)
SELECT
    order_id,
    'refund',
    (ARRAY['商品与描述不符','质量问题','尺寸不合适'])[1 + (ROW_NUMBER() OVER () % 3)],
    'pending',
    NOW() - INTERVAL '1 hour'
FROM orders
WHERE order_id LIKE 'BULK-%' AND status = '退款中'
ON CONFLICT DO NOTHING;

-- ========================================
-- 8. 更新所有序列
-- ========================================
SELECT setval('orders_id_seq', COALESCE((SELECT MAX(id) FROM orders), 1));
SELECT setval('products_id_seq', COALESCE((SELECT MAX(id) FROM products), 1));
SELECT setval('user_coupons_id_seq', COALESCE((SELECT MAX(id) FROM user_coupons), 1));
SELECT setval('order_logistics_id_seq', COALESCE((SELECT MAX(id) FROM order_logistics), 1));
SELECT setval('order_refunds_id_seq', COALESCE((SELECT MAX(id) FROM order_refunds), 1));
SELECT setval('approval_records_id_seq', COALESCE((SELECT MAX(id) FROM approval_records), 1));

-- ========================================
-- 9. 数据统计验证
-- ========================================
SELECT '========== 大批量数据加载完成 ==========' AS msg;
SELECT '订单总数' AS category, COUNT(*) AS count FROM orders
UNION ALL
SELECT '商品总数', COUNT(*) FROM products
UNION ALL
SELECT '优惠券总数', COUNT(*) FROM user_coupons
UNION ALL
SELECT '物流轨迹总数', COUNT(*) FROM order_logistics
UNION ALL
SELECT '退款记录总数', COUNT(*) FROM order_refunds
UNION ALL
SELECT '审批记录总数', COUNT(*) FROM approval_records
ORDER BY category;

SELECT '========== 订单状态分布 ==========' AS msg;
SELECT status, COUNT(*) AS count FROM orders GROUP BY status ORDER BY count DESC;

SELECT '========== 用户订单分布 ==========' AS msg;
SELECT user_id, COUNT(*) AS order_count FROM orders GROUP BY user_id ORDER BY user_id;
