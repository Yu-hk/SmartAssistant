-- SmartAssistant 多并发数据集完整性校验（只读）
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

CREATE TEMP TABLE sa_load_checks AS
WITH checks(dataset, actual_rows, expected_min_rows) AS (
    SELECT 'users', COUNT(*), :sa_user_count::bigint
      FROM users WHERE username LIKE 'load\_user\_%' ESCAPE '\'
    UNION ALL
    SELECT 'user_sessions', COUNT(*), (:sa_user_count::bigint * :sa_sessions_per_user::bigint)
      FROM user_sessions WHERE token_id LIKE 'LOAD-TOKEN-%'
    UNION ALL
    SELECT 'products', COUNT(*), :sa_product_count::bigint
      FROM products WHERE product_code LIKE 'LOAD-PROD-%'
    UNION ALL
    SELECT 'orders', COUNT(*), (:sa_user_count::bigint * :sa_orders_per_user::bigint)
      FROM orders WHERE order_id LIKE 'ORD-LOAD%'
    UNION ALL
    SELECT 'order_logistics', COUNT(*),
           (:sa_user_count::bigint * GREATEST(1, FLOOR(:sa_orders_per_user::numeric / 6))::bigint)
      FROM order_logistics WHERE order_id LIKE 'ORD-LOAD%'
    UNION ALL
    SELECT 'order_refunds', COUNT(*),
           (:sa_user_count::bigint * GREATEST(1, FLOOR(:sa_orders_per_user::numeric / 6))::bigint)
      FROM order_refunds WHERE order_id LIKE 'ORD-LOAD%'
    UNION ALL
    SELECT 'approval_records', COUNT(*),
           (:sa_user_count::bigint * GREATEST(1, FLOOR(:sa_orders_per_user::numeric / 6))::bigint)
      FROM approval_records WHERE order_id LIKE 'ORD-LOAD%'
    UNION ALL
    SELECT 'user_coupons', COUNT(*), (:sa_user_count::bigint * :sa_coupons_per_user::bigint)
      FROM user_coupons WHERE coupon_id LIKE 'LOAD-CPN-%'
    UNION ALL
    SELECT 'routing_call_log', COUNT(*), (:sa_user_count::bigint * :sa_routes_per_user::bigint)
      FROM routing_call_log WHERE request_id LIKE 'LOAD-ROUTE-%'
    UNION ALL
    SELECT 'conversation_feedback', COUNT(*), (:sa_user_count::bigint * :sa_feedback_per_user::bigint)
      FROM conversation_feedback WHERE metadata->>'source' = 'concurrency-fixture'
)
SELECT dataset, actual_rows, expected_min_rows, actual_rows >= expected_min_rows AS passed
FROM checks;

TABLE sa_load_checks;

SELECT BOOL_AND(passed) AS sa_all_row_counts_ok FROM sa_load_checks \gset
\if :sa_all_row_counts_ok
\else
\echo 'ERROR: one or more concurrency datasets are below the expected size.'
\quit 3
\endif

CREATE TEMP TABLE sa_integrity_checks AS
WITH checks(check_name, violation_count) AS (
    SELECT 'orphan user sessions', COUNT(*)
      FROM user_sessions s LEFT JOIN users u ON u.id = s.user_id
     WHERE s.token_id LIKE 'LOAD-TOKEN-%' AND u.id IS NULL
    UNION ALL
    SELECT 'orphan logistics', COUNT(*)
      FROM order_logistics l LEFT JOIN orders o ON o.order_id = l.order_id
     WHERE l.order_id LIKE 'ORD-LOAD%' AND o.id IS NULL
    UNION ALL
    SELECT 'orphan refunds', COUNT(*)
      FROM order_refunds r LEFT JOIN orders o ON o.order_id = r.order_id
     WHERE r.order_id LIKE 'ORD-LOAD%' AND o.id IS NULL
    UNION ALL
    SELECT 'orphan coupons', COUNT(*)
      FROM user_coupons c LEFT JOIN users u ON u.id = c.user_id
     WHERE c.coupon_id LIKE 'LOAD-CPN-%' AND u.id IS NULL
    UNION ALL
    SELECT 'duplicate load order request ids', COUNT(*)
      FROM (
          SELECT request_id FROM orders
          WHERE request_id LIKE 'LOAD-ORDER-REQ-%'
          GROUP BY request_id HAVING COUNT(*) > 1
      ) duplicates
)
SELECT check_name, violation_count, violation_count = 0 AS passed FROM checks;

TABLE sa_integrity_checks;

SELECT BOOL_AND(passed) AS sa_all_integrity_ok FROM sa_integrity_checks \gset
\if :sa_all_integrity_ok
\else
\echo 'ERROR: concurrency fixture contains referential-integrity violations.'
\quit 4
\endif

-- 场景覆盖统计。
SELECT 'order_status' AS dimension, status AS value, COUNT(*) AS row_count
FROM orders WHERE order_id LIKE 'ORD-LOAD%'
GROUP BY status
UNION ALL
SELECT 'session_state',
       CASE WHEN is_revoked THEN 'revoked'
            WHEN NOT is_active OR expires_at < CURRENT_TIMESTAMP THEN 'inactive_or_expired'
            ELSE 'active' END,
       COUNT(*)
FROM user_sessions WHERE token_id LIKE 'LOAD-TOKEN-%'
GROUP BY 2
UNION ALL
SELECT 'approval_status', status, COUNT(*)
FROM approval_records WHERE order_id LIKE 'ORD-LOAD%'
GROUP BY status
UNION ALL
SELECT 'route_status', status, COUNT(*)
FROM routing_call_log WHERE request_id LIKE 'LOAD-ROUTE-%'
GROUP BY status
ORDER BY dimension, value;

\echo 'Concurrency fixture verification passed.'
