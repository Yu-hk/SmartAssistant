# 商品结构化特征与本地测试

日期：2026-09-14。状态：本地实现、自动化回归通过；未执行线上迁移、未部署、未推送。

## 字段与口径

迁移文件：`database/migrations/20260914_add_product_structured_features.sql`。该脚本只新增可空字段及说明，不更新现有商品，不注入虚构参数。

| 数据库字段 | API 中的 features 字段 | 口径 |
| --- | --- | --- |
| weight_grams | weightGrams | 克；设备净重，耳机为双耳合计，不含充电盒、包装及附件 |
| battery_life_hours | batteryLifeHours | 小时；标称续航，不是电池容量，也不是含充电盒的总续航 |
| battery_life_scenario | batteryLifeScenario | video_playback / audio_anc_on / audio_anc_off / mixed_use |
| noise_cancelling | noiseCancelling | 主动降噪；true 支持、false 明确不支持、null 未知 |
| feature_source | source | 厂商规格页或经核验目录记录的来源引用 |
| features_verified_at | verifiedAt | 核验时间；不保证实际使用达到标称时长 |

缺失值不默认填 0 或 false。来源或核验记录不完整时，参数不能满足显式特征条件。旧目录通过 JSON 字段读取保持兼容，旧 API 记录没有 features 时为 UNKNOWN。

## 筛选与推荐

1. 解析明确的重量上限、续航下限、续航场景及主动降噪要求；kg/公斤归一化为克。
2. “轻便”“长续航”未给门槛、续航未给场景、条件二选一或边界表达暂不支持时，先澄清，不自行设门槛。
3. SQL 将特征条件与价格、品类、库存条件结合，在 ORDER BY / LIMIT 之前筛选；缺失参数不会满足条件。
4. 未给品类时，使用完整目录的过滤后 DISTINCT 品类查询，不以热门前几条推断唯一品类。品类唯一才继续，否则澄清。
5. Flash/Pro 接收带来源的 features；程序再次验证模型选中商品满足特征，拒绝不符合条件的选择。
6. 推荐理由展示实际参数、测试场景、来源和核验时间；默认仍只判断是否超预算，不展示差额。

修复了单位交叉问题：“重量不超过1.3kg”不会被金额解析器当作1300元预算。对已有商品询问“重量多少”“支持主动降噪吗”，不会强行改为泛化推荐；精确商品详情工具也会返回已记录的结构化参数。

## 测试数据与结果

`smart-assistant-product/src/test/resources/product-structured-features-fixture.json` 包含 8 款明确标注“虚构”的测试商品，仅用于自动化测试，不复制到生产目录，也不为真实型号编造参数。

| 提问/场景 | 测试结果 |
| --- | --- |
| 预算5000元，笔记本电脑重量≤1.3kg，视频播放续航≥10小时 | 选择测试轻便本A：3999元、1200克、视频播放12小时；排除高热度但2100克/8小时的B |
| 同上，但未给品类 | 电脑和平板都匹配，先询问品类；limit=1 不改变澄清判断 |
| 支持主动降噪，预算1000元，重量≤300克，开启降噪听歌续航≥25小时 | 唯一匹配耳机品类，返回虚构耳机A：699元、250克、30小时 |
| 只给“轻便、续航长” | 询问品类、阈值、续航测试场景 |
| 综合使用16小时 vs 视频播放最低10小时 | 不跨场景判定匹配 |
| 降噪 unknown / false / 无来源 | unknown 不等于 false，无来源不能作为匹配证据 |
| 模型选中超过重量上限或场景不同的高热度商品 | 程序拒绝该结构化选择 |

验证命令：Maven `-pl smart-assistant-product -am test`，指定商品推荐/字段/忠实度回归选择器，另执行 `python -m unittest discover -s scripts -p test_chat_telemetry.py -v`。

结果：Product 163 条、Common 38 条、Python 5 条，共 206 条通过。新增 `ProductStructuredFeaturesTest` 21 条、`JdbcProductFeaturesTest` 3 条及双模型结构化字段传递测试 1 条，已纳入 CI 对应回归入口。

限制：本地 JDBC 测试验证 SQL 条件、参数绑定、字段映射及完整品类查询，使用模拟 JdbcTemplate；未在 PostgreSQL 实例执行此次迁移。Flash/Pro 流程测试使用模型桩，不等同于真实模型线上端到端验证。部署前需执行迁移，并依据真实来源回填要演示的商品参数；未回填的商品仍按未知处理。
