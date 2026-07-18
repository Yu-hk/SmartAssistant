# SmartAssistant 历史迭代日志（Changelog）

> 本文件由 `README.md` 尾部的阶段性改造记录迁移而来，便于主文档保持简洁。
> 最新启动方式、架构与服务职责仍以 [`README.md`](README.md) 为准。

---

## 近期改进（2026-07-08 ~ 2026-07-09）

### RAG 深度增强

| 改进 | 说明 |
|------|------|
| 🛡️ **P1 确定性护栏** | `GuardrailService` 关键词+意图匹配，退款/退货等高风险查询强制 RAG 增强（`skipShortCircuit=true`+`forceRag=true`），配置文件 `router.agent.rag.guardrails.*` |
| 📉 **P2 结构化拒答 + 归一化分数** | `RetrievalQualityResult` 共享模型（QualityLabel 枚举），Product/Order 双模块统一归一化分数（0.85~0.95），注入标准化拒绝消息 |

### Agent 幂等性（Q6）

| 组件 | 位置 | 说明 |
|------|------|------|
| `DistributedLock` | common/idempotent | Redis SETNX 分布式锁，Lua 脚本原子性解锁 |
| `TaskLogService` | common/idempotent | 任务日志状态机（PENDING→RUNNING→COMPLETED/FAILED），Redis 72h TTL |
| `executeIfNotDone()` | TaskLogService | 幂等执行统一入口：requestId 去重 + 分布式锁 + 结果缓存 |
| OrderTools 注入 | order | createOrder/cancelOrder/shipOrder/confirmDelivery 四方法注入幂等检查 |
| DB 唯一索引 | order-schema.sql | `request_id VARCHAR(64)` + 唯一索引 `idx_orders_request_id`，`OrderEntity.requestId` 字段，`DuplicateKeyException` 捕获 |

### 多 Agent 心跳（Q7）

| 组件 | 位置 | 说明 |
|------|------|------|
| `AgentHeartbeatService` | router/heartbeat | 任务级心跳上报：beat/markCompleted/markFailed |
| `HeartbeatMonitor` | router/heartbeat | 三层检查：checkServiceHealth(Nacos) / checkTaskTimeout(Redis) / checkCombined |
| `NacosHeartbeatService` | router/heartbeat | **新增**—封装 Nacos `NamingService.selectInstances(name, true)` 替代 Redis 服务级心跳 |
| GraphExecutionService 集成 | router/core | executeNode() 全部 5 条路径均上报心跳 |

**架构决策**：服务级健康→Nacos（内置 5s 心跳），任务级进度→Redis（Nacos 无此粒度）。

### Token 配额限制

| 组件 | 说明 |
|------|------|
| `BudgetConfig` | 新增 `maxTokensPerUserPerDay`、`maxCallsPerUserPerDay`、`quotaResetInterval` |
| `BudgetTracker` | Redis-backed 用户级配额追踪 `checkUserQuota(userId)` |
| `RouterService` | 路由前插入配额检查，超限直接返回提示 |

### Prompt 模块化（P2）

| 组件 | 说明 |
|------|------|
| `PromptManager` | 统一 Prompt 加载器，支持版本管理（`-v2` 后缀 A/B 灰度）和 15 个便捷方法 |
| 外部化文件 | 15 个 `.txt` 文件覆盖 router/order/consumer/common 四大模块 |
| 已迁移服务 | RouterService（inlineFallback）、TextToSqlTool、OrderIntentService、RouterRagService（2处）、HybridDataQueryService |

### 冷热数据分离（P2）

| 组件 | 说明 |
|------|------|
| `TieredKnowledgeBase` | 包裹 InMemory（热层）+ PgVector/Milvus（冷层），读取优先热层→降级冷层→自动升温，写入双写 |

### 两篇公众号文章分析

| 文章 | 来源 | 结论 |
|------|------|------|
| 《从文本数据到向量知识库》 | 码驿随想 | 项目 6 路召回 vs 文章 2 路，**远超** |
| 《豆包Agent一面13道真题》 | 公众号 | RAG 90% 覆盖 / Agent 65% / 分布式 40% |

### 异步入库任务管线（P0，源于《RAG 系统从 Demo 到生产》）

将同步的 `KnowledgeIngestionService.parseAndIngest` 封装为**带状态机的异步任务管线**，把解析/分块/向量化等重活移出请求链路，提供「提交即受理 + 进度轮询 + 失败重试」的生产级契约。

**状态机**（对标文章「异步可追踪数据链路」）：

```
UPLOADED ──▶ PARSING ──▶ CHUNKING ──▶ EMBEDDING ──▶ INDEXED
   │                                                        │
   └──────────────────（任意阶段异常）──────────────────▶ FAILED ──▶ RETRYING ──▶ (重跑)
```

**REST 端点**（`@ConditionalOnWebApplication`，统一 `ApiResponse`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/knowledge/ingest/submit` | 提交即受理，立即返回 `jobId`（202 语义），后台虚拟线程异步执行 |
| GET  | `/api/knowledge/ingest/jobs/{jobId}` | 进度轮询（status / progress / docCount / errorMessage） |
| POST | `/api/knowledge/ingest/jobs/{jobId}/retry` | 仅 `FAILED` 任务可重试（409 守卫） |
| GET  | `/api/knowledge/ingest/jobs?tenantId=` | 列出租户任务 |

**关键设计**：
- 提交去重：同一（源路径 + 租户）的活跃任务直接复用，避免重复摄入；
- 阶段回调：`parseAndIngest` 新增 `Consumer<IngestionJobStatus>` 重载参数，在各阶段回调以驱动状态机，**向后兼容**原有 3 参调用（默认无操作）；
- 执行器：默认 `Executors.newVirtualThreadPerTaskExecutor()`（JDK21 虚拟线程，与项目 common 约定一致），`DisposableBean` 优雅关闭；
- 存储接口 `IngestionJobRepository` 默认 `InMemoryIngestionJobRepository`（单实例），生产可替换为 JDBC/Redis 实现；
- 自动装配 `IngestionJobAutoConfiguration` **自包含**创建 `KnowledgeIngestionService`（依赖 `DocumentParseRouter`/`DocumentChunker`/`KnowledgeBase` Bean），仅在有 `KnowledgeBase` Bean 的 **Web 应用**上下文激活，业务模块零接线。

**新增文件**（`common/rag/ingestion/job/`）：`IngestionJobStatus`、`IngestionJob`、`IngestionJobView`、`IngestionJobRepository`、`InMemoryIngestionJobRepository`、`IngestionJobManager`、`IngestionSubmitRequest`、`JobSubmitResponse`、`IngestionJobController`、`IngestionJobAutoConfiguration`；改动：`KnowledgeIngestionService`（阶段回调重载）。

**验证**：`IngestionJob*Test` 共 7 用例全过（状态机流转 / 失败重试 / 去重 / 重试守卫 / CRUD / REST 提交·查询·未知重试 404），既有 `KnowledgeIngestion*` 测试无回归。

---

## 2026-07-10 改进点补充（3 项）

### GAP-A: RAG 缓存失效机制 (P1)

知识库入库/更新后自动失效 RAG 答案缓存，避免返回陈旧回答。

| 文件 | 改动 |
|------|------|
| `consumer/.../AnswerCacheService.java` | 新增 `invalidateAll()` — 清除 L1 Redis (`answer:*`) + 委托清除 L2 向量检索缓存 |
| `consumer/.../SemanticCacheService.java` | 新增 `clearVectorSearchCache()` — 委托 `VectorSearchCacheService.clearVectorCache()` |
| **🆕** `consumer/.../CacheInvalidationWiringConfig.java` | 消费端 `@Configuration`，将 `AnswerCacheService::invalidateAll` 注册为 `KnowledgeIngestionService` 的缓存失效钩子 |

### GAP-B: Grafana 扩展观测面板 (高ROI)

| 文件 | 改动 |
|------|------|
| `common/.../ContentHashCache.java` | 新增 `a2a_content_hash_skip_total` Counter（零装配 `Metrics.globalRegistry`，文档未变更时 +1） |
| `common/.../SseEventBus.java` | 新增 `a2a_sse_events_buffered_total` Counter（Redis 缓存事件时 +1） |
| **🆕** `monitoring/.../a2a-extra-observability.json` | 3 面板：ErrorType 分布 / HashUtil 跳过率 / SSE 缓冲区事件速率。uid=`a2a-extra` |

### GAP-C: 响应格式统一 (P3)

| 文件 | 改动 |
|------|------|
| `router/.../ErrorResponse.java` | `msg` → `message` 消除字段名混用；确认零外部引用、全量迁移至 `ApiResponse` |
| `common/.../ApiResponse.java` | 补充 Javadoc JSON 示例，确立 OpenAI 兼容的 Canonical 响应格式 |

## 2026-07-13 MCP 工具扩展（T2c→T2d→T2e）

### T2c Common MCP 适配层
| 文件 | 改动 |
|------|------|
| 🆕 `common/.../tool/mcp/McpRegistryDiscoveryClient.java` | MCP 发现客户端，连 registry MCP server `tools/list`/`search_tools`，解析 `_meta` 扩展字段回 `ToolDefinition` |
| 🆕 `common/.../tool/mcp/McpBackendToolExecutor.java` | 后端 MCP `tools/call` 转发，源连接池池化 + 懒加载 + 重连 + `@PreDestroy` 关闭 |
| 🆕 `common/.../tool/mcp/McpToolCallbackFactory.java` | center/MCP-backed 分流，统一 `ToolGatewayToolCallback` def 感知重载包裹。P0 治理接线点 |
| 🆕 `common/.../tool/mcp/McpRegistryClientFactory.java` | McpSyncClient 创建封装，便于注入与单测 |
| 🆕 `common/.../tool/mcp/DefaultMcpRegistryClientFactory.java` | 生产级 McpSyncClient 构建（HttpClientSseClientTransport + 手动 client 构造，不依赖 Spring auto-assembly） |
| 🆕 `common/.../tool/mcp/McpDiscoveryAutoConfiguration.java` | 条件装配 MCP 适配层组件 |
| 🏗️ `common/.../tool/client/ToolRegistryClient.java` | MCP 优先 + REST fallback + `mergeByEndpoint` 按 name 叠加 endpoint/inputSchema。三级降级保留 |
| 🏗️ `common/.../tool/provider/SpringToolProvider.java` | MCP-backed 识别 + `McpToolCallbackFactory` 生成回调 |
| 🆕 `docs/design-t2c-mcp-adapter.md` + `2 个 mermaid 时序图` | T2c 设计文档 |
| 🆕 5 个测试类（`McpRegistryDiscoveryClientTest`/`McpBackendToolExecutorTest`/`McpToolCallbackFactoryTest`/`ToolRegistryClientMcpFallbackTest`/`SpringToolProviderMcpBackedTest`） | 32 个测试用例：发现/执行/回调/降级/治理包裹验证 |

### T2d Agent 侧发现机制
| 文件 | 改动 |
|------|------|
| 🆕 `common/.../tool/meta/DiscoverToolsTool.java` | CORE 元工具，`@Tool(name="discover_tools")`，输入 `capabilityQuery`/`keywords`/`matchMode`/`limit`。护栏：去重/每轮1次/每会话10次/最多15动态工具。调用 `McpRegistryDiscoveryClient` + `McpToolCallbackFactory` + `registerDiscoveredTool()` 注入动态工具集。Registry 挂时降级返回"仅预载可用"，不阻断对话 |
| 🆕 `common/.../tool/meta/DiscoveryEvent.java` | 可观测事件(dagentId/conversationId/capabilityQuery/matchedNames/hitCount/source/latencyMs)。接入 ObservationRegistry span `agent-tool-discovery` + SLF4J 结构化日志 |
| 🏗️ `common/.../agent/SmartReActAgent.java` | 新增 `dynamicTools`(会话级) + `registerDiscoveredTool()`(去重同名覆盖) + `getDiscoveredCapabilityHistory()` + `mergeWithDynamicTools()`(`effectiveTools` = base + dynamicTools，每轮重读) |
| 🏗️ `common/.../tool/client/ToolRegistryProperties.java` | 新增 `t2-mcp-discovery-enabled`(默认false) + 护栏阈值 `maxDiscoveriesPerTurn`/`maxDiscoveriesPerSession`/`maxDynamicTools`(可配置) |
| 🏗️ `McpDiscoveryAutoConfiguration.java` | `@ConditionalOnProperty` 条件注入 DiscoverToolsTool bean，开关关闭时 bean 不存在 |
| 🏗️ `Order/Product/GeneralAgentConfig` | 注入 DiscoverToolsTool(`@Autowired(required=false)` + 双安全检查) |
| 🏗️ 3 个 system-prompt 文件 | 增加 `discover_tools` 使用说明与护栏提示 |
| 🆕 `DiscoverToolsToolTest.java` | 6 个测试（发现/去重/会话上限/工具上限/降级/初始状态） |

### T2e 治理收口
- `t2-mcp-discovery-enabled=false` 时行为与 T2c 前完全一致
- 开关关闭：DiscoverToolsTool bean 不创建；开关开启：
  - `mcp-discovery-enabled=false` → 原 REST 路径
  - 两者皆 `true` → MCP 优先发现 + Agent 自主发现完整链
- 护栏阈值全部配置化，带默认值
- `McpBackendToolExecutor` 失败/超时/熔断复用 `ToolGateway` 治理链
- QA 验证：28 测试 27 通过（1 个预存 Jackson 3/2 classpath 冲突），**NoOne** 路由判定，P0 治理链无绕行路径

---

本项目已完成基于 [customer_work 12 生产坑](https://mp.weixin.qq.com/s/Ihtqsp68m1h66Ua12yV7kw) 和 [ThinkingAgent 可靠性体系](https://mp.weixin.qq.com/s/UTEdhrkV3G3Ycfrg0Jng_A) 的交叉审计。

详细检查清单见 [`docs/production-readiness-checklist.md`](docs/production-readiness-checklist.md)。

审计结论：**12 项核心条目全部通过**（4 项 ⚠️ 已在本次修复中处理）。

---


## 工具统一治理与解耦（tool-registry 模块）

> 本节记录 2026-07-10 的架构调整：所有业务工具已从 order / product / general / consumer 模块迁移至独立的 `smart-assistant-tool-registry` 服务，实现 agent 与工具彻底解耦。

### 背景与目标
- 原有工具分散在四个业务模块的 `tools` / `tool` 包中，agent 在初始化时硬编码绑定具体工具类。
- 现统一收敛到 `smart-assistant-tool-registry`（独立 Spring Boot 服务），业务模块不再直接依赖工具实现，新增工具只需在 registry 中注册即可被所有 agent 使用。

### 解耦规则
- 业务模块 `main` 源码**不得** `import com.example.smartassistant.toolregistry.tool.*` 具体工具类。
- 数据访问走 SPI 接口：`OrderDataProvider` / `ProductDataProvider` / `GeneralDataProvider`（接口定义在 `common.tool.spi`，实现在各业务模块）。
- 共享能力走 `common`：`KnowledgeRetrievalService`、`ToolRegistryClient`（HTTP 客户端，带本地缓存）、`AiToolRegistry`（工具组装）、`ToolProvider`（ApplicationContext 扫描 + Registry 过滤）。
- 进程内共享状态抽为 Bean：`GifCacheStore`（替代 `DataGifTool` 静态缓存）、`RegistryTool`（标记接口，业务模块经 `ApplicationContext.getBeansOfType(RegistryTool)` 发现 registry 工具 Bean）。

### 新增工具流程
1. 在 `smart-assistant-tool-registry` 的 `tool/<domain>` 包下实现工具类（建议实现 `RegistryTool` 标记接口）。
2. 构造注入所需 SPI 接口与 common Bean，不依赖业务模块。
3. 工具随 registry 服务注册；业务模块经 `ToolRegistryClient` 按需获取，**无需修改 agent 代码**。

### 构建注意
- `tool-registry` 配置了 `spring-boot-maven-plugin` 的 `classifier=exec`：普通（库）jar 作为主 artifact 被安装，可执行 jar 带 `-exec` 后缀，便于其他模块编译期依赖。
- 构建统一使用项目脚本 `mvn21.sh`（系统 `mvn` 不可用）。


## 2026-07-15 P0 阻断项修复（评估路线图）

对 `docs/project-assessment-2026-07-15.md`（综合评分 66/100）列出的 4 个 P0 阻断项完成修复：

- **P0-① 密钥治理**：消除入库弱密钥。`deploy/.env.production` 改写为纯占位符模板（`CHANGE_ME_*`）；`deploy/docker-compose.yml`、`docker-compose.yml`/`docker-compose-infra.yml`、`monitoring/*` 的生产/入库路径密码与 `NACOS_AUTH_TOKEN` 全部改为 `${VAR:?...}` 强制注入；`Dockerfile.services` 硬编码 Key → 环境变量；`deploy/scripts/deploy.ps1` 的 rsync 增 `--exclude=.env*`（阻断真实 Key 上传），硬编码公网 IP → 必填 `$env:DEPLOY_SERVER_IP`；`.gitignore` 显式忽略 `deploy/.env`。**本地开发栈保留可覆盖弱默认以便一键启动。**
- **P0-② 前端接口契约**：根因是 `StreamChatController` SSE 端点"只等待、从不触发"路由决策，且 `triggerRoutingDecision` 调用了不存在的 `/api/router/decision`。改为调用真实 `/api/router/route` 并在 SSE 前先触发；`RouterClient` 读超时 5s→30s；网关新增 `consumer-service-bare` 裸路由 + SSE 免鉴权白名单；前端 `/api` 代理与本地会话创建改走网关。
- **P0-③ 部署编排**：`docker-compose.deploy.yml` 补齐 `postgres` 并为各后端服务补全容器网络环境变量；`docker-compose.ha.yml` 修正 Dockerfile 引用（`deploy/Dockerfile`）、`PORT`、`NACOS_DISCOVERY_SERVER_ADDR`。
- **P0-④ 路由 E2E + CI**：`RouterServiceEndToEndTest` 当前 9/9 通过（评估引用的 `regression2.log` 为陈旧数据）；将其接入 `eval-gate.yml` 的 `router-e2e-gate` 必需卡点。

> 待用户侧：轮换根 `.env` 真实 API Key（DEEPSEEK / DASHSCOPE / AMAP）。

