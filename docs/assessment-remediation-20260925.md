# 2026-09-24 项目评估整改记录

评估输入是 `project-assessment-2026-09(2).md`，其基线为 `main@3ea98c09`。
本次可用的本地 `origin/main` 为 `4957de2b`（PR #95）；2026-09-25 经 GitHub 仓库接口核对，
远端 `main` 仍为同一提交。命令行 `git fetch` 遇到连接重置，未产生新的远端跟踪更新。
本记录以该基线的代码为准；报告中的建议先核实，不能把旧快照的缺口直接当作当前事实。

## 本次落实

- 将管理端 FAQ 的持久化、批量导入校验、来源记录和默认问答初始化从 `AdminService` 提取到包内 `AdminFaqService`。`AdminService` 保留公开接口与原有事务边界，避免改变 Controller 调用契约。
- 为 FAQ 增加 H2 PostgreSQL 模式的读写、命中、删除和导入覆盖测试，包含来源/覆盖更新的回归检查。
- 将流式 Controller 的回合日志持久化与成功后画像提交移入 `StreamTurnRecorder`，保持 Controller 对外协议和原调用顺序；新增直接单测及 Controller 回归。
- 将 Redis 列表/Stream 的 SSE 进度转发移入 `RedisSseProgressForwarder`。它继续过滤下游 `done`，由 Consumer 在工具与 Token 用量事件之后统一结束流；新增转发、终止事件过滤与 Redis 故障测试。
- 将商品工作流的已核实目录上下文、空目录与澄清结果判定、推荐证据兜底从 `ProductStreamController` 移至 `ProductEvidenceResponsePolicy`，控制器继续负责协议响应与用量头。保留现有商品目录安全边界及回复行为。
- CI 对三个新拆分边界运行测试并检查 JaCoCo 类级覆盖率：FAQ 指令不低于 80%、分支不低于 60%；回合记录指令不低于 80%、分支不低于 65%；Redis 进度转发指令不低于 80%、分支不低于 60%。缺失报告、目标类或计数器直接失败。阈值只代表被测类，**不代表 consumer 模块整体覆盖率**；现有 Router 门禁也保留。
- 删除 Router `TaskPlannerService.plan()` 的无调用旧版扁平规划入口及其私有解析器，保留仍被 `IntentGraph`、`ExperienceService` 使用的 `SubTask` 数据类型。
- 商品证据策略也纳入 CI 类级覆盖率门禁（指令 85%、分支 70%）；仍不等于 Product 模块整体覆盖率。

## 逐项核对

| 报告项 | 当前结论与处理 |
|---|---|
| 缺 JaCoCo `report`/`check` | 已有父 POM 插件目标，Router CI 已启用硬门禁；本次增加 FAQ 新边界的局部门禁。全模块 60% 不能由少量选定测试外推，需先有稳定的全量基线。 |
| 缺 Boot 4 升级 ADR/runbook | 已有 `docs/adr/0001-framework-baseline.md` 与 `docs/deployment-rollback-runbook.md`，不重复造文档。 |
| `AdminService` 等大类 | FAQ、流式回合记录、Redis 进度转发和商品证据规则已拆，其他大类（会话/统计、执行编排、流式 Controller 的其余协调逻辑）仍需按可回归的调用边界分批拆；不能一次移动以免改变会话/事务/流式时序。 |
| `SmartReActAgent` 工具调用待拆 | 当前已有 `AgentToolExecutor`、`AgentLoopDecision` 与 `LoopGuardService`，报告提出的三个子边界并非完全缺失；后续若继续拆主循环，须保留行为基线与工具并发测试。 |
| 8 处 `@Deprecated` | 已逐项定位并清理无调用的 `TaskPlannerService.plan()`。`ContextCompressor` 仍为活跃 `SummarizationAdvisor` 的父类；RAG 服务中的废弃入口属于活跃类；`MemoryVersionStore` 与 `ConversationDocumentService` 旧构造器仍有兼容测试。其余入口不能只按注解数量删除；先迁调用方，再删除旧入口及更新测试。 |
| gateway/tool-registry/user 缺测试 | 当前已存在 gateway 鉴权集成测试及上述模块的单元/契约测试。真实基础设施覆盖是否足够仍要按模块单独盘点。 |
| common/rag 拆模块 | 暂不搬动 128 个类；先记录依赖方向、Spring 自动装配与对外 API，再用迁移测试保护。 |
| Bus factor = 1 | 文档能降低交接成本，不能创造第二位维护者。架构依据看 `docs/architecture/`、框架决策看 ADR、发布与回滚看 runbook；生产发布仍需要有权限的第二人按清单复核。 |
| 浏览器视觉验收/多副本故障 | 仍属未完成验收；API、组件测试或单机 Compose 健康检查不能宣称替代真实浏览器与多副本故障演练。 |

## 接手者最短路径

1. 确认目标提交与工作树状态，再跑 JDK 21 Maven 构建和相关模块测试；阅读 `.github/workflows/eval-gate.yml` 了解 CI 证据边界。
2. 变更 agent、路由或会话状态机时，先看对应行为基线/并发测试；变更数据库时核对迁移和恢复基线。
3. 发布前按 `docs/deployment-rollback-runbook.md` 做精确的运行产物、挂载、哈希和回滚清点。不要根据文件名猜测线上版本；不要把测试环境成功当成生产验收。
4. 发布后分别确认内部健康、公开入口、鉴权、只读业务、SSE 完成事件及工具/Token 元数据。失败时按清单回滚并保留失败证据。

## 本地验证边界

- JDK 21、Maven 离线构建：consumer 所依赖模块执行 `clean test`，上述 FAQ/流式回归共 24 项通过；Router 的 `TaskPlannerServiceTest` 2 项通过。
- 清理构建产物后重新测得 `AdminFaqService` 指令 90.29%、分支 69.35%；`StreamTurnRecorder` 指令 88.51%、分支 75.00%；`RedisSseProgressForwarder` 指令 90.28%、分支 72.22%。CI 阈值据此设置并留有余量。
- JaCoCo 报告校验器的 3 项正负单测通过；缺类或空计数器按失败处理。
- 商品控制器与推荐安全回归 34 项通过；`ProductEvidenceResponsePolicy` 指令 94.74%、分支 79.45%。
- 曾尝试 consumer 全量测试，但仓库并发压测用例运行时间较长，主动中止；**不能将其记为全量通过**。
- 未做多副本故障演练。本地覆盖率不等于生产表现。

## 2026-09-25 单机线上发布与验收

- 发布服务：`smart-consumer`、`smart-router`、`smart-product`；无数据库迁移、无前端产物变更。发布前使用 `release_artifacts.py` 记录三个运行中 JAR 的挂载、镜像和 SHA-256，并核对 MQ ready/unacked 与运行中会话均为零后切换。
- 候选 JAR 的 SHA-256：Consumer `5017b6270da35c0fa84418a34e5e585fb346a74eafdb0f761eb3825750874d33`，Router `83f02ed6fd05d49211f57769e94dd5a0af16c08edff1d9cd31d4690a01c459e3`，Product `1687109c601174963b0ef542eb64485f43d271584f7f66dcf1ec6605fd516faf`。本地公共依赖及三个 JAR 中的内嵌公共依赖哈希一致。
- 首次切换中三个服务内部健康均通过，但 Gateway 重启后立刻检测公开入口得到一次 502；自动回滚完成，旧版运行哈希通过发布前快照核验，公开 `/healthz` 恢复 200。随后将发布脚本的公开就绪检查改为有界重试，并在回滚前重新暂停入口。
- 第二次切换成功：三个新服务内部健康、公开 `/healthz` 均通过；发布后独立快照证实运行中 JAR 哈希与上述候选一致且均不同于发布前。`/`、`/login`、`/api/public/visit-modules` 返回 200。对 Product 内部协议做无模型、无持久化的空目录路径烟测，收到 `SUCCEEDED` / `EMPTY_PRODUCT_CATALOG`，工具调用空、Token 用量为零。
- 发布证据与旧容器位于服务器 `/opt/smart-assistant/releases/assessment-20260925`；旧容器保留供回滚。该验收不覆盖真实账户登录、完整 SSE 对话、浏览器视觉或多副本故障。尝试浏览器验收时工具连续报 `nodeRepl.fetch request failed`，重置后仍无法取得标签页，因此没有宣称页面验收完成。
