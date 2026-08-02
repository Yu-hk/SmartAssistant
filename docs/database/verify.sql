\set ON_ERROR_STOP on

-- 必需扩展
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'required extension vector is missing';
    END IF;
END $$;

-- 核心业务表
DO $$
DECLARE
    required_table TEXT;
BEGIN
    FOREACH required_table IN ARRAY ARRAY[
        'users', 'user_sessions', 'orders', 'order_logistics',
        'order_refunds', 'approval_records', 'user_coupons', 'products',
        'routing_call_log', 'conversation_feedback', 'experience_embeddings'
    ] LOOP
        IF to_regclass('public.' || required_table) IS NULL THEN
            RAISE EXCEPTION 'required table public.% is missing', required_table;
        END IF;
    END LOOP;
END $$;

-- 容易发生脚本漂移的关键字段
DO $$
DECLARE
    missing_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO missing_count
    FROM (VALUES
        ('products', 'colors'),
        ('orders', 'request_id'),
        ('routing_call_log', 'request_id'),
        ('conversation_feedback', 'metadata')
    ) AS required(table_name, column_name)
    WHERE NOT EXISTS (
        SELECT 1
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
          AND c.table_name = required.table_name
          AND c.column_name = required.column_name
    );

    IF missing_count > 0 THEN
        RAISE EXCEPTION '% required columns are missing', missing_count;
    END IF;
END $$;

SELECT 'users' AS dataset, COUNT(*) AS row_count FROM users
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_logistics', COUNT(*) FROM order_logistics
UNION ALL SELECT 'order_refunds', COUNT(*) FROM order_refunds
UNION ALL SELECT 'user_coupons', COUNT(*) FROM user_coupons
UNION ALL SELECT 'conversation_feedback', COUNT(*) FROM conversation_feedback
ORDER BY dataset;

SELECT order_id, product_name, status, carrier, tracking_no
FROM orders
WHERE order_id LIKE 'ORD-%'
ORDER BY order_id
LIMIT 10;
