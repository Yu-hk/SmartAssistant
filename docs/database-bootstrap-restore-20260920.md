# PG16 空库初始化与逻辑恢复验证（2026-09-20）

本轮推进整改清单中的“数据库迁移 / 恢复自动化”，范围限定为部署实际引用的 `docs/database/schema.sql` 与 `seed_data.sql`。**不代表所有历史增量迁移、全站恢复或跨节点容灾已完成。**

## 已确认的初始化错误

1. 部署声明使用 PG16 + pgvector，但 `schema.sql` 保留了 PG18 导出中的 `SET transaction_timeout = 0`。原文件在隔离 PG16.10 中失败，SQLSTATE `42704`。该参数在 [PG17 才加入](https://www.postgresql.org/docs/17/release-17.html)，移除这一不兼容设置，不修改服务端超时配置。
2. 只移除上述参数后，初始化继续失败，SQLSTATE `3F000`。导出脚本把 `search_path` 设为空，后面追加的画像清理任务表、外键引用和索引却没有限定 schema。修复显式使用 `public.*`，没有扩大 `search_path` 或修改任何授权。[PG16 CREATE SCHEMA 规则](https://www.postgresql.org/docs/16/sql-createschema.html)。

两次 SQL 失败报告均保留，未忽略 `psql` 错误继续执行。修复只影响后续空库初始化文件，没有向现有生产数据库应用这些 DDL。

## 可执行验证

```sh
python -m unittest discover -s scripts -p test_database_bootstrap_drill.py -v
# 先准备可信的本地镜像；脚本自身不拉镜像、不连接任意远程 DB。
python scripts/database_bootstrap_drill.py --repo . \
  --image pgvector/pgvector:0.8.0-pg16 --report new-bootstrap-report.json
```

验证器固定使用自己创建的随机命名、唯一标签临时容器；`--network none`、无端口、无宿主绑定或命名卷，数据库目录为有大小限制的 tmpfs。镜像先解析成完整 ID。容器只含公开种子及脚本生成的测试库，不读取生产账号、连接配置、真实备份或用户记录。

执行步骤：

1. 等待临时数据库最终 TCP 服务可用，确认 PG16。
2. 原样执行两个仓库 SQL 文件，`ON_ERROR_STOP=1`；核验关键画像表、6 件商品、5 条订单、9 张优惠券。
3. 采集公开表的行数/内容摘要、列元数据、约束、索引以及序列的 `last_value/is_called`。
4. 使用真实 `pg_dump` 导出，恢复到另一个新建测试库，再逐项比较。不是只比较表数量。
5. 无论通过或失败，只在 ID、唯一标签及隔离配置匹配时移除自己创建的容器；失败报告保留，既有报告拒绝覆盖，清理失败不能标为通过。

CI 新增 `PG16 bootstrap and logical restore`，每次 PR 从空库运行完整流程并上传报告；脚本或报告缺失、任一契约失败都会使作业失败。

## 校验器纠偏

首次恢复后，数据、列及序列一致，但约束和一个部分索引的文本不同。现场证明 `pg_dump` 重解析将无长度限制的 `varchar` 字符串常量数组整体转 `text[]`，写成逐个元素转 `text`。

校验器只规范化这种精确形式，保留常量内容和顺序；不会删除所有类型转换、忽略约束或放宽结果。长度限制、NULL、列引用及其他类型保持原样，新增负向回归。无长度限制字符串类型的依据见 [PG16 字符类型](https://www.postgresql.org/docs/16/datatype-character.html)。原失败报告仍保留。

Podman 将 tmpfs 配置放在 `HostConfig.Tmpfs` 而非 `Mounts`，首轮隔离预检因此拒绝了尚未启动的容器；核验后仅移除了该自建空容器，增加兼容回归，未放宽对真实绑定/命名卷和网络的拒绝。

## 验收边界

服务器 PG16.10 实测通过：public schema 下 33 张基础表、20 条种子数据，列元数据、约束、索引、序列及数据摘要一致。14 项本地防护回归及同一组服务器回归通过。目录 `/opt/smart-assistant/releases/database-bootstrap-20260920` 保留 `before-sql.json`（42704）、`parameter-fixed.json`（3F000）、恢复文本差异诊断及最终 `verified.json`。所有本轮临时容器已删除，没有保留测试库或测试数据卷。

最终报告 SHA-256：`29e12a476b858b971f05a664ac7d984c71ef65738f85d31c794aa8440e7fa200`；服务器运行脚本与本地一致，SHA-256 为 `163a1695eeed14ccc9c74b3d98a5b9a6bfc1c17bc55e0c1d0db6d7d6f1fc449a`。验证后 8 个应用服务及公网健康均 UP，生产 PG 仍为运行五周的原容器。

- 本地 Docker 引擎未运行，本地验证为 Python 防护契约；不宣称本地真实 PG 集成通过。真实 PG 演练在服务器禁网临时容器及 CI 中进行。
- 检查的是这套基础 SQL 和公开种子，未自动执行 `docs/database/migrations`。部分历史迁移含业务数据更新、会话状态修整及恢复控制来源前置条件，需独立设计逐版本升级测试。
- 未证明触发器/函数的全部运行行为、角色权限恢复、真实用户规模性能，或 Redis/MQ/文件/独立恢复控制目录的全站一致恢复。
- 不替换生产 PostgreSQL、应用 JAR 或 DNS，不重启业务服务、不生成新生产备份、不删除已有备份。
- 浏览器连接依然返回 `nodeRepl.fetch request failed`；隐私页面真实视觉验收保持未完成，不能拿本轮数据库测试代替。
