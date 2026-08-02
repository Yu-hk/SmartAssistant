# SmartAssistant 项目运行与演示手册

## 1. 准备清单

| 类别 | 必需项 | 默认值或入口 |
|---|---|---|
| 运行时 | JDK 21、Maven Wrapper、Node.js 18+、Docker Compose v2 | 根目录 |
| 基础设施 | Redis、Nacos、PostgreSQL + pgvector、Zipkin | `docker-compose.dev.yml` |
| 模型 | Ollama 模型、BGE 1024 维向量模型 | `models/`、Embedding Service |
| 密钥 | `POSTGRES_PASSWORD`、`JWT_SECRET`；云模型按需配置 | `.env.example` |
| 业务数据 | 核心表与最小演示数据 | `docs/database/` |
| RAG 数据 | 客服知识手册与评测集 | `knowledge/`、`data/rag_eval_dataset.json` |

## 2. 本地启动

```powershell
Copy-Item .env.example .env
# 修改 .env 中的密码、JWT 和按需使用的 API Key

docker compose -f docker-compose.dev.yml up -d redis nacos postgres zipkin
$env:POSTGRES_PASSWORD = '与 .env 一致的密码'
.\scripts\prepare-data.ps1

.\mvnw.cmd clean package -DskipTests
```

推荐启动顺序：Embedding Service → Gateway → User → Consumer → Router → Order → Product → General。服务端口详见根目录 `README.md`。

如果当前 PostgreSQL 没有映射主机端口，改用：

```powershell
.\scripts\prepare-data.ps1 -Mode Docker -ContainerName postgres
```

## 3. 数据验收

```powershell
.\scripts\prepare-data.ps1 -Mode Docker -ContainerName postgres -VerifyOnly
```

验收结果至少应包含：2 个演示用户、5 个商品、5 个订单、2 条物流、1 条退款、3 张优惠券和 4 条反馈。脚本还会检查 `vector` 扩展、11 张核心表及容易漂移的关键列。

## 4. 演示路径

| 场景 | 示例提问 | 预期能力 |
|---|---|---|
| 订单查询 | `查询订单 ORD-1003 的状态和物流` | Router → Order Agent，返回已发货和顺丰轨迹 |
| 退款咨询 | `订单 ORD-1005 为什么还在退款中？` | 订单与退款记录联合查询 |
| 商品咨询 | `MacBook Air M3 的价格、库存和颜色是什么？` | Product Agent / Text-to-SQL |
| 优惠券 | `我有哪些还没使用的优惠券？` | 用户维度优惠券查询 |
| 多意图 | `查 ORD-1003 的物流，再推荐一款耳机` | 任务拆分与多 Agent 协作 |
| 高风险操作 | `取消 ORD-1002` | 人工确认、审批状态机、幂等保护 |

演示前先登录 `test_user`，初始密码为 `password`。该账号只用于本地或隔离演示环境。

## 5. RAG 文档与评测

- 客服知识源放在 `knowledge/`，建议使用 Markdown front matter 标注版本、类别、有效期和关键词。
- `data/rag_eval_dataset.json` 是检索评测集；新增知识条目时同步补充对应问题和期望文档。
- 摄取前确认 Embedding Service 正常，并通过 `/api/knowledge/ingest` 相关接口提交文档。
- 评测脚本为 `scripts/eval_rag.py`。当前脚本中的检索适配层需指向实际搜索接口后再用于正式指标。

## 6. 交付前检查

- `.env` 未提交，仓库中不存在真实密钥或生产密码。
- `verify.sql` 通过，服务健康检查全部为 `UP`。
- 演示账号已在生产环境禁用或删除。
- RAG 文档已标注来源、版本和有效期，过期政策已下线。
- 关键接口、SSE 流、审批操作和至少一个多 Agent 场景已完成冒烟测试。
- 数据库已经备份，并记录恢复步骤和变更版本。

生产部署和运维命令见 [生产部署文档](../deploy/README.md)；架构与生产就绪检查分别见 [系统设计](system_design.md) 和 [生产就绪检查表](production-readiness-checklist.md)。
