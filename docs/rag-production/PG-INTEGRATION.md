# PG + pgvector 真集成验证说明（RAG 生产化改造收尾）

> 本文档对应交付物 `PgVectorKnowledgeBaseIntegrationTest`、一键验证脚本与 `ReviewQueueService` 自愈补齐，
> 用于补齐「RAG 生产化改造」（commit `a912269`）**唯一未连环境验证的环节**——
> Flyway 迁移 + PgVector 增量 upsert + 多实例读共享。

---

## 1. 背景与目标

上一轮 RAG 生产化改造交付了 `PgVectorKnowledgeBase`（动态维度 / 真实余弦距离 / 增量 upsert / `index_version` 过滤）
以及 `KnowledgeIndexMetaService` / `ComplianceAuditRecorder` / `ReviewQueueService` 三套自愈服务，
QA 两轮 35/35 全绿。但当时**唯一没连真 PostgreSQL 验证**的是：

1. V1 Flyway 迁移 SQL 在真 pgvector 上能否正确建 4 张表、`embedding` 列确为 `vector(1024)`；
2. 增量 upsert 是否真的只更新、不触发整库 reindex；
3. `index_version` 过滤在真 PG 上是否生效；
4. 多实例（REQ-4）是否真的通过共享 PG 实现读共享，而非内存快照。

本交付物用**真 PG 集成测试**在用户机器补齐上述证据，并保证**无 PG 环境下编译通过、集成测试优雅 skip、BUILD SUCCESS**。

---

## 2. 环境前提

| 项 | 要求 |
| --- | --- |
| Docker | 已安装并启动（Windows 用 Docker Desktop；用 Git Bash 或 PowerShell 执行脚本） |
| pgvector 镜像 | `pgvector/pgvector:0.8.0-pg16`（由 `docker-compose-infra.yml` 的 `postgres` 服务引用，**无需手动拉取**，首次 `up` 自动拉取） |
| 端口 | 宿主机 `5433` → 容器 `5432` |
| 库 / 用户 / 密码 | `a2a_system` / `postgres` / `postgres123`（`shared_preload_libraries=vector` 已配置） |
| 本机工具 | 系统 Maven `/d/maven/apache-maven-3.9.6/bin/mvn`、JDK 21（`JAVA_HOME="D:/Program Files/Java/jdk-21.0.6+7"`） |

> 与 `docker-compose-infra.yml` 的 `postgres` 服务严格一致。若你修改过该服务端口/凭据，请同步调整
> `PgVectorKnowledgeBaseIntegrationTest` 顶部的 `PG_URL / PG_USER / PG_PWD` 常量。

---

## 3. 启动 PostgreSQL + pgvector

```bash
# 在项目根目录执行（仅启动 postgres 单服务，不影响其它基础设施）
docker compose -f docker-compose-infra.yml up postgres -d

# 等待就绪（脚本会自动轮询 pg_isready，最多 60s）
docker exec smart-postgres pg_isready -U postgres -d a2a_system
```

或直接用本文档提供的一键脚本（自动完成「启动 → 探活 → 跑测试 → 可选停止」）。

---

## 4. 测试覆盖点（对应 a~f 与自愈）

测试类：`smart-assistant-common/src/test/java/com/example/smartassistant/common/rag/PgVectorKnowledgeBaseIntegrationTest.java`
（`@Tag("integration")`）。

| 编号 | 覆盖点 | 验证方式 |
| --- | --- | --- |
| (a) | **4 张表均存在** | 查询 `pg_tables` 确认 `knowledge_docs` / `knowledge_index_meta` / `knowledge_review_queue` / `compliance_audit_log` 都存在 |
| (b) | **embedding 为 `vector` 且维度=1024** | 查 `pg_attribute.atttypmod` 断言维度=1024（非写死 384），`pg_type.typname='vector'` |
| (c) | **增量 upsert，不触发整库 reindex** | 同 id 两次 upsert（改 content）→ `kb.size()==1`、检索反映新 content；用 `ReindexCountingKB` 子类计数 `reindex()` 调用次数，断言为 0 |
| (d) | **index_version 过滤** | upsert 两份同内容（向量相同）但 `index_version` 分别为 v1/v2；`KnowledgeIndexMetaService` 设 active=v1；检索仅返回 v1 文档 |
| (e) | **多实例读共享（REQ-4）** | 构造两个独立 `PgVectorKnowledgeBase`（共享同一 JdbcTemplate/DB）；实例 A upsert 后，实例 B 直接检索到，**无需重启/通知** |
| (f) | **真实相似度排序** | 用真 pgvector `<->` 距离断言「自身距离=0 < 不同文档距离」；两个不同查询向量分别将各自最相似文档排第一 |
| (+) | **ReviewQueueService 自愈建表** | 故意 DROP 表后构造 `ReviewQueueService`，断言其自愈建表并成功写入、读回（验证交付物 #2 的修复）|

> 测试不依赖真 BGE 模型：内置 `StubBgeEmbeddingModel` 固定返回 **1024 维确定性向量**（相同文本 → 相同向量），
> 因此 upsert 与查询向量一致、距离=0，无需 `embedding-service` 即可验证向量读写与距离语义。

---

## 5. 无 PG 环境下的行为（红线保证）

- 通过 `Assumptions.assumeTrue(pgAvailable)` 实现：仅当能连接本地 PG，或显式传入 `-Dpg.integration=true` 时执行；
- 否则**整类优雅 skip（不是失败）**，`mvn test` 仍然 `BUILD SUCCESS`；
- 即便误设 `-Dpg.integration=true` 但 PG 实际未启动，`setUp` 内的真实连通性探针（`SELECT 1`）会捕获失败并同样 skip，**不会报错**。

验证示例（沙箱无 PG）：

```
[INFO] Running com.example.smartassistant.common.rag.PgVectorKnowledgeBaseIntegrationTest
[WARNING] Tests run: 6, Failures: 0, Errors: 0, Skipped: 6
[INFO] BUILD SUCCESS
```

---

## 6. 在用户机器执行的精确步骤

### 方式一：一键脚本（推荐）

**PowerShell（Windows）：**
```powershell
# 默认不停止 postgres；如需验证后停止，加 -StopPostgres
.\scripts\verify-pg-rag-integration.ps1
# 或自定义 Maven / JDK 路径：
.\scripts\verify-pg-rag-integration.ps1 -MavenExe "D:\maven\apache-maven-3.9.6\bin\mvn" -JavaHome "D:\Program Files\Java\jdk-21.0.6+7"
```

**Git Bash：**
```bash
./scripts/verify-pg-rag-integration.sh
# 验证后停止 postgres：
STOP_POSTGRES=true ./scripts/verify-pg-rag-integration.sh
```

脚本逻辑：① 检查 Docker → ② `docker compose -f docker-compose-infra.yml up postgres -d`
→ ③ 轮询 `pg_isready`（超时 60s）→ ④ `mvn -pl smart-assistant-common test -Dtest=PgVectorKnowledgeBaseIntegrationTest -Dpg.integration=true`
→ ⑤ 输出结果（可选 `stop postgres`）。脚本幂等，可重复执行。

### 方式二：手动执行

```bash
# 1) 启动 PG
docker compose -f docker-compose-infra.yml up postgres -d

# 2) 等待就绪
docker exec smart-postgres pg_isready -U postgres -d a2a_system

# 3) 运行集成测试
export JAVA_HOME="D:/Program Files/Java/jdk-21.0.6+7"
/d/maven/apache-maven-3.9.6/bin/mvn -pl smart-assistant-common test \
  -Dtest=PgVectorKnowledgeBaseIntegrationTest -Dpg.integration=true

# 4) （可选）停止
docker compose -f docker-compose-infra.yml stop postgres
```

---

## 7. 预期输出示例（真 PG 通过）

```
[INFO] Running com.example.smartassistant.common.rag.PgVectorKnowledgeBaseIntegrationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in ...PgVectorKnowledgeBaseIntegrationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

6 个用例全部通过，覆盖上文 a~f 与 ReviewQueueService 自愈。

---

## 8. 已知约束（固化结论）

1. **向量维度以运行时为准**：`V1__rag_knowledge_schema.sql` 中 `embedding vector(1024)` 是**默认占位**；
   运行时以 `PgVectorKnowledgeBase.dimensions()`（来自 `BgeEmbeddingModel.dimensions()`，默认 1024）为准。
   表一旦由任一方建成，另一方 `IF NOT EXISTS` 不会改维度。当前 BGE 维度=1024，两者一致。
   **不要在 V1 SQL 里写动态模板**（Flyway SQL 不能含运行期变量），保持 `1024` 并依赖注释说明即可。
2. **Flyway 自动迁移未接入 Spring Boot**：之前移除了 `spring-boot-starter-flyway`、未注册 DataSource Bean。
   运行时靠各服务**自愈建表**——`PgVectorKnowledgeBase.initSchema()`（knowledge_docs）、
   `KnowledgeIndexMetaService`（knowledge_index_meta）、`ComplianceAuditRecorder`（compliance_audit_log）、
   以及本交付物补齐的 `ReviewQueueService`（knowledge_review_queue）。集成测试的 `setUp` 直接执行 V1 SQL 文本保证环境干净，
   再允许自愈幂等。
3. **双建表源一致性**：`PgVectorKnowledgeBase.initSchema()` 与 `V1__rag_knowledge_schema.sql` 对 `knowledge_docs` 的列定义
   名称/类型/默认值语义一致（后者额外建了 `idx_knowledge_acl_users` / `idx_knowledge_index_version` 两个索引，initSchema 不删除，兼容）。
   四张表的列定义与本交付物核对结论见第 9 节。
4. **不破坏内存模式**：无 PG 时 `mvn -pl smart-assistant-common test` 中集成测试被 skip，内存模式单测（`PgVectorKnowledgeBaseDefectTest` 等）不受影响。

---

## 9. 双建表一致性核对结论

| 表 | 来源 | 结论 |
| --- | --- | --- |
| knowledge_docs | `PgVectorKnowledgeBase.initSchema()` + V1 SQL | 一致。列语义对齐；V1 额外建 2 个索引，initSchema 不冲突 |
| knowledge_index_meta | `KnowledgeIndexMetaService.initTable()` + V1 SQL | 一致（id / active_index_version / bumped_at） |
| compliance_audit_log | `ComplianceAuditRecorder.ensureTable()` + V1 SQL | 一致 |
| **knowledge_review_queue** | V1 SQL 已有；**`ReviewQueueService` 原缺自愈** | **已修复**：补齐 `CREATE TABLE IF NOT EXISTS knowledge_review_queue`（字段对齐 V1：id / raw_payload JSONB / reason / source_type / submitted_by / status / reviewed_by / reviewed_at / created_at）+ 状态索引，构造时幂等建表 |

> 即：**发现并修复了一处不一致**——`ReviewQueueService` 原本只 INSERT/UPDATE/SELECT，从不建表；
> 在「Flyway 自动迁移未接入」的前提下，若 V1 脚本未先行执行，该服务会因表不存在而写入失败（仅降级内存）。
> 本交付物已让其与另三个服务看齐，具备自愈建表能力，向后兼容、不影响现有内存模式测试。

---

## 10. 交付文件清单

- 新增：`smart-assistant-common/src/test/java/com/example/smartassistant/common/rag/PgVectorKnowledgeBaseIntegrationTest.java`
- 新增：`scripts/verify-pg-rag-integration.ps1`（Windows / PowerShell 一键验证）
- 新增：`scripts/verify-pg-rag-integration.sh`（Git Bash 一键验证）
- 新增：`docs/rag-production/PG-INTEGRATION.md`（本文件）
- 修改：`smart-assistant-common/src/main/java/com/example/smartassistant/common/rag/ingestion/ReviewQueueService.java`（补齐自愈建表）
- 修改：`smart-assistant-common/pom.xml`（新增 `org.postgresql:postgresql` test 作用域依赖，版本固定 42.7.7 以便离线解析）
