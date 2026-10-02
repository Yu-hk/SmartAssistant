# Production Readiness Checklist

## 快照与判定边界

- 本次代码核对日期：2026-10-01。
- 代码基线：`943b0af4`（本次文档修订前的 HEAD；分支 `codex/assessment-contracts-20261001`）。后续代码或运行配置变化需重新核对，不自动继承本表结论。
- 历史输入：原清单自述基于 `2026-06-23 / 0cc6589`，随后又混入修复状态。本次替换其汇总判定；旧版本仍可通过 Git 历史追溯，不将旧缺口当成当前事实。
- 本次清单修订主要核对仓库源码、测试定义、配置与带日期验收文档；未执行全量测试，也不把文档编辑本身计为部署或故障验收。本轮额外的定向测试、只读 HTTP 核对及其运行边界见 [2026-10-01 整改记录](assessment-contracts-20261001.md)。
- 下文“既往验收”只指链接文档记录的那次产物、场景和环境，不证明今天仍部署同一版本；实际拓扑、开关、挂载与工件哈希必须在发布前重新盘点。

不再使用“12 项中 8 项通过 / 全部安全”的总分：原表项数与扩展项口径不一致，且把实现、配置、测试与运行验收混成了同一状态。

| 证据状态 | 含义 | 不代表 |
| --- | --- | --- |
| 代码已实现 | 基线内有实现及对应调用路径 | 测试已运行、线上已启用或所有异常路径正确 |
| 有范围验收 | 有带日期、范围和结果的既往验证记录 | 全量生产分布、集群故障或永久有效的认证 |
| 部分实现 | 有局部能力，但目标控制链尚不完整 | 该能力完全缺失，或局部实现已经完成目标 |
| 待验证 | 尚无覆盖目标场景的证据 | 已证明失败，也不等于可以默认放行 |

## 一、并发、资源与请求生命周期

| 检查项 | 当前证据 | 状态与保留边界 |
| --- | --- | --- |
| 同会话并发与写入竞态 | [ConversationGateService](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/session/ConversationGateService.java)用按用户/会话隔离的 Redis 门禁与租约控制同会话在途回合；[真实 Redis 回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/service/session/ConversationGateRedisIntegrationTest.java)覆盖 Lua/并发条件。[独立会话变更](independent-conversations-20260926.md)允许同账号不同会话独立运行。 | 代码已实现；不能因“每次新建 Agent”或“状态在 Redis”推导天然串行安全。跨节点租约失效、网络分区、重复派发仍需组合故障验收。 |
| 缓存与执行器容量 | [CacheConfig](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/config/CacheConfig.java)有 Redis TTL 策略；[ThreadPoolConfig](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/config/ThreadPoolConfig.java)混用虚拟线程执行器与有队列上限的平台线程池；[AgentToolExecutor](../smart-assistant-common/src/main/java/com/example/smartassistant/common/agent/AgentToolExecutor.java)用 Semaphore 限制单批工具并发。 | 部分实现；不是“所有缓存/Map/线程池均有界”。通用 taskExecutor 未设置并发上限，虚拟线程也不能替代数据库、连接池、队列与下游容量控制；需逐入口盘点增长和饱和行为。 |
| 阻塞 IO 与框架边界 | Consumer 等业务服务有 Servlet/SSE 路径，Gateway 是 WebFlux；[StreamChatController](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/controller/StreamChatController.java)与 [Gateway 配置](../smart-assistant-gateway/src/main/resources/application.yml)分别提供证据。 | 待验证；不能把整个系统称为“全是 MVC，因此不存在 IO 线程阻塞问题”。需检查实际执行调度、连接等待、取消和负载下的延迟。 |
| SSE 完成、取消与持久化 | [StreamTurnRecorder](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/session/StreamTurnRecorder.java)负责回合记录；[Controller 回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/controller/StreamChatControllerPersistenceTest.java)覆盖记录及带已认证用户的取消传播。[取消验收](cancellation-recovery-verification-20260917.md)记录真实 JWT/HTTPS/SSE 取消及后续请求。 | 代码已实现 / 有范围验收；BLPOP 超时或 @Async 本身不证明 cancel/error/complete 已覆盖。协作式取消不保证供应商或数据库立即停止，断流/超时也不能自动当作业务失败或已撤销。 |
| 会话关闭和删除的并发边界 | [AdminController](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/controller/AdminController.java)删除前取得门禁凭据，SQL 提交后完成清理；[鉴权与事务顺序回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/controller/AdminControllerSessionAuthorizationTest.java)覆盖运行中拒绝、服务不可用及失败撤销。[删除修复验收](session-gate-deletion-fix.md)保留线上复现与复测。 | 代码已实现 / 有范围验收；该验收含历史账号级互斥行为，当前独立会话语义以 2026-09-26 文档和当前代码为准。不能外推为所有历史占用都已清理。 |

## 二、模型调用、恢复与质量

| 检查项 | 当前证据 | 状态与保留边界 |
| --- | --- | --- |
| 错误分类与有限重试 | [ModelRoutingService](../smart-assistant-router/src/main/java/com/example/smartassistant/router/service/core/ModelRoutingService.java)有 @Retry 路径；[Router 配置](../smart-assistant-router/src/main/resources/application.yml)为 modelRoutingRetry 配置最大次数和指定异常，为 agentRetry 另配策略。[AgentErrorCode](../smart-assistant-common/src/main/java/com/example/smartassistant/common/error/AgentErrorCode.java)与 [ErrorRecoveryService](../smart-assistant-common/src/main/java/com/example/smartassistant/common/error/ErrorRecoveryService.java)提供统一错误/恢复抽象。 | 代码已实现；不能由一个异常白名单声称所有工具、HTTP 4xx/5xx、DNS/TLS 与包装异常都已正确分类。须检查各层重试叠加与写操作幂等，不给未知执行结果自动重放权限。 |
| Token、工具与并行成本 | [BudgetTracker](../smart-assistant-router/src/main/java/com/example/smartassistant/router/governance/budget/BudgetTracker.java)提供回合 Token/工具/轮次及用户级 Redis 配额；[AgentToolExecutor 回归](../smart-assistant-common/src/test/java/com/example/smartassistant/common/agent/AgentToolExecutorTest.java)保护工具执行边界。模型选择由当前 tier 配置决定，不再沿用旧表固定模型名或版本。 | 代码已实现；调用计数和 UI 用量不是完整供应商账单，也不证明各执行分支、缓存校验与重试费用均已归集。需预算耗尽和超时组合测试。 |
| 任务分析参与执行 | [RouterService](../smart-assistant-router/src/main/java/com/example/smartassistant/router/service/core/RouterService.java)调用任务分析，并用于澄清短路、查询改写、缓存策略和执行输入；[澄清策略回归](../smart-assistant-router/src/test/java/com/example/smartassistant/router/service/core/RouterClarificationPolicyTest.java)保护相应路径。 | 代码已实现；不再写“只保存 Redis、未参与路由”。实体/约束提取泛化和路由收益仍需独立题集评测，不能由 DTO 字段存在推导。 |
| 业务质检、反思与 LLM Judge | [RouteFinalizer](../smart-assistant-router/src/main/java/com/example/smartassistant/router/service/core/RouteFinalizer.java)按领域质量、缓存/澄清状态选择反思和 Judge，包含有条件的反思重试及质量拒绝/缓存准入；[回归](../smart-assistant-router/src/test/java/com/example/smartassistant/router/service/core/RouteFinalizerTest.java)与 [Judge 测试](../smart-assistant-router/src/test/java/com/example/smartassistant/router/service/quality/QualityEvaluationServiceTest.java)提供契约证据。 | 代码已实现；既不是“所有质量失败只跳过缓存”，也不是“每次低分都重试”。领域 PASS、规则分数、LLM Judge 与 Ragas 四维是不同指标，不能互相冒充或保证回答事实正确。 |
| Checkpoint 与断点恢复 | [LangGraphRedisCheckpointSaver](../smart-assistant-router/src/main/java/com/example/smartassistant/router/service/checkpoint/LangGraphRedisCheckpointSaver.java)及 [单元测试](../smart-assistant-router/src/test/java/com/example/smartassistant/router/service/checkpoint/LangGraphRedisCheckpointSaverTest.java)已实现存取/更新/释放；[真实 Redis 集成测试定义](../smart-assistant-router/src/test/java/com/example/smartassistant/router/service/core/LangGraphRedisRecoveryIntegrationTest.java)包含 Router 实例替换和在途节点恢复，需显式设置 ROUTER_RECOVERY_REDIS_PORT 才执行。 | 代码已实现；不是“完全无 Checkpoint”。测试存在或启用条件未满足而跳过，不等于故障演练通过；恢复执行开关、写操作去重、结果不确定处理与多副本竞争须按目标环境验收。[2026-09-17 记录](cancellation-recovery-verification-20260917.md)中的恢复关闭是历史运行状态，不能据此判断今天开关。 |

## 三、检索、记忆与限流

| 检查项 | 当前证据 | 状态与保留边界 |
| --- | --- | --- |
| 真实检索与质量基线 | [真实检索评测](real-rag-evaluation-20260920.md)记录公开种子在实际 Java/BM25/BGE 子链路上的隔离验证；[检索覆盖](retrieval-coverage.md)与 [校准记录](retrieval-calibration-verification-20261001.md)区分字段证据、阶段轨迹和策略实验。本轮真实 embedding HTTP 契约观测维度为 512，范围见 [整改记录](assessment-contracts-20261001.md)。 | 有范围验收；不再硬编码“全系统 BGE 384 维 / 所有知识均 pgvector / 无伪语义”。维度须取运行时响应并验证各链路一致性，health 自报模型字符串不认证权重身份；HTTP 契约、公开种子命中也不认证完整生产语料、PG 后端、答案事实或真实用户分布。 |
| 有界自动重检索与 Ragas | [有界重检索](automatic-retrieval.md)记录 2026-10-01 Product 上线：只读知识问题最多补检一次，保持原问题/选域/ACL，有限等待与工作位，失败保留首轮；[BoundedRetrievalFeedbackTest](../smart-assistant-product/src/test/java/com/example/smartassistant/service/search/BoundedRetrievalFeedbackTest.java)覆盖权限、容量、超时、原证据保留和错误回退。[Ragas 影子验收](ragas-online-shadow-verification-20261001.md)记录冻结合成问题的隔离评分。 | 代码已实现 / 有范围验收；在线词项覆盖是代理，不是 Ragas ContextRecall。不是每个真实请求在线跑四维 Judge，不是自动全局调权，也不允许订单/资金写操作重试。该上线记录没有生产 ACCEPTED 样本，接受候选仅在隔离控制题验证，不外推用户收益。 |
| 在线 Agent 记忆持久化 | [AgentMemoryService](../smart-assistant-common/src/main/java/com/example/smartassistant/common/memory/AgentMemoryService.java)的 Spring 构造注入 [GovernedAgentMemoryStore](../smart-assistant-common/src/main/java/com/example/smartassistant/common/memory/GovernedAgentMemoryStore.java)，在线读写 PostgreSQL profile_agent_memory，按请求/原话准入、生命周期代次及有效期过滤；[单元回归](../smart-assistant-common/src/test/java/com/example/smartassistant/common/memory/GovernedAgentMemoryTest.java)与 [PG 集成回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/service/recommendation/GovernedAgentMemoryIntegrationTest.java)保护拒绝和事务边界。[记忆验收](governed-agent-memory.md)有部署范围记录。 | 代码已实现 / 有范围验收；单参数文件构造只保留离线兼容。数据库不可用不回退旧文件；旧无请求写入不能视为在线写入入口。到期不可读不等于已物理删除。 |
| 旧文件/Redis 镜像边界 | [ConversationDocumentService](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/session/ConversationDocumentService.java)生产构造进入 governedRuntime，跳过旧摘要文件/user:memory:* 镜像写入；[兼容回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/service/session/ConversationDocumentServiceTest.java)保留旧构造测试。 | 代码已实现；旧文件/镜像是兼容与清理边界，不是当前在线 Agent 记忆主存储，也不能把“增加 Redis 镜像”当成全部持久化已外置。画像清理和恢复状态应看后续[受支持恢复基线](supported-recovery-baselines-20260921.md)，不要把 9 月 19 日的待办直接复制为当前状态。 |
| 入口与内部限流 | [GatewayConfig](../smart-assistant-gateway/src/main/java/com/example/smartassistant/gateway/config/GatewayConfig.java)提供 RedisRateLimiter；[Gateway 配置](../smart-assistant-gateway/src/main/resources/application.yml)启用 RequestRateLimiter 和 gateway.rate-limit.enabled=true。内部 [RouterClient](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/client/RouterClient.java)的 Resilience4j 限流、[ToolGateway](../smart-assistant-common/src/main/java/com/example/smartassistant/common/gateway/tool/ToolGateway.java)限流与部分 Semaphore 仍是进程内控制。 | 部分实现；不能说“限流全是本地”，也不能说“已全局共享所有额度”。多副本、可信身份键、入口绕行及 Redis 故障行为待验；不根据仓库配置猜测当前副本数。 |

## 四、配置、停机、安全与观测

| 检查项 | 当前证据 | 状态与保留边界 |
| --- | --- | --- |
| Prompt 外部化、刷新与灰度 | [TaskAnalysisService](../smart-assistant-router/src/main/java/com/example/smartassistant/router/service/taskanalysis/TaskAnalysisService.java)、[QualityEvaluationService](../smart-assistant-router/src/main/java/com/example/smartassistant/router/service/quality/QualityEvaluationService.java)有 @RefreshScope 与外部化属性，任务分析默认模板在资源中，源码受 Git 管理；[PromptGrayReleaseConfig](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/config/PromptGrayReleaseConfig.java)存在，但基线没有 shouldUseJsonFormat 的业务调用方。 | 部分实现；不是“所有 Prompt 都是不可热更常量”，也不是“灰度已上线”。实际 Nacos 刷新、生效版本、稳定分桶、审计和回滚链路需验收，配置类存在不证明调用已接通。 |
| 优雅停机与依赖关闭 | [Consumer 配置](../smart-assistant-consumer/src/main/resources/application.yml)、[Router 配置](../smart-assistant-router/src/main/resources/application.yml)、[Gateway 配置](../smart-assistant-gateway/src/main/resources/application.yml)等已配置 server.shutdown=graceful 与每阶段 30s；[ThreadPoolConfig](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/config/ThreadPoolConfig.java)通用任务终止等待及部分平台池等待为 60s，异步路由日志另为 30s。 | 代码/配置已实现，运行顺序待验证；未发现自定义 SmartLifecycle 不等于无优雅停机。30s 与 60s 属不同组件，不等于整个关停预算或所有任务完成保证。正确顺序是先停止接收新请求/派发，保持依赖可用并排空在途/后台任务，再关闭业务组件及依赖；不是让数据库连接池先于请求处理器关闭。 |
| 容器信号与停止宽限 | [Compose](../deploy/docker-compose.yml)未统一设置 stop_grace_period；[Dockerfile](../deploy/Dockerfile)用 sh -c java 而非显式 exec。[2026-09-28 发布记录](profile-admin-production-verification-20260928.md)记载 Consumer 两次在默认 10s 后收到 SIGKILL，切换前 MQ 已排空。 | 待验证；须隔离验证 PID 1/SIGTERM 转发和实际停止预算，不凭配置宣称信号一定未转发或已经完成 SIGTERM 演练。历史 SIGKILL 是风险证据，不证明当前线上仍使用同一启动方式，也不证明在途请求得到保护。 |
| JWT 与会话/请求归属 | [GlobalJwtAuthFilter](../smart-assistant-gateway/src/main/java/com/example/smartassistant/gateway/filter/GlobalJwtAuthFilter.java)及 [鉴权集成测试](../smart-assistant-gateway/src/test/java/com/example/smartassistant/gateway/filter/GlobalJwtAuthFilterIntegrationTest.java)保护入口；会话结束为 POST /api/sessions/{id}/close，删除为 DELETE /api/sessions/{id}，停止生成为 POST /api/chat/cancel。前两者在 [AdminController](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/controller/AdminController.java)，后者在 [StreamChatController](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/controller/StreamChatController.java)，使用已认证身份传入会话/请求所属链路。[Controller 鉴权回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/controller/AdminControllerSessionAuthorizationTest.java)、[SQL 用户隔离回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/service/admin/AdminServiceSessionIsolationTest.java)与 [取消传播回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/controller/StreamChatControllerPersistenceTest.java)提供证据。 | 代码已实现 / 有范围验收；原文“没有结束/中断端点，所以无 IDOR 风险”错误。端点确实存在，已有鉴权/归属保护，仍需逐入口检查伪造身份头、跨用户请求及内部直连边界；不能宣称全系统不存在 IDOR。 |
| 密钥、管理面与信息暴露 | [Gateway 配置](../smart-assistant-gateway/src/main/resources/application.yml)用环境属性注入 JWT/Redis/Nacos 敏感值；[管理端鉴权回归](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/controller/AdminControllerSessionAuthorizationTest.java)包含精确角色拒绝。 | 部分实现 / 待验证；属性占位符不证明线上密钥轮换、日志脱敏或全部 Actuator/Swagger/refresh 端点已受控。“生产 profile 未写 springdoc”也不证明文档端点关闭；须盘点实际暴露与访问策略。 |
| 指标、日志与追踪 | [AgentMetricsCollector](../smart-assistant-common/src/main/java/com/example/smartassistant/common/metrics/AgentMetricsCollector.java)提供指标，[Gateway 配置](../smart-assistant-gateway/src/main/resources/application.yml)含 Prometheus 和 Zipkin/Jaeger 兼容追踪配置。[会话用量验收](session-telemetry-verification-20261001.md)记录有限浏览器场景中的实时/缓存来源与回放一致性。 | 代码/配置已实现 / 有范围验收；未据此证明指标实际被抓取、跨服务 trace 连贯、告警可达和当班处置闭环。页面零用量需区分缓存来源，也不能把页面快照当完整成本账单。 |
| 发布、回滚与灾备 | [发布/回滚 runbook](deployment-rollback-runbook.md)规定精确工件盘点、停收、排空、健康检查与回滚；[整改验收](assessment-remediation-20260925.md)保留失败回滚和后续成功切换；[受支持恢复基线](supported-recovery-baselines-20260921.md)明确白名单、隔离恢复与跨存储保护范围。 | 有范围验收；单机发布或限定备份恢复不是零停机滚动发布、多副本故障、未知历史版本升级、整机/磁盘丢失灾备或分布式 exactly-once 认证。 |

## 五、发布前仍须补齐的证据

这些是验收目标，不是本次已经完成的操作。涉及生产、真实用户或写业务必须另行获得授权，并先确认目标提交、环境与回滚范围。

| 优先级 | 待验证目标 | 通过条件与证据边界 |
| --- | --- | --- |
| 高 | 目标环境的停收/排空/停机 | 显式记录新接入已停止、MQ ready/unacked、执行中请求与后台提交状态；验证超时退出时结果不确定处理、容器信号及停止宽限；依赖在排空期间保持可用。只读健康检查不能替代在途停机测试。 |
| 高 | 鉴权与会话/请求归属负例 | 目标入口和必要内部边界分别测试未认证、伪造身份头、跨用户关闭/删除/取消；结合真实 Redis/SQL 状态确认未影响他人，不能只测 Controller 返回码。 |
| 高 | Checkpoint/租约/消息的组合故障 | 在隔离环境注入进程崩溃、租约过期、重复投递和恢复竞争，确认已完成节点不重复执行、写操作幂等及不确定结果不盲目重试；跳过的外部依赖测试必须单列。 |
| 高 | 资源饱和与多副本额度 | 盘点缓存基数、虚拟线程入口、队列/连接池及本地限流器，验证拒绝、超时和恢复；Gateway 共享限流不替代内部全局预算或下游容量验证。 |
| 中 | Prompt 刷新与灰度闭环 | 若发布依赖热更或灰度，先接通实际调用路径，记录版本、分桶、生效和回退结果；不以配置类或注解验收运行能力。 |
| 中 | 观测运行闭环 | 验证实际抓取、跨服务 trace 关联、告警触发/送达、日志权限和处置过程；限定会话统计验收不可扩大为全链路 SLO 或账单认证。 |
| 中 | 独立检索/答案质量收益 | 冻结独立留出问题、负例与成本/时延边界，对照完整目标链路；分别记录检索覆盖代理、ID 指标、领域规则和四维分数。继续保留未知、失败和回退，不为提高指标补造资料。 |

未给 Runtime Sandbox、Harness 或强化学习管线统一“通过/未通过”评分：是否需要这些能力应由实际工具权限、部署威胁模型与业务目标确定；与当前实现相关的后续设计见 [评测系统缺口设计](eval-system-gaps-design.md)。

## 维护规则

2026-10-02 补充：[全模块轻量离线基线](offline-baseline-20261002.md)建立固定套件清单、干净报告及重复运行核对。该限定范围不能替代上表真实基础设施、生产停机与容量验证；父 POM 全局门槛未提高，已有精确类门槛不降低。

1. 每次更新标明代码提交、核对日期与验证方法；源码阅读、模拟测试、真实基础设施测试、线上 API 和浏览器验收分别记录。
2. 测试计数、覆盖率、模型名、维度、线上副本数和开关值只引用对应证据，不复制旧快照为当前事实；选定类的覆盖门禁不外推模块或项目整体覆盖率。
3. 既往验收文档可能包含后续补充；保留原失败、修复和复测时间边界，具体运行结论须绑定该次工件，不把后续成功改写为旧版从未失败。
4. 需要运行验收时先按 [发布/回滚 runbook](deployment-rollback-runbook.md)重新核对实际产物与部署，再附带范围清楚的新记录；本文档本身不授予生产操作权限。
