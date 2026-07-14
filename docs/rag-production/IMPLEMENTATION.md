# RAG 生产化改造 — 实现记录 (IMPLEMENTATION.md)

> 模块：SmartAssistant（SmartAssistant 多智能体客服系统）
> 角色：Engineer（寇豆码）
> 范围：T01–T05 全量落地（REQ-1 路由摄入 / REQ-2 持久化 / REQ-3 生成后合规 / REQ-4 多实例读共享 / 集成验收）
> 技术栈：Java 21 · Spring Boot 3.5.16 · Spring AI 1.0.9 · pgvector · Flyway

---

## 0. 全局一致性评审（Global Consistency Review）

**结论：`IS_PASS: YES`**

| 检查项 | 结果 |
|---|---|
| 跨文件 import 一致性（无缺失、无环依赖） | ✅ common + product 全量编译通过 |
| 接口契约（KnowledgeBase.search/listAll 实现） | ✅ InMemory / PgVector(改造) / Resilient(补 search 重写) 均实现 |
| 数据流正确性（RagProductionProperties Bean 被 3 处消费） | ✅ ComplianceAutoConfiguration / RagProductionAutoConfiguration / IngestionJobAutoConfiguration 一致 |
| 合规链路贯通（RuleSet→Grader→Guard→Advisor） | ✅ 运行期由 advisor 测试验证 |
| 无重复实现 | ✅ |
| 检索 API 不变（red line） | ✅ ProductRagService / KnowledgeRetrievalService 未改签名 |
| KnowledgeDocument 仅增量字段 | ✅ sourceType / rawChecksum / ingestBatchId 均为新增可选字段 |
| GoldenSuite 基线零回归 | ✅ common 模块全量测试 BUILD SUCCESS |

**测试结论（内存模式，避免 PG 依赖）：**
- `smart-assistant-common`：全量测试 **BUILD SUCCESS（exit 0）**，含 GoldenSuiteEvalGateTest / Default / Trial（RAG 4/4 通过，Agent Trial×pass^k 门禁行为符合设计）。
- `smart-assistant-product`：全量测试 **BUILD SUCCESS（exit 0）**，RAG 检索路径运行期日志确认「RAG 知识已注入 / 无证据拒答 / 降级无上下文」均正常。
- `smart-assistant-router`：存在 **7 个预存失败**（`RouterServiceEndToEndTest`），属纯 Mockito 单测，手动构造 `RouterService` 且 **不涉及 `AiChatService` / Spring 上下文 / 任何 RAG 代码**，与本次改造无关，不在 T01–T05 范围内，未做任何改动。

---

## 1. 任务总览（T01–T05）

| 任务 | 优先级 | 需求 | 交付内容 |
|---|---|---|---|
| T01 | P0 | 基础设施/数据模型 | KnowledgeDocument 增量字段、RagProductionProperties、Flyway V1 迁移（vector+4 表）、PgVector 动态维度 |
| T02 | P0 | REQ-2 持久化 + REQ-4 读共享 | ResilientKnowledgeBase、MemoryRefreshCoordinator、KnowledgeBase.listAll() |
| T03 | P0 | REQ-1 路由摄入 | Markdown 解析 + Tika 嗅探、DocumentMetadataEnricher、DocumentValidator、ReviewQueueService、Webhook/定时触发、去除每次全量 reindex |
| T04 | P1 | REQ-3 生成后合规 | ComplianceGrader + ComplianceGuard + ≥20 规则、compliance_audit_log、PostGenerationComplianceAdvisor（warn/rewrite/block，默认 rewrite） |
| T05 | P0 | 集成/验收 | RagProductionAutoConfiguration、ProductKnowledgeConfig 重接线、默认 store=auto 降级、种子灌 PG、GoldenSuite 零回归 |

---

## 2. 架构师识别缺陷（已全部修复）

| 缺陷 | 根因 | 修复 |
|---|---|---|
| #1 PgVector 写死 384 维 | `PgVectorKnowledgeBase` 硬编码维度 | 改用 `BgeEmbeddingModel.dimensions()`（bge-large-zh-v1.5 → 1024）动态维度 |
| #2 `search()` 写死 cosSim=1.0 | 相似度计算未用真实距离 | 改为 `(1 - distance)`（余弦距离→相似度） |
| #3 每次摄入强制 `reindex()` | `KnowledgeIngestionService` 摄入即全量重建 | 去除强制 reindex，仅做增量 upsert（embedding 在 addDocument 内完成，多实例互不触发重算，满足 REQ-4） |

---

## 3. 文件清单

### 3.1 新增文件（common 模块）
- `rag/properties/RagProductionProperties.java` — `app.rag.*` / `app.compliance.*` 集中配置（默认 `mode=auto`、`defaultStrategy=rewrite`）
- `rag/store/ResilientKnowledgeBase.java` — PG 主库 + 内存降级装饰器（请求级故障转移 + `search` 重写）
- `rag/store/MemoryRefreshCoordinator.java` — 周期从 PG 拉取全量刷新内存快照（REQ-4 一致性）
- `rag/store/KnowledgeIndexMetaService.java` — 索引版本元数据（active 版本过滤）
- `rag/document/MarkdownDocumentParser.java` — Markdown 解析（front-matter 手写解析，不依赖 front-matter 扩展）
- `rag/document/TikaDocumentSniffer.java` — Apache Tika 内容嗅探
- `rag/ingestion/DocumentMetadataEnricher.java` — 元数据绑定（版本/时效/分类/ACL/sourceType）
- `rag/ingestion/DocumentValidator.java` + `ValidationResult.java` — 脏数据校验
- `rag/ingestion/ReviewQueueService.java` + `ReviewItem.java` — 复核队列
- `rag/ingestion/job/IngestionWebhookController.java` / `ReviewQueueController.java` / `ScheduledIngestionPoller.java` — Webhook / 定时触发
- `rag/compliance/ComplianceRuleSet.java` / `ComplianceRule.java` / `ComplianceGrader.java` / `ComplianceResult.java` / `ComplianceGuard.java` / `ComplianceAuditEvent.java` / `ComplianceAuditRecorder.java` / `ComplianceAutoConfiguration.java`
- `rag/advisor/PostGenerationComplianceAdvisor.java` — CallAdvisor/StreamAdvisor（Order=450，重写 ChatClientResponse）
- `src/main/resources/rag/compliance-rules.json` — 30 条规则（C001–C030：绝对化/超承诺/模糊政策/隐私/医疗/投资欺诈；block 仅限 C028–C030）
- `src/main/resources/db/migration/V1__rag_knowledge_schema.sql` — `CREATE EXTENSION vector` + 4 张表（含 `compliance_audit_log`，自举建表幂等）

### 3.2 新增文件（product 模块）
- `config/RagProductionAutoConfiguration.java` — `productKnowledgeBase`(KnowledgeBase) + `ragProductionJdbcTemplate` 装配，auto 降级

### 3.3 修改文件
- `rag/KnowledgeDocument.java` — 新增 `sourceType` / `rawChecksum` / `ingestBatchId`（构造器向后兼容）
- `rag/KnowledgeBase.java` — 新增 `search(String,int,String)` 抽象方法 + `listAll()`
- `rag/InMemoryKnowledgeBase.java` — 实现新增接口、补 `replaceAll`
- `rag/PgVectorKnowledgeBase.java` — 动态维度 + `(1-distance)` + 增量 upsert + `ON CONFLICT(id) DO UPDATE` + `initSchema()` 自举建表
- `rag/advisor/AiChatService.java` — 新增 5 参构造器（注入合规 Advisor，保留 4 参向后兼容）
- `rag/advisor/AdvisorChainAutoConfiguration.java` — `aiChatService` Bean 注入 `PostGenerationComplianceAdvisor`（required=false）
- `rag/ingestion/KnowledgeIngestionService.java` — 去除强制 reindex、接入 enricher/validator/reviewQueue、indexMeta
- `rag/ingestion/job/IngestionJobAutoConfiguration.java` — 注入 `RagProductionProperties`
- `rag/document/DocumentParseRouter.java` — 路由解析（Markdown 优先 + Tika 兜底）
- `config/ProductKnowledgeConfig.java` — 移除 `productKnowledgeBase` Bean（迁移至 RagProductionAutoConfiguration），下游改为按 `KnowledgeBase` 类型注入
- `pom.xml`（根 + common + product）— 依赖调整（见 §4 偏差）

### 3.4 删除文件
- `smart-assistant-common/src/test/.../ingestion/KnowledgeIngestionMultimodalTest.java` — 陈旧测试，调用已被移除的 `KnowledgeIngestionService.ingestImages(...)` 并校验已删除的 `reindex()`；多模态路径已由 `MultimodalIngestor`（经 `MultimodalIngestorTest` 充分覆盖）承担，删除后无覆盖损失，并解除其对 common 测试编译的阻断。

---

## 4. 关键设计决策与偏差（Deviations / Assumptions）

1. **构建工具：Maven Wrapper 不可用** → 使用系统 Maven `/d/maven/apache-maven-3.9.6/bin/mvn`（wrapper 报 `ClassNotFoundException: plexus.classworlds.launcher.Launcher`）。
2. **`spring-boot-starter-flyway:3.5.16` 无法解析**（不在 BOM、huaweicloud/jitpack 均无该坐标）→ 移除 starter，保留 `flyway-core` + `flyway-database-postgresql`（11.0.0，可解析）。生产建表走 Flyway；应用侧 `PgVectorKnowledgeBase.initSchema()` 在 `initSchemaOnStartup=true` 时自举（默认 false）。
3. **`commonmark-ext-front-matter:0.21.0` 404**（可达仓库无此坐标）→ 移除该依赖，front-matter 改为 `MarkdownDocumentParser` 手写正则解析（原有 `extractFrontMatter`/`stripFrontMatter` 逻辑已具备）。
4. **Product `application.yml` 的 `datasource:` 位于根级而非 `spring.datasource:`** → Spring Boot 不会据此自动创建 `DataSource`/`JdbcTemplate` Bean。决策：**刻意不注册 DataSource Bean**（避免 Flyway 自动迁移在无 PG 时打断上下文）；改为用匿名 `DriverManagerDataSource` 构建可选 `JdbcTemplate`（`ragProductionJdbcTemplate`，不可达则返回 null → 整体内存模式）。
5. **`RagProductionProperties` 此前非 Spring Bean**（会导致 `IngestionJobAutoConfiguration` 注入失败）→ 通过 `ComplianceAutoConfiguration` 与 `RagProductionAutoConfiguration` 的 `@EnableConfigurationProperties` 注册（Spring 去重，无冲突 Bean）。
6. **审计表自举** → `ComplianceAuditRecorder` 在首次写入前 `CREATE TABLE IF NOT EXISTS compliance_audit_log`，使审计在无 Flyway 自动迁移的环境（如内存/测试）亦可工作。
7. **默认模式** → `app.rag.store.mode=auto`（优先 PG，失败整体降级内存）；`app.compliance.default-strategy=rewrite`（命中即改写，block 仅限明确欺诈类规则）。

---

## 5. 验收（Verification）

| 阶段 | 命令 | 结果 |
|---|---|---|
| common 编译 | `mvn -pl smart-assistant-common compile` | ✅ EXIT=0 |
| product 编译 | reactor `common,product` | ✅ EXIT=0 |
| common 全量测试（内存） | `mvn -pl smart-assistant-common test` | ✅ BUILD SUCCESS |
| product 全量测试（内存） | reactor `common,product test` | ✅ BUILD SUCCESS |
| GoldenSuite | EvalGateTest / Default / Trial | ✅ RAG 4/4 通过，门禁行为符合设计 |
| Advisor 链路 | AdvisorChain/AdvisorRuntime/AiChatServiceEntity/PromptAudit/SafeGuard/TokenUsage/ThinkingCollector | ✅ 全部 0 error / 0 failure |
| router 测试 | `mvn -pl smart-assistant-router test` | ⚠️ 7 个预存失败（RouterServiceEndToEndTest，与本次改造无关，未改动） |

---

## 6. 红线遵守（Red Lines）

- ✅ 检索管线（parseAndIngest 流程）零行为回归：仅去除强制 reindex，改为增量 upsert。
- ✅ `ProductRagService.retrieve` / `retrieveWithQualityResult` / `KnowledgeRetrievalService` API 签名未变。
- ✅ `KnowledgeDocument` 仅新增可选字段（向后兼容）。
- ✅ GoldenSuite 基线无回归（common 全量测试通过）。
- ✅ Java 21 + Spring Boot 3.5.16 + Spring AI 1.0.9（实测版本，parent 亦声明 1.0.9，无 Nullness P0 回退风险）。
