-- Category-aware product discovery. Safe to run repeatedly.
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS category VARCHAR(50) NOT NULL DEFAULT '其他';

UPDATE products
   SET category = CASE
       WHEN UPPER(product_code) LIKE '%IPAD%'
            OR LOWER(product_name) LIKE '%ipad%'
            OR product_name LIKE '%平板%' THEN '平板电脑'
       WHEN UPPER(product_code) LIKE '%MACBOOK%'
            OR product_name LIKE '%笔记本%' THEN '笔记本电脑'
       WHEN UPPER(product_code) LIKE '%AIRPODS%'
            OR product_name LIKE '%耳机%' THEN '耳机'
       WHEN UPPER(product_code) LIKE '%IPHONE%'
            OR UPPER(product_code) LIKE '%GALAXY%'
            OR UPPER(product_code) LIKE '%XIAOMI%'
            OR product_name LIKE '%手机%' THEN '手机'
       WHEN UPPER(product_code) LIKE '%WATCH%'
            OR product_name LIKE '%手表%' THEN '智能手表'
       ELSE category
   END
 WHERE category IS NULL OR category = '' OR category = '其他';

CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);
