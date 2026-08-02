-- 仅清理 concurrency_test_data.sql 生成的 LOAD-* / ORD-LOAD* / load_user_* 测试数据。
-- 不会删除最小演示数据或正常业务数据。
\set ON_ERROR_STOP on

BEGIN;

DELETE FROM conversation_feedback
WHERE metadata->>'source' = 'concurrency-fixture';

DELETE FROM routing_call_log
WHERE request_id LIKE 'LOAD-ROUTE-%';

DELETE FROM approval_records
WHERE order_id LIKE 'LOAD-ORD-%' OR order_id LIKE 'ORD-LOAD%';

DELETE FROM order_refunds
WHERE order_id LIKE 'LOAD-ORD-%' OR order_id LIKE 'ORD-LOAD%';

DELETE FROM order_logistics
WHERE order_id LIKE 'LOAD-ORD-%' OR order_id LIKE 'ORD-LOAD%';

DELETE FROM orders
WHERE order_id LIKE 'LOAD-ORD-%' OR order_id LIKE 'ORD-LOAD%';

DELETE FROM user_coupons
WHERE coupon_id LIKE 'LOAD-CPN-%';

DELETE FROM user_sessions
WHERE token_id LIKE 'LOAD-TOKEN-%';

DELETE FROM users
WHERE username LIKE 'load\_user\_%' ESCAPE '\';

DELETE FROM products
WHERE product_code LIKE 'LOAD-PROD-%';

COMMIT;

\echo 'SmartAssistant concurrency fixture removed.'
