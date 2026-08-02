-- ============================================================
-- SmartAssistant 多并发全流程验证数据
--
-- 默认规模：
--   1,000 用户 / 5,000 会话 / 120 商品 / 20,000 订单
--   约 6,000 物流 / 3,000 退款 / 4,000+ 审批 / 4,000 优惠券
--   30,000 路由日志 / 3,000 反馈
--
-- 所有业务键均使用 LOAD- / ORD-LOAD / load_user_ 前缀，支持重复执行。
-- 可通过 psql -v sa_user_count=... 覆盖下列默认值。
-- ============================================================

\set ON_ERROR_STOP on

\if :{?sa_user_count}
\else
\set sa_user_count 1000
\endif
\if :{?sa_sessions_per_user}
\else
\set sa_sessions_per_user 5
\endif
\if :{?sa_product_count}
\else
\set sa_product_count 120
\endif
\if :{?sa_orders_per_user}
\else
\set sa_orders_per_user 20
\endif
\if :{?sa_routes_per_user}
\else
\set sa_routes_per_user 30
\endif
\if :{?sa_feedback_per_user}
\else
\set sa_feedback_per_user 3
\endif
\if :{?sa_coupons_per_user}
\else
\set sa_coupons_per_user 4
\endif

SELECT :sa_user_count::integer BETWEEN 1 AND 100000
   AND :sa_sessions_per_user::integer BETWEEN 1 AND 20
   AND :sa_product_count::integer BETWEEN 1 AND 10000
   AND :sa_orders_per_user::integer BETWEEN 1 AND 1000
   AND :sa_routes_per_user::integer BETWEEN 1 AND 5000
   AND :sa_feedback_per_user::integer BETWEEN 0 AND :sa_sessions_per_user::integer
   AND :sa_coupons_per_user::integer BETWEEN 0 AND 100 AS sa_parameters_valid
\gset
\if :sa_parameters_valid
\else
\echo 'ERROR: concurrency fixture parameters are outside the safe range.'
\quit 2
\endif

BEGIN;

-- --------------------------------------------------------------------------
-- 1. 可登录用户。统一密码 password，仅限本地测试环境。
-- --------------------------------------------------------------------------
INSERT INTO users (username, password, email, role, created_at, updated_at)
SELECT
    'load_user_' || LPAD(user_no::text, 6, '0'),
    '$2a$10$1dt1lB3qbi.OxD043Oo.guNZ1.DKhZDcd0.EhYozbXsbPefCLT9Bi',
    'load_user_' || LPAD(user_no::text, 6, '0') || '@example.test',
    CASE WHEN user_no % 100 = 0 THEN 'ROLE_ADMIN' ELSE 'ROLE_USER' END,
    CURRENT_TIMESTAMP - ((user_no % 180) || ' days')::interval,
    CURRENT_TIMESTAMP
FROM generate_series(1, :sa_user_count::integer) AS user_no
ON CONFLICT (username) DO UPDATE SET
    password = EXCLUDED.password,
    email = EXCLUDED.email,
    role = EXCLUDED.role,
    updated_at = CURRENT_TIMESTAMP;

-- --------------------------------------------------------------------------
-- 2. 用户会话。每 5 个会话默认覆盖 3 个活跃、1 个过期、1 个撤销。
-- --------------------------------------------------------------------------
INSERT INTO user_sessions (
    user_id, token_id, device_info, ip_address, user_agent,
    is_active, is_revoked, created_at, last_active_at, expires_at, revoked_at
)
SELECT
    u.id,
    'LOAD-TOKEN-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(session_no::text, 2, '0'),
    jsonb_build_object(
        'platform', (ARRAY['web', 'android', 'ios'])[((session_no - 1) % 3) + 1],
        'deviceId', 'load-device-' || user_no || '-' || session_no,
        'fixture', 'concurrency-v1'
    ),
    '10.' || (user_no % 250) || '.' || (session_no % 250) || '.' || ((user_no + session_no) % 250 + 1),
    (ARRAY['SmartAssistantLoad/Web', 'SmartAssistantLoad/Android', 'SmartAssistantLoad/iOS'])[((session_no - 1) % 3) + 1],
    session_no <= GREATEST(1, :sa_sessions_per_user::integer - 2),
    session_no = :sa_sessions_per_user::integer,
    CURRENT_TIMESTAMP - ((session_no * 3) || ' hours')::interval,
    CURRENT_TIMESTAMP - ((session_no * 7) || ' minutes')::interval,
    CASE
        WHEN session_no = :sa_sessions_per_user::integer - 1 THEN CURRENT_TIMESTAMP - INTERVAL '1 hour'
        ELSE CURRENT_TIMESTAMP + ((session_no + 1) || ' days')::interval
    END,
    CASE WHEN session_no = :sa_sessions_per_user::integer
         THEN CURRENT_TIMESTAMP - INTERVAL '30 minutes' END
FROM generate_series(1, :sa_user_count::integer) AS user_no
CROSS JOIN generate_series(1, :sa_sessions_per_user::integer) AS session_no
JOIN users u
  ON u.username = 'load_user_' || LPAD(user_no::text, 6, '0')
ON CONFLICT (token_id) DO UPDATE SET
    device_info = EXCLUDED.device_info,
    ip_address = EXCLUDED.ip_address,
    user_agent = EXCLUDED.user_agent,
    is_active = EXCLUDED.is_active,
    is_revoked = EXCLUDED.is_revoked,
    last_active_at = EXCLUDED.last_active_at,
    expires_at = EXCLUDED.expires_at,
    revoked_at = EXCLUDED.revoked_at;

-- --------------------------------------------------------------------------
-- 3. 商品目录。覆盖品类、价格区间和库存状态。
-- --------------------------------------------------------------------------
INSERT INTO products (product_code, product_name, price, stock, spec, colors, created_at)
SELECT
    'LOAD-PROD-' || LPAD(product_no::text, 4, '0'),
    (ARRAY['手机', '平板电脑', '笔记本电脑', '耳机', '智能手表', '运动鞋', '图书', '食品'])[((product_no - 1) % 8) + 1]
        || ' 并发测试款 ' || LPAD(product_no::text, 4, '0'),
    (99 + ((product_no * 137) % 15000))::numeric(10, 2),
    (ARRAY['充足', '充足', '充足', '紧张', '缺货'])[((product_no - 1) % 5) + 1],
    '并发验证商品；型号 SA-' || LPAD(product_no::text, 4, '0') || '；支持查询、下单、推荐和统计链路',
    (ARRAY['黑色/白色', '蓝色/银色', '红色/灰色', '标准色'])[((product_no - 1) % 4) + 1],
    CURRENT_TIMESTAMP - ((product_no % 90) || ' days')::interval
FROM generate_series(1, :sa_product_count::integer) AS product_no
ON CONFLICT (product_code) DO UPDATE SET
    product_name = EXCLUDED.product_name,
    price = EXCLUDED.price,
    stock = EXCLUDED.stock,
    spec = EXCLUDED.spec,
    colors = EXCLUDED.colors;

-- --------------------------------------------------------------------------
-- 4. 订单。六种业务状态均匀分布，可直接用于状态流转和并发查询。
-- --------------------------------------------------------------------------
WITH generated AS (
    SELECT
        u.id AS user_id,
        user_no,
        order_no,
        (((user_no * 31 + order_no * 17) - 1) % :sa_product_count::integer) + 1 AS product_no,
        (ARRAY['待付款', '待发货', '已发货', '已签收', '已取消', '退款中'])[((order_no - 1) % 6) + 1] AS order_status
    FROM generate_series(1, :sa_user_count::integer) AS user_no
    CROSS JOIN generate_series(1, :sa_orders_per_user::integer) AS order_no
    JOIN users u
      ON u.username = 'load_user_' || LPAD(user_no::text, 6, '0')
), enriched AS (
    SELECT g.*, p.product_name, p.price,
           split_part(p.product_name, ' 并发测试款', 1) AS product_type
    FROM generated g
    JOIN products p
      ON p.product_code = 'LOAD-PROD-' || LPAD(g.product_no::text, 4, '0')
)
INSERT INTO orders (
    order_id, user_id, product_name, amount, status, carrier, tracking_no,
    product_type, delivered_date, contact_name, contact_phone,
    shipping_address, payment_method, request_id, created_at, updated_at
)
SELECT
    'ORD-LOAD' || LPAD(user_no::text, 6, '0') || LPAD(order_no::text, 3, '0'),
    user_id,
    product_name,
    price,
    order_status,
    CASE WHEN order_status IN ('已发货', '已签收')
         THEN (ARRAY['顺丰速运', '京东物流', '中通快递'])[((order_no - 1) % 3) + 1] END,
    CASE WHEN order_status IN ('已发货', '已签收')
         THEN 'LOAD-SF-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(order_no::text, 3, '0') END,
    product_type,
    CASE WHEN order_status = '已签收'
         THEN CURRENT_TIMESTAMP - (((user_no + order_no) % 14 + 1) || ' days')::interval END,
    '并发用户' || LPAD(user_no::text, 6, '0'),
    '13' || LPAD((user_no % 1000000000)::text, 9, '0'),
    (ARRAY[
        '北京市海淀区中关村测试路1号', '上海市浦东新区测试大道2号',
        '深圳市南山区科技测试园3号', '杭州市余杭区未来测试城4号',
        '成都市高新区天府测试街5号'
    ])[((user_no - 1) % 5) + 1],
    CASE WHEN order_status = '待付款' THEN NULL
         ELSE (ARRAY['微信支付', '支付宝', '银行卡'])[((order_no - 1) % 3) + 1] END,
    'LOAD-ORDER-REQ-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(order_no::text, 3, '0'),
    CURRENT_TIMESTAMP - (((user_no * :sa_orders_per_user::integer + order_no) % 259200) || ' minutes')::interval,
    CURRENT_TIMESTAMP - (((user_no + order_no) % 1440) || ' minutes')::interval
FROM enriched
ON CONFLICT (order_id) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    product_name = EXCLUDED.product_name,
    amount = EXCLUDED.amount,
    status = EXCLUDED.status,
    carrier = EXCLUDED.carrier,
    tracking_no = EXCLUDED.tracking_no,
    product_type = EXCLUDED.product_type,
    delivered_date = EXCLUDED.delivered_date,
    contact_name = EXCLUDED.contact_name,
    contact_phone = EXCLUDED.contact_phone,
    shipping_address = EXCLUDED.shipping_address,
    payment_method = EXCLUDED.payment_method,
    request_id = EXCLUDED.request_id,
    updated_at = CURRENT_TIMESTAMP;

-- --------------------------------------------------------------------------
-- 5. 物流轨迹。与已发货、已签收订单一一对应。
-- --------------------------------------------------------------------------
INSERT INTO order_logistics (
    tracking_no, order_id, carrier, status, trajectory, created_at, updated_at
)
SELECT
    o.tracking_no,
    o.order_id,
    o.carrier,
    CASE WHEN o.status = '已签收' THEN 'delivered' ELSE 'in_transit' END,
    CASE WHEN o.status = '已签收' THEN
        jsonb_build_array(
            jsonb_build_object('time', (o.created_at + INTERVAL '8 hours')::text, 'location', '始发分拨中心', 'desc', '快件已揽收'),
            jsonb_build_object('time', o.delivered_date::text, 'location', '收货地址', 'desc', '已签收，签收人：本人')
        )::text
    ELSE
        jsonb_build_array(
            jsonb_build_object('time', (o.created_at + INTERVAL '8 hours')::text, 'location', '始发分拨中心', 'desc', '快件已揽收'),
            jsonb_build_object('time', (o.created_at + INTERVAL '20 hours')::text, 'location', '中转场', 'desc', '运输中')
        )::text
    END,
    o.created_at + INTERVAL '8 hours',
    o.updated_at
FROM orders o
WHERE o.order_id LIKE 'ORD-LOAD%'
  AND o.status IN ('已发货', '已签收')
ON CONFLICT (tracking_no) DO UPDATE SET
    status = EXCLUDED.status,
    trajectory = EXCLUDED.trajectory,
    updated_at = EXCLUDED.updated_at;

-- --------------------------------------------------------------------------
-- 6. 退款记录。保留多种处理状态，pending 数据可用于并发申请验证。
-- --------------------------------------------------------------------------
INSERT INTO order_refunds (order_id, reason, amount, status, created_by, created_at, updated_at)
SELECT
    o.order_id,
    (ARRAY['商品质量问题', '商品与描述不符', '物流时效未达预期', '七日无理由退货'])[((o.id - 1) % 4) + 1],
    o.amount,
    (ARRAY['pending', 'pending', 'approved', 'rejected'])[((o.id - 1) % 4) + 1],
    u.username,
    o.created_at + INTERVAL '1 day',
    o.updated_at
FROM orders o
JOIN users u ON u.id = o.user_id
WHERE o.order_id LIKE 'ORD-LOAD%'
  AND o.status = '退款中'
  AND NOT EXISTS (
      SELECT 1 FROM order_refunds r WHERE r.order_id = o.order_id
  );

-- --------------------------------------------------------------------------
-- 7. 审批状态机数据。覆盖 PENDING / CONFIRMED / CONSUMED / CANCELLED。
-- --------------------------------------------------------------------------
WITH candidates AS (
    SELECT o.*,
           CASE WHEN o.status = '退款中' THEN 'refund' ELSE 'cancel' END AS action_type,
           (ARRAY['PENDING', 'CONFIRMED', 'CONSUMED', 'CANCELLED'])[((o.id - 1) % 4) + 1] AS approval_status
    FROM orders o
    WHERE o.order_id LIKE 'ORD-LOAD%'
      AND (o.status = '退款中' OR (o.status IN ('待付款', '待发货') AND o.id % 3 = 0))
)
INSERT INTO approval_records (
    order_id, action_type, reason, status, operator, operator_ip,
    created_at, confirmed_at, consumed_at
)
SELECT
    c.order_id,
    c.action_type,
    CASE WHEN c.action_type = 'refund' THEN '并发退款审批验证' ELSE '并发取消审批验证' END,
    c.approval_status,
    CASE WHEN c.approval_status = 'PENDING' THEN NULL ELSE 'load-operator-' || (c.id % 20 + 1) END,
    CASE WHEN c.approval_status = 'PENDING' THEN NULL ELSE '10.20.0.' || (c.id % 250 + 1) END,
    c.created_at + INTERVAL '30 minutes',
    CASE WHEN c.approval_status IN ('CONFIRMED', 'CONSUMED') THEN c.created_at + INTERVAL '45 minutes' END,
    CASE WHEN c.approval_status = 'CONSUMED' THEN c.created_at + INTERVAL '50 minutes' END
FROM candidates c
WHERE NOT EXISTS (
    SELECT 1 FROM approval_records a
    WHERE a.order_id = c.order_id AND a.action_type = c.action_type
);

-- --------------------------------------------------------------------------
-- 8. 优惠券。覆盖满减、折扣、现金券以及已用/未用、有效/过期。
-- --------------------------------------------------------------------------
INSERT INTO user_coupons (
    coupon_id, user_id, coupon_type, title, value,
    condition_amount, used, expire_at, created_at, updated_at
)
SELECT
    'LOAD-CPN-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(coupon_no::text, 2, '0'),
    u.id,
    (ARRAY['FULL_REDUCTION', 'DISCOUNT', 'CASH'])[((coupon_no - 1) % 3) + 1],
    (ARRAY['满500减50并发测试券', '九折并发测试券', '立减20元并发测试券'])[((coupon_no - 1) % 3) + 1],
    (ARRAY[50.00, 0.90, 20.00]::numeric[])[((coupon_no - 1) % 3) + 1],
    CASE WHEN (coupon_no - 1) % 3 = 0 THEN 500.00 END,
    coupon_no % 4 = 0,
    CURRENT_TIMESTAMP + ((coupon_no * 30 - 45) || ' days')::interval,
    CURRENT_TIMESTAMP - INTERVAL '30 days',
    CURRENT_TIMESTAMP
FROM generate_series(1, :sa_user_count::integer) AS user_no
CROSS JOIN generate_series(1, :sa_coupons_per_user::integer) AS coupon_no
JOIN users u
  ON u.username = 'load_user_' || LPAD(user_no::text, 6, '0')
ON CONFLICT (coupon_id) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    coupon_type = EXCLUDED.coupon_type,
    title = EXCLUDED.title,
    value = EXCLUDED.value,
    condition_amount = EXCLUDED.condition_amount,
    used = EXCLUDED.used,
    expire_at = EXCLUDED.expire_at,
    updated_at = CURRENT_TIMESTAMP;

-- --------------------------------------------------------------------------
-- 9. 路由调用日志。覆盖四类 Agent、三种路由方式和成功/失败/超时。
-- --------------------------------------------------------------------------
WITH generated AS (
    SELECT
        user_no,
        call_no,
        ((call_no - 1) % :sa_sessions_per_user::integer) + 1 AS session_no,
        (ARRAY['order', 'product', 'general', 'recommend'])[((call_no - 1) % 4) + 1] AS agent_name,
        (ARRAY['keyword_match', 'semantic', 'llm_fallback'])[((call_no - 1) % 3) + 1] AS method_name,
        CASE WHEN call_no % 20 = 0 THEN 'TIMEOUT'
             WHEN call_no % 20 = 1 THEN 'FAILED'
             ELSE 'SUCCESS' END AS call_status
    FROM generate_series(1, :sa_user_count::integer) AS user_no
    CROSS JOIN generate_series(1, :sa_routes_per_user::integer) AS call_no
)
INSERT INTO routing_call_log (
    request_id, session_id, user_input, routed_agent, route_method,
    match_score, llm_received_question, response_summary, latency_ms,
    status, error_message, created_at
)
SELECT
    'LOAD-ROUTE-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(call_no::text, 4, '0'),
    'LOAD-SESSION-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(session_no::text, 2, '0'),
    CASE agent_name
        WHEN 'order' THEN '查询我的订单状态和物流进度'
        WHEN 'product' THEN '查询商品价格、库存和颜色'
        WHEN 'recommend' THEN '根据预算推荐一款合适的商品'
        ELSE '介绍一下售后服务流程'
    END,
    agent_name,
    method_name,
    (0.55 + ((call_no % 40)::numeric / 100))::numeric(5, 4),
    '并发验证请求 #' || user_no || '-' || call_no,
    CASE WHEN call_status = 'SUCCESS' THEN '请求处理完成' ELSE '请求已降级处理' END,
    CASE method_name WHEN 'keyword_match' THEN 35 + call_no % 80
                     WHEN 'semantic' THEN 120 + call_no % 400
                     ELSE 800 + call_no % 2200 END,
    call_status,
    CASE call_status WHEN 'TIMEOUT' THEN 'upstream timeout'
                     WHEN 'FAILED' THEN 'simulated dependency failure' END,
    CURRENT_TIMESTAMP - (((user_no * :sa_routes_per_user::integer + call_no) % 43200) || ' minutes')::interval
FROM generated
ON CONFLICT (request_id) WHERE request_id IS NOT NULL DO UPDATE SET
    session_id = EXCLUDED.session_id,
    routed_agent = EXCLUDED.routed_agent,
    route_method = EXCLUDED.route_method,
    match_score = EXCLUDED.match_score,
    latency_ms = EXCLUDED.latency_ms,
    status = EXCLUDED.status,
    error_message = EXCLUDED.error_message,
    created_at = EXCLUDED.created_at;

-- --------------------------------------------------------------------------
-- 10. 对话反馈。session_id 与前面的路由日志会话一致，可验证管理端关联查询。
-- --------------------------------------------------------------------------
INSERT INTO conversation_feedback (
    session_id, user_id, rating, feedback_text, intent_tag, agent_name, metadata, created_at
)
SELECT
    'LOAD-SESSION-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(feedback_no::text, 2, '0'),
    u.id,
    ((user_no + feedback_no) % 5) + 1,
    (ARRAY['回答准确', '响应速度符合预期', '流程清晰', '需要补充更多说明', '已转人工处理'])[((user_no + feedback_no - 2) % 5) + 1],
    (ARRAY['QUERY_ORDER', 'PRODUCT_QUERY', 'REFUND', 'RECOMMEND'])[((feedback_no - 1) % 4) + 1],
    (ARRAY['order', 'product', 'general', 'recommend'])[((feedback_no - 1) % 4) + 1],
    jsonb_build_object(
        'source', 'concurrency-fixture',
        'fixtureVersion', 'v1',
        'userNo', user_no,
        'feedbackNo', feedback_no
    ),
    CURRENT_TIMESTAMP - (((user_no + feedback_no) % 30) || ' days')::interval
FROM generate_series(1, :sa_user_count::integer) AS user_no
CROSS JOIN generate_series(1, :sa_feedback_per_user::integer) AS feedback_no
JOIN users u
  ON u.username = 'load_user_' || LPAD(user_no::text, 6, '0')
WHERE NOT EXISTS (
    SELECT 1 FROM conversation_feedback f
    WHERE f.session_id = 'LOAD-SESSION-' || LPAD(user_no::text, 6, '0') || '-' || LPAD(feedback_no::text, 2, '0')
      AND f.metadata->>'source' = 'concurrency-fixture'
);

-- 更新序列，避免后续业务写入与显式生成数据冲突。
SELECT setval(pg_get_serial_sequence('users', 'id'), COALESCE((SELECT MAX(id) FROM users), 1));
SELECT setval(pg_get_serial_sequence('user_sessions', 'id'), COALESCE((SELECT MAX(id) FROM user_sessions), 1));
SELECT setval(pg_get_serial_sequence('products', 'id'), COALESCE((SELECT MAX(id) FROM products), 1));
SELECT setval(pg_get_serial_sequence('orders', 'id'), COALESCE((SELECT MAX(id) FROM orders), 1));
SELECT setval(pg_get_serial_sequence('order_logistics', 'id'), COALESCE((SELECT MAX(id) FROM order_logistics), 1));
SELECT setval(pg_get_serial_sequence('order_refunds', 'id'), COALESCE((SELECT MAX(id) FROM order_refunds), 1));
SELECT setval(pg_get_serial_sequence('approval_records', 'id'), COALESCE((SELECT MAX(id) FROM approval_records), 1));
SELECT setval(pg_get_serial_sequence('user_coupons', 'id'), COALESCE((SELECT MAX(id) FROM user_coupons), 1));
SELECT setval(pg_get_serial_sequence('routing_call_log', 'id'), COALESCE((SELECT MAX(id) FROM routing_call_log), 1));
SELECT setval(pg_get_serial_sequence('conversation_feedback', 'id'), COALESCE((SELECT MAX(id) FROM conversation_feedback), 1));

COMMIT;

\echo 'SmartAssistant concurrency fixture generated.'
\echo 'Login sample: load_user_000001 / password'
\echo 'Order sample: ORD-LOAD000001003'
