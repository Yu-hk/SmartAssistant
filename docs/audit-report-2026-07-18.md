# SmartAssistant 项目健康度审计（2026-07-18）

> 审计范围：D:\workspace\SmartAssistant 后端（11 个 Maven 模块）+ 文档（README.md、docs/、ai-project-context.md）
> 方法：只读调查，未修改任何文件。每个结论均附文件路径与行号证据。

## 总览判定

| # | 关注点（你的原话） | 是否属实 | 严重程度 | 一句话结论 |
|---|---|---|---|---|
| 1 | RouterService / SmartReActAgent 等核心类超千行 | **部分属实（被夸大）** | 中 | 两个指定类均未过千（774 / 910）；全项目仅 1 个类超千行（ExperienceService 1073），但 21 个主文件 >500 行、职责耦合，修改风险真实 |
| 2 | MCP / ONNX / Milvus 版本不一致 | **部分属实** | 中 | ONNX / Milvus / Spring AI 跨模块一致；**MCP SDK 在两模块硬编码 2.0.0、未纳入统一管理**；且存在文档/注释漂移 |
| 3 | recommend 与 tool-registry 都标 8088 | **属实** | 高 | 两个 application.yml 均写死 8088，本地单机必冲突，且二者均未被容器化 |
| 4 | ai-project-context.md 写 2.0.0 vs 父 POM 1.0.9 | **属实** | 中 | 文档 2.0.0（Boot 3.4.8）、代码 1.0.9（Boot 3.5.16），两处均不符 |
| 5 | embedding / product / user / recommend 测试薄弱 | **基本属实** | 中 | embedding 0 测试、user/recommend 各 1、product 4；密度普遍低于均值 0.22 |
| 6 | 注册中心 / 异常处理 / 线程池生命周期生产化风险 | **属实** | 高 | 注册中心内存态在 HA 下直接引发业务故障；SseEventBus 调度器泄漏；网关透传上游响应体 |
| 7 | README 堆叠改造记录、启动/架构难找 | **属实** | 中 | 2641 行中约 49% 为阶段性改造；真实启动在 1651 行、架构在 1600 行才完整 |

**结论：7 项关注点中，6 项实质成立（其中 1 项需注意前提），1 项（核心类超千行）前提需修正但风险判断方向正确。**

---

## 1. 核心类行数 —— 部分属实，但"超千行"前提需修正

| 类名 | 路径 | 行数 | >1000 |
|---|---|---:|---|
| ExperienceService | smart-assistant-router/.../service/experience/ | **1073** | ✅ |
| SemanticRouteCacheService | smart-assistant-router/.../cache/ | 992 | — |
| GraphExecutionService | smart-assistant-router/.../core/ | 958 | — |
| **SmartReActAgent** | smart-assistant-common/.../common/agent/ | 910 | — |
| KeywordExtractionService | smart-assistant-router/.../extraction/ | 841 | — |
| **RouterService** | smart-assistant-router/.../core/ | 774 | — |
| McpAgentService | smart-assistant-consumer/.../agent/ | 685 | — |
| AgentDiscoveryService | smart-assistant-router/.../agent/ | 638 | — |

- 你点名的两个类 **均未超过 1000 行**：RouterService（774）、SmartReActAgent（910）。全项目主源码中**仅 ExperienceService（1073）超过千行**。
- 但修改风险是真实的：主源文件 >500 行共 21 个，且多为"路由/编排/推理"核心类。耦合点示例：
  - **ExperienceService（1073 行 / 71 方法 / 2 内部类）**：经验提取 + Redis 存储 + BGE/Jaccard 向量匹配 + 淘汰策略 高度耦合。
  - **GraphExecutionService（958 行 / 36 方法）**：DAG 拓扑编排 + 并行调度 + 死锁保护 + 异常降级 耦合。
  - **SmartReActAgent（910 行 / 70 方法）**：ReAct 推理循环 + 工具执行 + 记忆 + 安全 + 状态机 耦合。
- **建议**：优先对 ExperienceService（唯一超千行）与 GraphExecutionService 做拆分（提取匹配算法、调度器为独立组件）；RouterService/SmartReActAgent 可在下次需求改动时顺势抽层，不必一次性重写。

---

## 2. 依赖版本一致性 —— MCP 未统一管理；文档/注释存在漂移

**父 POM 关键属性（pom.xml）：**
- `spring-ai.version` = **1.0.9**（:37，BOM 导入 :103）
- `onnxruntime.version` = 1.20.0（:64）
- `milvus-sdk-java.version` = 2.5.5（:66）
- `spring-boot-starter-parent` = 3.5.16（:10）

**声明汇总：**
| 依赖 | 版本 | 引用方式 | 跨模块一致 |
|---|---|---|---|
| spring-ai-* 系列 | 1.0.9 | BOM + 部分 `${spring-ai.version}` | ✅ |
| io.modelcontextprotocol.sdk:mcp / mcp-json-jackson3 | 2.0.0 | **硬编码（无父属性/BOM）** | 当前一致，但未单点管控 |
| com.microsoft.onnxruntime:onnxruntime | 1.20.0 | 父属性管理 | ✅ |
| io.milvus:milvus-sdk-java | 2.5.5 | 父属性管理 | ✅ |

**漂移证据：**
- `ai-project-context.md:9`：「Spring Boot 3.4.8 + Spring AI 2.0.0」→ 与代码（1.0.9 / 3.5.16）不符。
- `smart-assistant-tool-registry/pom.xml:51` 注释称「spring-ai-bom (2.0.0)」，实际 BOM 为 1.0.9。
- `ai-project-context.md:11` 写「Milvus 2.4」（服务端），而 SDK 管理为 2.5.5（客户端）——分属两端，文档应注明以免混淆。

**建议：** ① 将 MCP SDK 版本提升为父 POM 属性（如 `mcp-sdk.version=2.0.0`）统一管理；② 修正 ai-project-context.md 与 tool-registry pom 注释中的版本号。

---

## 3. 端口冲突（8088）—— 属实，严重程度高

**端口 8088 命中：** recommend 与 tool-registry 的 `application.yml` 均写死 8088。

**各服务实际端口（来自 `**/src/main/resources/application.yml`）：**
| 服务 | 端口 | 服务 | 端口 |
|---|---|---|---|
| gateway | 8081 | embedding | 8091 |
| consumer | 8082 | **recommend** | **8088** |
| router | 8083 | **tool-registry** | **8088** |
| product | 8084 | user | 8086 |
| order | 8085 | general | 8087 |

- 二者同端口，本地单机同时启动必冲突。
- `docker-compose.deploy.yml` 实际只编排 7 个服务（gateway/user/consumer/router/order/product/general），**recommend 与 tool-registry 均未被容器化**，只能本地运行 → 端口无法共存。
- 内部评估文档 `docs/project-assessment-2026-07-15.md:47,172` 已记录该冲突。
- README.md:1613 服务表把 Tool-Registry 标为「—」，与代码自相矛盾。

**建议：** 给 tool-registry 重新分配端口（如 8090），并补入 docker-compose；在 docs 中维护一份权威「端口分配表」。

---

## 4. 测试分布 —— 基本属实，embedding 最薄弱

| 模块 | 主代码 | 测试 | 密度 |
|---|---:|---:|---:|
| common | 325 | 105 | 0.32 |
| router | 100 | 34 | 0.34 |
| consumer | 63 | 10 | 0.16 |
| order | 46 | 6 | 0.13 |
| product | 32 | 4 | 0.13 |
| gateway | 8 | 5 | 0.63 |
| tool-registry | 15 | 5 | 0.33 |
| general | 16 | 2 | 0.13 |
| recommend | 10 | 1 | 0.10 |
| user | 16 | 1 | 0.06 |
| **embedding-service** | **2** | **0** | **0.00** |

- 你点名的四个模块确实薄弱：**embedding 零测试**（最突出）、user 1、recommend 1、product 4。四者密度（0 / 0.06 / 0.10 / 0.13）均低于全量均值 ≈ 0.22。
- 但"薄弱"部分源于**模块本身规模小**：common/router 因体量大承担了 85% 的测试（139/164）。
- 严重程度**中等**：业务核心 product/user/recommend 测试偏薄，embedding 缺失。

**建议：** 优先补 embedding-service 的契约测试 + user/recommend 的单元测试（Mock 工具注册与 LLM 调用），不必追求全局均衡。

---

## 5. 生产化风险 —— 属实，注册中心内存态为硬伤

**注册中心（最高优先级）：**
- `RegistryService` 全量存 `ConcurrentHashMap`（:40），无心跳 / TTL / 租约 / 定时清理，工具注册后永久存活，`RegistryController` 无反注册端点 → 陈旧条目累积。
- `getHealth()`（:435-446）仅在 status=DISABLED/REMOVED 标 DOWN、错误率>5% 标 DEGRADED；后端已死但 ACTIVE 的工具永远显示 OK，无依据剔除。
- 类注释自承「基于内存 ConcurrentHashMap，后续可迁移到数据库」（:24-33），但 `docker-compose.ha.yml` 为多云实例部署 → **各实例注册表独立，A 实例注册的工具对 B 实例不可见，无分布式一致**。这是 HA 下唯一会直接引发业务故障的硬伤。

**异常处理：**
- 网关 `GlobalExceptionHandler.java:72` 在 `WebClientResponseException` 中将 `e.getResponseBodyAsString()` 直接作为 `ErrorDetail.detail` 返回 → 可能泄露上游内部错误。
- `MilvusKnowledgeBase.java:431/627/640` 等 `catch (Exception ignored) {}` 在清理路径静默吞异常，隐患无感知。
- 整体较好：common/gateway/router 三处用统一错误码、区分业务/系统异常、不暴露堆栈。

**线程池 / 生命周期：**
- `SseEventBus.java` 每连接 `new ScheduledThreadPoolExecutor`（:106-133），`close()` 仅 `heartbeatFuture.cancel(false)`（:143），**未 `shutdownNow()` → 执行器/线程泄漏**，高并发 SSE 下累积大量 executor。
- `taskExecutor`/`asyncRouteExecutor` 用 `SimpleAsyncTaskExecutor(setVirtualThreads)`（ThreadPoolConfig.java:95-108,166-179），**无并发上限、无背压**。
- 治理较好：`HotAgentPool`/`AgentTaskWorkerPool` 实现 `DisposableBean`+`shutdown`；`RouterThreadPoolConfig` 设优雅关闭与拒绝策略。

**Top 风险排序：** ① 注册中心内存态（HA 工具视图分裂 + 陈旧条目无人清理）；② SseEventBus 调度器泄漏；③ 网关透传上游响应体 + Milvus 清理吞异常。

---

## 6. README 审计 —— 属实，启动/架构被淹没

- 文件：`D:\workspace\SmartAssistant\README.md`，**2641 行**。
- 第 299–1593 行（近 1300 行，约 49%）为「阶段性改造/改进点/评估路线图」堆叠：知识库管道、Router 流水线、混元部署、Spring AI 2.0 工程化、生产级 Agent、G3/G4 路由、Loop Engineering、P0~P5 各阶段、2026-07-10/13/15 改进等。
- **真实启动指引在 :1651 起**（快速开始），需滚动至全文约 63%；Docker 部署在 :1807；DB 初始化 :1760 —— 关键启动信息分散，无顶部导航直达。
- 顶部「系统架构」图（:214）缺 tool-registry，recommend 仅作 P3 旁支；完整端口表延迟到 :1600，且与代码端口（recommend、tool-registry 同 8088）不一致。

**建议：** 将「快速开始」上移到目录之后；补独立「端口分配表」；把历史改造段落收敛到 `docs/`，README 只保留当前架构 + 启动。

---

## 优先修复路线（按风险/收益）

1. **P0（必改）**：端口冲突（recommend/tool-registry 8088）→ 重新分配并容器化。
2. **P0（必改）**：注册中心内存态 → HA 下工具视图分裂；短期至少加 TTL/心跳清理，长期迁共享存储（Redis）。
3. **P1**：SseEventBus 调度器泄漏 → `close()` 中 `shutdownNow()`。
4. **P1**：文档/代码漂移 → 修正 ai-project-context.md 版本号、MCP 版本统一管理、tool-registry pom 注释。
5. **P2**：README 重构 + 端口分配表；补 embedding/user/recommend 测试；核心大类（ExperienceService、GraphExecutionService）拆分。
