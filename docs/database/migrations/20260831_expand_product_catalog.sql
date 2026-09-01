-- Expand the demo catalog and add explicit recommendation evidence.
-- Idempotent: safe to execute repeatedly. Metrics are site-local catalog snapshots,
-- not claims about external market-wide sales.

BEGIN;

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS color VARCHAR(200),
    ADD COLUMN IF NOT EXISTS market_price NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS sales_30d BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rating NUMERIC(2, 1),
    ADD COLUMN IF NOT EXISTS review_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS metrics_source VARCHAR(32) NOT NULL DEFAULT 'catalog',
    ADD COLUMN IF NOT EXISTS metrics_updated_at TIMESTAMP;

-- Older installations used `colors`; keep the canonical `color` column while
-- remaining safe on databases where the legacy column never existed.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'products'
           AND column_name = 'colors'
    ) THEN
        EXECUTE 'UPDATE products SET color = COALESCE(NULLIF(color, ''''), colors)';
    END IF;
END $$;

UPDATE products
   SET category = CASE product_code
       WHEN 'ADIDAS-ULTRA' THEN '运动鞋服'
       WHEN 'NIKE-AJ1' THEN '运动鞋服'
       WHEN 'BOOK-AI' THEN '图书'
       WHEN 'BOOK-CSAPP' THEN '图书'
       WHEN 'BOOK-DP' THEN '图书'
       WHEN 'CHERRIES-5KG' THEN '生鲜食品'
       WHEN 'STEAK-RIBEYE' THEN '生鲜食品'
       WHEN 'COFFEE-BEANS' THEN '食品饮料'
       WHEN 'CUSTOM-CUP' THEN '家居日用'
       WHEN 'CUSTOM-PHONE' THEN '手机配件'
       WHEN 'DELL-XPS-15' THEN '笔记本电脑'
       WHEN 'MBP-14-M4' THEN '笔记本电脑'
       WHEN 'PS5-PRO' THEN '游戏娱乐'
       WHEN 'SWITCH-2' THEN '游戏娱乐'
       WHEN 'SONY-XM6' THEN '耳机'
       WHEN 'UNIQLO-UV' THEN '服饰鞋包'
       WHEN 'ZARA-COAT' THEN '服饰鞋包'
       ELSE category
   END
 WHERE product_code IN (
       'ADIDAS-ULTRA', 'NIKE-AJ1', 'BOOK-AI', 'BOOK-CSAPP', 'BOOK-DP',
       'CHERRIES-5KG', 'STEAK-RIBEYE', 'COFFEE-BEANS', 'CUSTOM-CUP',
       'CUSTOM-PHONE', 'DELL-XPS-15', 'MBP-14-M4', 'PS5-PRO', 'SWITCH-2',
       'SONY-XM6', 'UNIQLO-UV', 'ZARA-COAT'
 );

INSERT INTO products
    (product_code, product_name, price, stock, category, spec, color)
VALUES
    ('HUAWEI-MATEPAD-PRO', '华为 MatePad Pro 12.2', 4299.00, '充足', '平板电脑', '12.2英寸柔性OLED、星闪连接、10000mAh电池', '砚黑/宣白/流金'),
    ('IPAD-AIR-M3', 'iPad Air M3 11英寸', 4799.00, '充足', '平板电脑', 'M3芯片、11英寸Liquid视网膜屏、128GB', '深空灰/蓝色'),
    ('XIAOMI-PAD-7', '小米平板 7', 1999.00, '充足', '平板电脑', '11.2英寸高刷屏、骁龙平台、128GB', '黑色/蓝色'),
    ('HUAWEI-WATCH-GT5', '华为 WATCH GT 5', 1588.00, '充足', '智能手表', '健康监测、户外运动模式、长续航', '曜石黑/冰川白'),
    ('XIAOMI-WATCH-S4', '小米 Watch S4', 999.00, '充足', '智能手表', '1.43英寸AMOLED、双频定位、健康监测', '黑色/银色'),
    ('SALMON-NORWAY', '挪威大西洋三文鱼 500g', 138.00, '充足', '生鲜食品', '冷链配送、去皮去刺、适合煎烤', '原切'),
    ('BLUEBERRY-BOX', '云南蓝莓 4盒装', 89.00, '充足', '生鲜食品', '大果径、冷链保鲜、即食装', '蓝紫色'),
    ('TEA-LONGJING', '明前龙井茶 250g', 298.00, '充足', '食品饮料', '绿茶、豆香型、独立密封罐', '嫩绿色'),
    ('MILK-ORGANIC', '有机纯牛奶 250ml×24盒', 119.00, '充足', '食品饮料', '有机认证、常温奶、整箱装', '原味'),
    ('PILLOW-LATEX', '天然乳胶枕 60×40cm', 199.00, '充足', '家居日用', '波浪护颈、透气枕套、可拆洗', '白色'),
    ('TOWEL-COTTON', '新疆长绒棉浴巾套装', 129.00, '充足', '家居日用', 'A类标准、高克重、两浴巾两毛巾', '灰色/米色'),
    ('POWERBANK-20000', '20000mAh 65W快充移动电源', 239.00, '充足', '手机配件', '双向快充、自带Type-C线、数显电量', '黑色/白色'),
    ('CHARGER-100W', '氮化镓 100W 四口充电器', 299.00, '充足', '手机配件', '三Type-C一USB-A、多设备功率分配', '白色'),
    ('XBOX-SERIES-X', 'Xbox Series X 1TB', 3899.00, '紧张', '游戏娱乐', '1TB固态硬盘、4K游戏、光驱版', '黑色'),
    ('NINTENDO-SWITCH-2', 'Nintendo Switch 2', 3499.00, '充足', '游戏娱乐', '掌机主机双模式、256GB、双手柄', '黑色'),
    ('PS5-SLIM', 'PlayStation 5 Slim', 3599.00, '紧张', '游戏娱乐', '1TB固态硬盘、光驱版、4K游戏', '白色'),
    ('NORTHFACE-JACKET', '北面户外冲锋衣', 1299.00, '充足', '服饰鞋包', '防风防泼水、可调节风帽、常规版型', '黑色/卡其色'),
    ('UNIQLO-UV-JACKET', '优衣库防晒衣', 299.00, '充足', '服饰鞋包', 'UPF50+、轻量透气、便携收纳', '白色/浅蓝色'),
    ('ZARA-WOOL-COAT', 'ZARA 羊毛混纺大衣', 899.00, '充足', '服饰鞋包', '羊毛混纺、中长款、常规版型', '黑色/驼色'),
    ('LI-NING-RUNNER', '李宁超轻跑鞋', 599.00, '充足', '运动鞋服', '轻量中底、透气鞋面、日常慢跑', '白蓝/黑红'),
    ('ADIDAS-ULTRABOOST', 'Adidas Ultraboost 跑鞋', 999.00, '充足', '运动鞋服', 'Boost中底、针织鞋面、缓震跑步', '黑白/蓝色'),
    ('NIKE-PEGASUS', 'Nike Pegasus 跑鞋', 699.00, '充足', '运动鞋服', 'ReactX泡棉、透气鞋面、日常训练', '白色/黑色'),
    ('REDMI-BUDS-6-PRO', 'Redmi Buds 6 Pro', 399.00, '充足', '耳机', '主动降噪、双设备连接、长续航', '黑色/白色'),
    ('SONY-WF-C700N', 'Sony WF-C700N', 799.00, '充足', '耳机', '主动降噪、环境声模式、轻量入耳式', '黑色/白色/紫色'),
    ('SOUNDCORE-LIBERTY-4NC', 'Soundcore Liberty 4 NC', 699.00, '充足', '耳机', '自适应降噪、空间音频、无线充电', '黑色/白色'),
    ('LENOVO-XIAOXIN-PRO', '联想小新 Pro 14', 5299.00, '充足', '笔记本电脑', '14英寸高刷屏、32GB内存、1TB固态', '深空灰'),
    ('MACBOOK-AIR-M4', 'MacBook Air 13 M4', 8499.00, '充足', '笔记本电脑', 'M4芯片、16GB内存、512GB固态', '午夜色/星光色'),
    ('PIXEL-9-PRO', 'Google Pixel 9 Pro', 6999.00, '充足', '手机', '高刷新率OLED、长焦影像、AI摄影', '黑色/灰色'),
    ('ROBOROCK-S8', '石头扫拖机器人 S8', 3299.00, '充足', '智能家居', '激光导航、自动集尘、扫拖一体', '白色/黑色'),
    ('MI-SMART-LOCK', '小米智能门锁 Pro', 1699.00, '充足', '智能家居', '指纹识别、密码开锁、门铃联动', '深空灰'),
    ('HUAWEI-ROUTER', '华为凌霄 Wi-Fi 7 路由器', 699.00, '充足', '智能家居', '双频聚合、全屋覆盖、儿童上网保护', '白色'),
    ('MIDEA-AC', '美的一级能效空调 1.5匹', 2899.00, '充足', '家用电器', '变频冷暖、自清洁、智能控制', '白色'),
    ('HAIER-WASHER', '海尔滚筒洗衣机 10kg', 2499.00, '充足', '家用电器', '超薄机身、蒸汽除菌、智能投放', '极夜灰'),
    ('DYSON-V15', '戴森 V15 Detect 吸尘器', 4490.00, '充足', '家用电器', '激光显尘、智能吸力、整机过滤', '金色/银色'),
    ('SUPOR-RICE', '苏泊尔 IH 电饭煲 4L', 699.00, '充足', '厨房电器', 'IH立体加热、多功能菜单、预约烹饪', '曜石黑'),
    ('JOYOUNG-BLENDER', '九阳破壁机 1.75L', 599.00, '充足', '厨房电器', '降噪罩、自动清洗、冷热双打', '灰色'),
    ('GALANZ-MICROWAVE', '格兰仕微蒸烤一体机 32L', 1899.00, '充足', '厨房电器', '微波蒸烤、空气炸、智能菜单', '黑色'),
    ('LOGITECH-MX-KEYS', '罗技 MX Keys S 键盘', 899.00, '充足', '电脑办公', '多设备切换、背光、蓝牙连接', '石墨灰'),
    ('DELL-U2723', 'Dell U2723QE 27英寸显示器', 3499.00, '充足', '电脑办公', '4K IPS Black、USB-C 90W、出厂校色', '黑色'),
    ('EPSON-L3258', '爱普生 L3258 墨仓打印机', 1099.00, '充足', '电脑办公', '打印复印扫描、无线连接、低成本耗材', '白色'),
    ('SONY-A7C2', 'Sony A7C II 全画幅相机', 13999.00, '紧张', '摄影摄像', '3300万像素、五轴防抖、AI对焦', '黑色/银色'),
    ('DJI-OSMO-3', 'DJI Osmo Pocket 3', 3499.00, '充足', '摄影摄像', '一英寸传感器、三轴云台、4K视频', '黑色'),
    ('INSTA360-X4', 'Insta360 X4 全景相机', 3498.00, '充足', '摄影摄像', '8K全景、防抖、防水机身', '黑色'),
    ('LOREAL-SERUM', '欧莱雅玻色因精华 30ml', 329.00, '充足', '美妆个护', '抗皱保湿、轻盈质地、按压瓶', '透明'),
    ('SKII-ESSENCE', 'SK-II 神仙水 230ml', 1590.00, '充足', '美妆个护', '精华水、保湿修护、玻璃瓶装', '透明'),
    ('PHILIPS-SHAVER', '飞利浦电动剃须刀 S9000', 1999.00, '充足', '美妆个护', '智能感应、干湿双剃、无线清洁', '深蓝色'),
    ('PAMPERS-L', '帮宝适一级帮纸尿裤 L 码', 189.00, '充足', '母婴用品', '轻薄透气、柔软表层、拉拉裤', '白色'),
    ('APTAMIL-3', '爱他美卓萃 3 段奶粉 900g', 369.00, '充足', '母婴用品', '幼儿配方、罐装、适合1至3岁', '金色'),
    ('GOODBABY-STROLLER', '好孩子轻便婴儿推车', 1099.00, '充足', '母婴用品', '一键收车、可登机、五点式安全带', '灰色/蓝色'),
    ('ROYALCANIN-CAT', '皇家室内成猫粮 10kg', 699.00, '充足', '宠物用品', '室内成猫配方、密封袋装', '原味'),
    ('PETKIT-FEEDER', '小佩智能喂食器', 499.00, '充足', '宠物用品', '定时定量、远程控制、缺粮提醒', '白色'),
    ('PIDAN-LITTER', '混合猫砂 6L×4包', 149.00, '充足', '宠物用品', '豆腐膨润土混合、快速结团、低粉尘', '原味'),
    ('BASEUS-CAR-CHARGER', '倍思 65W 双口车载充电器', 129.00, '充足', '汽车用品', 'Type-C快充、双口输出、电压显示', '黑色'),
    ('70MAI-DASHCAM', '70迈 4K 行车记录仪', 899.00, '充足', '汽车用品', '4K夜视、停车监控、手机互联', '黑色'),
    ('MICHELIN-INFLATOR', '米其林便携充气泵', 399.00, '充足', '汽车用品', '数显胎压、预设充停、应急照明', '黑色'),
    ('OMRON-BP', '欧姆龙上臂式电子血压计', 399.00, '充足', '健康护理', '智能加压、心率检测、双人记忆', '白色'),
    ('YUWELL-OXIMETER', '鱼跃指夹式血氧仪', 169.00, '充足', '健康护理', '血氧与脉率检测、彩色显示', '白色'),
    ('BEURER-MASSAGER', 'Beurer 颈肩按摩器', 599.00, '充足', '健康护理', '正反揉捏、热敷、力度调节', '灰色'),
    ('SAMSONITE-24', '新秀丽 24 英寸旅行箱', 1399.00, '充足', '旅行箱包', 'PC箱体、万向轮、TSA密码锁', '黑色/银色'),
    ('OSPREY-DAYLITE', 'Osprey Daylite 20L 双肩包', 599.00, '充足', '旅行箱包', '轻量背负、水袋兼容、多隔层', '黑色/绿色'),
    ('NATUREHIKE-TENT', '挪客云尚双人帐篷', 899.00, '充足', '旅行箱包', '双层防雨、铝合金杆、轻量收纳', '绿色'),
    ('LEGO-CLASSIC', '乐高经典创意积木盒', 399.00, '充足', '玩具乐器', '多色基础颗粒、创意拼搭、收纳盒', '多色'),
    ('YAMAHA-P125', '雅马哈 P-125 数码钢琴', 3899.00, '充足', '玩具乐器', '88键重锤、钢琴音源、双扬声器', '黑色/白色'),
    ('FENDER-PLAYER', 'Fender Player 电吉他', 6299.00, '紧张', '玩具乐器', '双单双拾音器、枫木琴颈、标准琴体', '日落色/黑色')
ON CONFLICT (product_code) DO UPDATE SET
    product_name = EXCLUDED.product_name,
    price = EXCLUDED.price,
    stock = EXCLUDED.stock,
    category = EXCLUDED.category,
    spec = EXCLUDED.spec,
    color = EXCLUDED.color;

-- Give every production catalog item a complete, deterministic site-local snapshot.
UPDATE products
   SET market_price = COALESCE(market_price,
           ROUND(price * (1.06 + MOD(ABS(hashtext(product_code)::BIGINT), 15)::NUMERIC / 100), 0)),
       sales_30d = CASE WHEN sales_30d > 0 THEN sales_30d
           ELSE 180 + MOD(ABS(hashtext(product_code)::BIGINT), 850) END,
       rating = COALESCE(rating,
           4.2 + MOD(ABS(hashtext(product_code || '-rating')::BIGINT), 8)::NUMERIC / 10),
       review_count = CASE WHEN review_count > 0 THEN review_count
           ELSE (180 + MOD(ABS(hashtext(product_code)::BIGINT), 850))
                * (2 + MOD(ABS(hashtext(product_code || '-reviews')::BIGINT), 4)) END,
       metrics_source = 'catalog-seed-20260831',
       metrics_updated_at = CURRENT_TIMESTAMP
 WHERE UPPER(product_code) NOT LIKE 'LOAD-PROD-%'
   AND UPPER(product_code) NOT LIKE 'E2E-PROD-%';

UPDATE products
   SET market_price = ROUND(market_price, 0)
 WHERE metrics_source = 'catalog-seed-20260831'
   AND market_price IS NOT NULL;

-- Stable leading products ensure global popularity questions have a clear ranking.
UPDATE products AS p
   SET sales_30d = v.sales_30d,
       rating = v.rating,
       review_count = v.review_count,
       metrics_source = 'catalog-seed-20260831',
       metrics_updated_at = CURRENT_TIMESTAMP
  FROM (VALUES
      ('XIAOMI-15', 1980::BIGINT, 4.8::NUMERIC, 8620::BIGINT),
      ('AIRPODS-PRO', 1880::BIGINT, 4.9::NUMERIC, 9200::BIGINT),
      ('ROBOROCK-S8', 1760::BIGINT, 4.8::NUMERIC, 7350::BIGINT),
      ('SUPOR-RICE', 1680::BIGINT, 4.8::NUMERIC, 8100::BIGINT),
      ('ADIDAS-ULTRA', 1590::BIGINT, 4.7::NUMERIC, 6800::BIGINT),
      ('OMRON-BP', 1510::BIGINT, 4.8::NUMERIC, 6250::BIGINT),
      ('LEGO-CLASSIC', 1450::BIGINT, 4.9::NUMERIC, 7400::BIGINT),
      ('HUAWEI-MATEPAD-PRO', 1380::BIGINT, 4.7::NUMERIC, 5690::BIGINT)
      ,('REDMI-BUDS-6-PRO', 1360::BIGINT, 4.8::NUMERIC, 6100::BIGINT)
      ,('SONY-WF-C700N', 1210::BIGINT, 4.7::NUMERIC, 4820::BIGINT)
      ,('SOUNDCORE-LIBERTY-4NC', 1120::BIGINT, 4.7::NUMERIC, 4510::BIGINT)
  ) AS v(product_code, sales_30d, rating, review_count)
 WHERE p.product_code = v.product_code;

ALTER TABLE products DROP CONSTRAINT IF EXISTS products_rating_check;
ALTER TABLE products ADD CONSTRAINT products_rating_check
    CHECK (rating IS NULL OR (rating >= 0 AND rating <= 5));
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_review_count_check;
ALTER TABLE products ADD CONSTRAINT products_review_count_check CHECK (review_count >= 0);
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_sales_30d_check;
ALTER TABLE products ADD CONSTRAINT products_sales_30d_check CHECK (sales_30d >= 0);

CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);
CREATE INDEX IF NOT EXISTS idx_products_sales_30d ON products(sales_30d DESC);
CREATE INDEX IF NOT EXISTS idx_products_rating ON products(rating DESC);

COMMENT ON COLUMN products.sales_30d IS '站内近30天销量快照；不代表外部市场销量';
COMMENT ON COLUMN products.rating IS '站内商品评分，0至5分';
COMMENT ON COLUMN products.review_count IS '站内有效评价数量';
COMMENT ON COLUMN products.market_price IS '用于站内价格对比的参考价';

COMMIT;
