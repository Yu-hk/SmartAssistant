\set ON_ERROR_STOP on

BEGIN;

CREATE TABLE IF NOT EXISTS products (
    id           BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(50) NOT NULL UNIQUE,
    product_name VARCHAR(200) NOT NULL,
    price        DECIMAL(10, 2) NOT NULL,
    stock        VARCHAR(20) NOT NULL DEFAULT '充足',
    spec         TEXT,
    color        VARCHAR(200),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_products_code ON products(product_code);

CREATE TABLE IF NOT EXISTS inventory_snapshots (
    id           BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(50) NOT NULL REFERENCES products(product_code),
    warehouse    VARCHAR(50) NOT NULL,
    on_hand      INTEGER NOT NULL,
    reserved     INTEGER NOT NULL,
    quality_hold INTEGER NOT NULL,
    available    INTEGER NOT NULL,
    batch_id     VARCHAR(80) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inventory_snapshot UNIQUE (product_code, warehouse, batch_id)
);

CREATE INDEX IF NOT EXISTS idx_inventory_product
    ON inventory_snapshots(product_code);

CREATE TEMP TABLE e2e_products_stage (
    product_code TEXT,
    product_name TEXT,
    price TEXT,
    stock TEXT,
    spec TEXT,
    color TEXT
);

\copy e2e_products_stage FROM '/tmp/smartassistant-e2e-20260729/business-data/products.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

INSERT INTO products (product_code, product_name, price, stock, spec, color)
SELECT product_code, product_name, price::numeric, stock, spec, color
FROM e2e_products_stage
ON CONFLICT (product_code) DO UPDATE SET
    product_name = EXCLUDED.product_name,
    price = EXCLUDED.price,
    stock = EXCLUDED.stock,
    spec = EXCLUDED.spec,
    color = EXCLUDED.color;

CREATE TEMP TABLE e2e_inventory_stage (
    product_code TEXT,
    warehouse TEXT,
    on_hand TEXT,
    reserved TEXT,
    quality_hold TEXT,
    available TEXT,
    batch_id TEXT
);

\copy e2e_inventory_stage FROM '/tmp/smartassistant-e2e-20260729/business-data/inventory.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

INSERT INTO inventory_snapshots (
    product_code, warehouse, on_hand, reserved, quality_hold, available, batch_id
)
SELECT
    product_code,
    warehouse,
    on_hand::integer,
    reserved::integer,
    quality_hold::integer,
    available::integer,
    batch_id
FROM e2e_inventory_stage
ON CONFLICT (product_code, warehouse, batch_id) DO UPDATE SET
    on_hand = EXCLUDED.on_hand,
    reserved = EXCLUDED.reserved,
    quality_hold = EXCLUDED.quality_hold,
    available = EXCLUDED.available;

CREATE TEMP TABLE e2e_orders_stage (
    order_id TEXT,
    user_id TEXT,
    product_name TEXT,
    amount TEXT,
    status TEXT,
    carrier TEXT,
    tracking_no TEXT,
    product_type TEXT,
    contact_name TEXT,
    contact_phone TEXT,
    shipping_address TEXT,
    payment_method TEXT,
    created_at TEXT,
    updated_at TEXT
);

\copy e2e_orders_stage FROM '/tmp/smartassistant-e2e-20260729/business-data/orders.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

INSERT INTO orders (
    order_id, user_id, product_name, amount, status, carrier, tracking_no,
    product_type, contact_name, contact_phone, shipping_address, payment_method,
    created_at, updated_at
)
SELECT
    order_id,
    :'e2e_user_id'::bigint,
    product_name,
    amount::numeric,
    status,
    NULLIF(carrier, ''),
    NULLIF(tracking_no, ''),
    product_type,
    contact_name,
    contact_phone,
    shipping_address,
    payment_method,
    created_at::timestamp,
    updated_at::timestamp
FROM e2e_orders_stage
ON CONFLICT (order_id) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    product_name = EXCLUDED.product_name,
    amount = EXCLUDED.amount,
    status = EXCLUDED.status,
    carrier = EXCLUDED.carrier,
    tracking_no = EXCLUDED.tracking_no,
    product_type = EXCLUDED.product_type,
    contact_name = EXCLUDED.contact_name,
    contact_phone = EXCLUDED.contact_phone,
    shipping_address = EXCLUDED.shipping_address,
    payment_method = EXCLUDED.payment_method,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at;

CREATE TEMP TABLE e2e_logistics_stage (
    tracking_no TEXT,
    order_id TEXT,
    carrier TEXT,
    status TEXT,
    trajectory TEXT
);

\copy e2e_logistics_stage FROM '/tmp/smartassistant-e2e-20260729/business-data/logistics.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

INSERT INTO order_logistics (tracking_no, order_id, carrier, status, trajectory)
SELECT tracking_no, order_id, carrier, status, trajectory
FROM e2e_logistics_stage
ON CONFLICT (tracking_no) DO UPDATE SET
    order_id = EXCLUDED.order_id,
    carrier = EXCLUDED.carrier,
    status = EXCLUDED.status,
    trajectory = EXCLUDED.trajectory,
    updated_at = CURRENT_TIMESTAMP;

COMMIT;

SELECT 'products' AS dataset, COUNT(*) AS rows
FROM products WHERE product_code LIKE 'E2E-PROD-%'
UNION ALL
SELECT 'inventory', COUNT(*)
FROM inventory_snapshots WHERE batch_id LIKE 'E2E-INV-20260729-%'
UNION ALL
SELECT 'orders', COUNT(*)
FROM orders WHERE order_id LIKE 'ORD-E2E-%'
UNION ALL
SELECT 'logistics', COUNT(*)
FROM order_logistics WHERE order_id LIKE 'ORD-E2E-%'
ORDER BY dataset;
