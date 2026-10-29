# SmartAssistant 项目整体评估（2026-09-29 深度版）

> **评估对象**：`main` @ `869957ed`（Merge PR #111），覆盖 PR #95–#111（43 提交 / 238 文件 / +142,005 / −1,266）
> **评估方法**：仓库实测 + **真实执行验证**。已执行 ①全量 11 模块 `mvn -am -DskipTests install` → BUILD SUCCESS（4m15s）；②全量单元测试 `mvn test`（排除需基础设施的 `*IntegrationTest`）→ **BUILD SUCCESS，2,344 测试 / 0 失败 / 0 错误**（17m0s）。所有度量取自仓库与构建产物，非估算。
> **与上一版（9-24）的差异**：本版新增「真实执行证据」「运维与部署」「安全与可观测性」「架构治理」四个此前未覆盖的维度，并订正模块数等自身偏差。

---

## 〇、执行摘要

| 项目 | 结论 |
|---|---|
| 定位 | 多智能体智能客服工作台（8 运行服务 + 12 Maven 模块），链路：问题 → 意图路由 → Agent 执行 → RAG 增强 → 画像沉淀 |
| 规模 | 主代码 **107,745 行 / 798 文件**；测试 **45,881 行 / 428 类**；前端 13,263 行；文档 **169 份** |
| 质量实证 | **2,344 单元测试真实跑通，0 失败 / 0 错误**；全量编译 BUILD SUCCESS |
| 技术栈 | Spring Boot **4.1.0** + Spring AI **2.0.1** + Spring Cloud 2025.1.2 + JDK 21 + RabbitMQ 4.1(Quorum) + PG16/pgvector + Redis 7 + langgraph4j + React 18/TDesign |
| 活跃度 | 首提交 2026-04-30 → 732 提交 / **82 个合并 PR**；近 30 天 183 提交、近 7 天 59 提交 |
| 综合评级 | **4.3 / 5**（较 9-24 的 4.0 提升，主因：覆盖率门禁闭环 + 真实执行验证通过） |
| 最大风险 | **Bus factor = 1**（732 提交实为 1 人 5 身份），结构性不可通过工程消除 |
| 最大亮点 | 「评估 → 核对 → 整改 → 复评」闭环真实运转；生产发布带 SHA-256 产物校验与自动回滚 |

---

## 一、规模与结构度量（实测）

### 1.1 模块分布（**12 个模块**，订正：前版漏计 `smart-assistant-data-intake`）

| 模块 | main 行数 | test 行数 | 实测执行测试数 | 定位 |
|---|---:|---:|---:|---|
| common | 37,353 | 15,464 | **852** | Agent 引擎 / RAG / 记忆 / 分词（承载最重，占 35%） |
| router | 25,860 | 10,221 | **466** | 意图路由 + Graph 编排 + 经验体系 |
| consumer | 19,851 | 8,766 | **360** | SSE 入口 + 会话 + 画像 + 上下文压缩 |
| product | 8,115 | 4,256 | **281** | 商品咨询（原 travel/food） |
| order | 8,065 | 3,239 | **151** | 订单客服 |
| tool-registry | 2,107 | 1,274 | **66** | 工具注册中心 |
| gateway | 785 | 1,002 | **59** | WebFlux 网关 + JWT |
| data-intake | — | — | **57** | 数据接入（本版新识别） |
| user | 2,696 | 956 | **43** | 用户 / OAuth / JWT |
| tool-runtime | 1,736 | 86 | **6** | 工具运行时 |
| routing-contract | 157 | 41 | **3** | 路由契约 |
| embedding-service | 184 | 0 | 0 | 独立嵌入服务（无测试） |
| **合计** | **107,745** | **45,881** | **2,344** | **0 失败 / 0 错误** |

> 三强（common + router + consumer）占主代码 **77%**，common 单模块 37k 行承载偏重。

### 1.2 API 与组件面
- REST Controller **42 个**；`@Tool` 注解 **151 处**（20+ 工具集）；`@Service/@Component` **259 个**
- 前端：67 个 ts/tsx 文件 / 18 组件 / 2 页面 / 13,263 行

---

## 二、五维评估

### ① 代码结构　　评分 3.9 / 5
**正面**
- 基础设施与策略类设计精炼：`AgentExecutionBudget`（51 行）、本轮拆出的 `AdminFaqService` / `StreamTurnRecorder` / `RedisSseProgressForwarder` / `ProductEvidenceResponsePolicy` 均单一职责、可独立测试。
- 包组织清晰：common 下 `agent / rag / memory / tokenizer / gateway / security / skill / sql` 子域边界明确；router 下 `experience / core / agent / taskanalysis` 分层合理。

**负面**
- **13 个 >640 行主类**：AdminService 1,143 / ExperienceService 1,085 / SmartReActAgent 1,033 / LangGraphRouteExecutionService 920 / RouteExecutionService 877 / StreamingProductAgentService 849 / **StreamChatController 770（胖 Controller）** / MilvusKnowledgeBase 753 / GraphNodeExecutionService 717 …
- `StreamChatController` 仍残留 SSE 编排与协议响应协调逻辑（本轮已下沉回合记录与 Redis 转发，剩余待拆）。
- common 37k 行 / 303 类（RAG 子集 128 类）承载过重，是拆分子模块的首要候选。

### ② 功能实现完整性　　评分 4.4 / 5
本轮能力均**前后端 + 测试 + 文档齐备**，无半成品或 mock 占位：
- 商品适配性元数据（PR #111）：声明 → 存储 → 检索全链路 + 迁移基线
- 商品搜索召回（PR #109/#110）：保留原始 query、实时目录索引供 BM25、`ProductEvidenceResponsePolicy` 收敛证据与空目录兜底
- RAG 生产验证（`docs/rag-production/` 10 份）：评测流程生产化、可复现
- 会话独立（PR #97）：独立会话 / 防已删会话复现 / 深链校验 / 恢复基线入 CI
- 画像 Admin 生命周期（PR #102）：生命周期管理 + 审计清理 + schema 恢复基线审批
- 域边界咨询：`PurchasePrerequisiteGuard` + 有界咨询决策
- 数据接入（data-intake）：独立模块 57 个测试

### ③ 潜在缺陷与技术债务　　评分 4.1 / 5
- **代码内债务标记极低**：`TODO=0`、`FIXME=0`、`HACK=2`（注解级）。
- **`@Deprecated` 7 处**：项目方已逐项盘点——本轮仅删除无调用的 `TaskPlannerService.plan()`；其余为活跃父类（`ContextCompressor`）、活跃 RAG 入口、兼容构造器。`ContextCompressor` 等 6 处**保留合理，不应按注解数量盲删**（前版建议已被项目方以更准确的论据部分驳回）。
- **框架升级 ADR 已存在**（前版误报已订正）：`docs/adr/0001-framework-baseline.md`（2026-09-17，含"仍未证明"边界矩阵 + 回滚约束）+ `docs/deployment-rollback-runbook.md`。
- 真实发布曾遇 Gateway 重启后一次 502 → 自动回滚 + 有界重试修复，缺陷暴露与修复闭环健康。

### ④ 测试覆盖率与质量　　评分 4.4 / 5（本版提升最大）
- **真实执行**：2,344 单元测试 / **0 失败 / 0 错误**（17m0s），覆盖 11 个有测试模块；另有 **25 个集成测试**（真实 PG16 + RabbitMQ 4.1 + Redis 7 service-container）由 CI 执行。测试比 42.6%。
- **覆盖率门禁已闭环**（前版 P0）：
  - 父 POM `jacoco-maven-plugin` 0.8.12，`check` 目标设 BUNDLE 指令≥30% / 分支≥20%（`haltOnFailure` 默认 false，避免阻断增量构建）
  - **CI 类级硬门禁**（不受默认开关影响）：`ProductEvidenceResponsePolicy` 85%/70%、`AdminFaqService` 80%/60%、`StreamTurnRecorder` 80%/65%、`RedisSseProgressForwarder` 80%/60% 等 5 处
  - `verify_integration_reports.py --minimum N` 防静默缩水，覆盖 15+ 测试类
- **CI 19 个 job**：`compile-all` / `module-boundary-gate` / `database-bootstrap-restore` / `retrieval-probe-contract` / `react-behavior-baseline` / `router-e2e-gate` / `product-evidence-gate` / `profile-governance` / `profile-privacy-auth` / `session-gate` / `voice-input-gate` / `project-context` / `quality-gate` 等
- **仍待补强**：全模块统一覆盖率阈值未开硬门禁（需先建稳定全量基线）；`embedding-service` 零测试。

### ⑤ 可维护性　　评分 4.2 / 5
- **文档密度极高（169 份）且结构化**：`docs/architecture/` 29（含可交互 HTML + JSON 工作流定义 + SVG + 视觉校验产物）、`docs/database/` 36、`docs/rag-production/` 10、`docs/adr/` 1、`docs/eval/` 2。交接成本显著低于同类单人项目。
- **运维脚本成熟（25+）**：`database_bootstrap_drill.py`（数据库恢复演练）、`eval_rag.py`、`profile_control_recovery_drill.py`、`profile_backup_ownership_audit.py`、`release_artifacts.py`（只读产物清点 + SHA-256）、`check_project_context.py`（事实防漂移）。
- **可观测性完整**：Prometheus + Grafana（5 个仪表盘）+ Alertmanager + Loki/Promtail + Jaeger + Micrometer 指标。
- **最高风险 Bus factor = 1**：732 提交 / **1 人 5 身份**（yu 477 + 于海阔 129 + Yu-hk 122 + 2 个 agent 身份 4）。项目方在整改记录中诚实承认"文档能降低交接成本，不能创造第二位维护者"。

---

## 三、前版未覆盖的新增维度

### ⑥ 运维与部署　　评分 4.3 / 5
- 编排：`docker-compose.yml`（主）/ `docker-compose.integration.yml` / `docker-compose.dev.yml` / `docker-compose.ha.yml`；`deploy/` 下 nginx 双份配置、systemd 单元、证书自动续期（`renew-cert.sh` + timer）、`init-server.sh`、切换脚本（`cutover_data_intake_*.sh`）
- 发布治理：`release_artifacts.py` 只读清点运行中 JAR + 镜像 + SHA-256，回滚前用发布前快照核验；数据库"只允许先扩展再收缩"
- 真实发布记录（2026-09-25）：三服务切换，首次遇 502 自动回滚，第二次成功，独立快照证实运行哈希一致
- **缺口**：多副本故障演练、Chaos 测试未做（项目方明确未宣称完成）

### ⑦ 安全　　评分 4.2 / 5
- 33 个安全相关类：网关 `GlobalJwtAuthFilter` + `JwtUtil`、用户服务 `SecurityConfig` + `JwtAuthenticationFilter`、外部 `OAuthLoginService`、`PiiScrubber`（7 类正则脱敏）、`AgentSafetyService`（提示注入检测）、`AuthorityLevel`（RAG ACL）、`ProfileAdmin/PrivacyAuthentication`
- 生产 profile 未启用 springdoc（Swagger 已关闭）；密钥走环境变量；异常信息通用化；`SqlSecurityValidatorTest` 18 项
- CI `security-scan.yml`：OWASP Dependency-Check，CVSS≥9 阻断，抑制项仅 2（克制）
- **缺口**：PII 为正则而非 NER；未做渗透测试/依赖审计之外的应用层安全测试

### ⑧ 架构治理　　评分 4.2 / 5
- **ADR 0001** 质量高：明确"保持当前基线"、升级须独立 PR 附依赖树+适配器回归+产物哈希+回滚点、**只对实际启用的适配器给兼容结论**（Milvus 等保持"未认证"，禁止由 SDK 存在推断线上启用），并给出"验证矩阵 / 仍未证明"对照表
- `ai-project-context.md` 由脚本 `check_project_context.py` + `test_project_context.py` 强制校验防漂移（前版 P0 已根治）
- `module-boundary-gate` CI job 强制模块扫描边界

---

## 四、项目方自审交叉验证

`docs/production-readiness-checklist.md` 对标行业文章"12 个生产级坑"做逐条自检：**8 通过 / 4 需关注 / 0 失败**。与我独立评估高度一致，其开放项构成我风险清单的重要输入：

| 自审项 | 状态 | 备注 |
|---|---|---|
| 并发与状态（会话串行化 / 缓存有界 / 线程模型） | ✅ 3/3 | Redis 会话 + Caffeine 有界缓存 + 4 个有界线程池 |
| 模型成本（流式正确性 / 重试分类 / 成本护栏） | ✅ 2/2 | Flash/Pro 分层 + Token 预算 + 有界并行 |
| 召回质量（真实语义 / 持久化外置） | ⚠️ 1/2 | **进程内限流 + 文件记忆是水平扩展瓶颈** |
| 基础设施（prompt 管理 / 优雅停机 / 可观测性） | ⚠️ 1/3 | **无 prompt 热更新灰度、无 SmartLifecycle 编排停机** |
| 安全运维（鉴权 / 持久化可靠性） | ✅ 2/2 | JWT 全覆盖，审计确认无 IDOR 端点 |

> 注：该自审文档脚注标注"基于 2026-06-23 版本"，但内容含后续修复项（如 LangGraph Redis Checkpoint 已实现），存在**时间戳与内容不同步**的轻微文档漂移（P2）。

---

## 五、风险清单（本版刷新）

| 等级 | 风险 | 状态 | 建议动作 |
|---|---|---|---|
| **P0** | **Bus factor = 1**（1 人 5 身份，732 提交） | ⏳ 结构性 | 引入第二位有发布权限的复核人；开源核心模块；把隐性知识继续沉淀为 ADR/runbook |
| **P0** | 全模块统一覆盖率硬门禁未启用（`haltOnFailure=false`） | ⬇️ 类级已硬门禁 | 先建稳定全量基线，再在 `eval-gate` 加 `-Djacoco.haltOnFailure=true` 设 40–50% 下限 |
| **P1** | **水平扩展瓶颈**：进程内限流 + 本地文件记忆 | ⏳ | 限流迁 Redis 滑动窗口；记忆存储外置（已有 Redis 镜像开关） |
| **P1** | **优雅停机缺 SmartLifecycle 编排** | ⏳ | 增加停机顺序编排（DB 池 → Web 容器），配置 `timeout-per-shutdown-phase` |
| **P1** | God-class 集中度（13 个 >640 行，含 770 行胖 Controller） | ⬇️ 已拆 4 个 | 继续按可回归边界拆 `StreamChatController` 残留协调逻辑、`SmartReActAgent`（行为基线已保护）、`ExperienceService` |
| **P1** | common 37k 行承载偏重 | ⏳ | `common/rag`（128 类）独立为 `rag-core` 子模块 |
| **P2** | prompt 无热更新与灰度 | ⏳ | 已加 `@RefreshScope` + 可外部化属性，可接 Nacos 动态配置 |
| **P2** | 浏览器视觉验收 / 多副本故障演练缺失 | ⏳ | 补 Playwright E2E 与 Chaos 演练，**不宣称 API 测试可替代** |
| **P2** | `embedding-service` 零测试 | ⏳ | 补最小契约测试 |
| **P2** | 自审清单时间戳不一致 | ⏳ | 更新脚注或拆分历史快照 |

---

## 六、改进路线图

**1 周内（低风险）**
1. `eval-gate.yml` 的 verify job 加 `-Djacoco.haltOnFailure=true`，BUNDLE 阈值设 35%/25%
2. `embedding-service` 补最小契约测试，纳入 `module-boundary-gate`
3. 更新自审清单时间戳

**2–4 周（中风险）**
4. 拆 `StreamChatController` 残留协调逻辑为 `ChatStreamOrchestrator`；拆 `SmartReActAgent` 主循环为 `AgentToolCaller` + `AgentLoopDecision`（`SmartReActAgentBehaviorBaselineTest` 已保护）
5. 限流迁 Redis 滑动窗口，开启记忆 Redis 镜像
6. 增加 `SmartLifecycle` 优雅停机编排

**1–2 月（需设计）**
7. `common/rag` 子模块化
8. prompt 接 Nacos 动态配置 + 版本 + 灰度
9. 引入 Playwright 浏览器验收与多副本故障演练

**结构性（需决策）**
10. Bus factor：引入第二维护者 / 开源协作

---

## 七、评分演进

| 维度 | 9-16 | 9-24 | **9-29** | 变化 |
|---|---:|---:|---:|---|
| 代码结构 | 3.8 | 3.8 | 3.9 | God-class 拆分启动 |
| 功能完整性 | 4.2 | 4.3 | 4.4 | 本轮能力端到端齐备 |
| 缺陷 / 债务 | 4.0 | 4.0 | 4.1 | 误报订正，债务标记极低 |
| 测试覆盖 | 3.7 | 3.9 | **4.4** | 门禁闭环 + 实测 2,344 全通过 |
| 可维护性 | 3.9 | 4.0 | 4.2 | 文档/运维/发布治理强化 |
| **加权总分** | **3.9** | **4.0** | **4.3** | **+0.3** |

---

## 八、评估闭环（连续三轮实证）

| 评估 | 触发的整改 |
|---|---|
| 9-16 P0 文档漂移 | PR #54 重写 `ai-project-context.md` + CI 事实校验脚本（根治） |
| 9-16 P1 三套画像无治理 | PR #55 → #63–#78 演化为画像生命周期子系统 |
| 9-24 P0 缺覆盖率门禁 | 父 POM jacoco + 5 处类级硬门禁 + 19 job CI |
| 9-24 P1 God-class | AdminService 1,329→1,143，新增 4 个拆分服务 |
| 9-24 P0 缺框架 ADR | **判定为误报**，ADR 0001 早已存在（前版未核查 `docs/adr/`） |

**结论**：项目处于「单人高质量持续演进」状态。工程纪律（19 job CI、类级覆盖率门禁、真实基础设施契约测试、SHA-256 发布校验与自动回滚、169 份文档）显著优于多数同类项目，且具备罕见的**自我纠错闭环**。天花板是 **Bus factor = 1** 与 **God-class 集中度**，前者结构性、后者持续治理中。

**面试高光点**：① 评估→整改闭环（有第三方核对记录）；② 真实发布 502 自动回滚 + 产物哈希核验；③ 类级覆盖率门禁设计思路（先类级后全局，避免无基线外推）；④ ADR 中"只对实际启用的适配器给兼容结论"的证据观。
