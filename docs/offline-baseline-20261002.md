# 全模块轻量离线测试基线（2026-10-02）

## 范围与发现

本轮从 `e36bdcc6`（PR #127 合并后的 main）开展整改，工作分支为 `codex/assessment-baseline-20261001`。只修改测试隔离、基线回执和 CI，不修改生产业务代码、模型、数据库或运行配置，因此不发布替换生产服务，也不重绘未变化的业务架构图。

首轮无凭证、干净测试暴露 Gateway 集成测试仍依赖外部 `JWT_SECRET`；5 个用例因 Spring 上下文装配失败而报错。Windows 上 9 个 POSIX 日志用例和 1 个符号链接用例按操作系统条件跳过。这一轮明确失败，不计为当前通过证据。

随后复跑还发现 WorkflowRecoveryManagerTest 偶发 `Boolean cannot be returned by poll()`：BeforeEach 已启动后台 poll 线程，用例却继续修改同一个队列 mock 的 retry 返回配置。将后台启动移到全部 mock 配置之后，保留原断言并把瞬时失败/延迟重投用例重复执行 20 次；没有改运行代码，也不把真实停机安全性扩大为此单元测试通过。原失败日志保留。

[Gateway 集成测试](../smart-assistant-gateway/src/test/java/com/example/smartassistant/gateway/filter/GlobalJwtAuthFilterIntegrationTest.java)现在自带明显标记的合成签名配置，显式关闭 `.env` 导入、Nacos 与遥测，不在生产代码加入默认密钥。[基线入口](../scripts/run_offline_baseline.py)使用环境变量白名单，删除继承的 API/存储测试凭证、Spring 导入和 JVM 参数，拒绝相关路径真实 `.env` 及未经审查的 Maven 启动配置。关闭 Boot 日志导出与 trace，避免测试上下文向本机 4318 导出合成日志。

## 固定选择

[审查清单](../scripts/offline-baseline-policy.json)包含 449 个按 Surefire 命名识别的源文件，其中 2 个是辅助类；实际选择 419 个套件源文件，覆盖全部 12 个 Maven 模块。套件数不是测试用例数。新增、删除或改名必须显式审查和更新清单，不能默默扩大测试的外部访问范围。

| 不属于本组的源文件 | 数量 | 单列原因 |
| --- | ---: | --- |
| PostgreSQL / Redis / RabbitMQ 外部集成 | 23 | 需专用合成依赖与显式启用；部分会删除测试键，绝不能指向生产 |
| RagRerankTest / BgeModelComparisonDiagnosticTest | 2 | 加载真实本地 ONNX / tokenizer，含条件跳过及静默返回 |
| ConcurrencyLoadTest | 1 | 合成长耗时负载，不是生产容量认证；存在等待完成判定需要另行审查 |
| ProfileControlJournalTest / ProfileLegacyFilesTest | 2 | Linux POSIX/符号链接边界；现有 Linux 恢复门禁继续要求实际执行，不降低它们的标准 |
| TestRecordingKnowledgeBase / TestSlotSchemas | 2 | 测试辅助实现，不是 JUnit 套件 |

不是简单排除所有 `*IntegrationTest`：AgentMemoryServiceIntegrationTest 使用临时文件、ChatIntegrationTest 使用 MockMvc/替身、GlobalJwtAuthFilterIntegrationTest 使用本机随机端口/Redis 替身，这三组仍执行。

Maven `-o` 只限制依赖解析下载，**不是 JVM 网络沙箱**。本组不配置真实模型或存储访问，允许本机临时 HTTP 服务器及 H2/临时文件；不能把结果称为真实 PG、Redis、MQ、容器或生产验收。修改既有测试内容仍须代码审查，清单名称匹配不能证明新增代码没有外部调用。

## 测量与报告契约

每轮使用全反应堆 `clean test`，保留提交、源码指纹、测试清单与 SHA、执行器 SHA、Java/Maven 版本、隔离属性、执行命令和耗时。每个选中套件须实际执行；报告缺失、空执行、任何失败/错误/跳过、未知套件或无效计数都不放行。JaCoCo 只读取根 BUNDLE 计数，不对 package/class 子树重复求和；空分支不伪造为 100%。

两轮使用不同的新输出目录，下一次 `clean` 之前保存本轮 Surefire XML / JaCoCo XML。原始报告和运行日志只作验收产物，不入库。重复核对绑定源码、选择、隔离配置、工具版本、模块测试计数和被分析代码的计数分母；不允许拿旧模块报告拼接新一轮通过。覆盖命中数按两轮最小/最大区间保留，不强制每一位相等，也不据此宣称覆盖率毫无波动或收益已经认证。

本地首个修复后测量为 12 模块、2,476 项、零失败/错误/跳过，约 309.6 秒；当时仍观察到本机遥测导出尝试，随后关闭，最终配置须独立复跑，不能把先前回执补写成已关闭遥测。

关闭遥测并修复队列测试竞态后的最终两轮均为 2,495 项、零失败/错误/跳过，约 295.1 / 293.3 秒；其中恢复延迟重投用例的 20 次重复贡献了相比原版本新增的 19 项执行，不意味着新增 19 个业务场景。26 项基线回执/隔离/超时清理与重核验防护测试另行通过。以下覆盖率来自最终第一轮，而非前置通过或失败轮。

| 模块 | 用例数 | 指令覆盖 | 分支覆盖 |
| --- | ---: | ---: | ---: |
| common | 878 | 59.23% | 44.17% |
| routing-contract | 3 | 85.58% | 70.00% |
| tool-runtime | 6 | 21.13% | 12.20% |
| embedding-service | 39 | 98.62% | 100.00% |
| consumer | 350 | 52.56% | 40.82% |
| data-intake | 60 | 86.09% | 72.31% |
| router | 465 | 53.18% | 41.97% |
| order | 148 | 47.76% | 43.71% |
| product | 378 | 81.71% | 69.43% |
| gateway | 59 | 71.25% | 54.00% |
| user | 43 | 44.52% | 33.60% |
| tool-registry | 66 | 53.51% | 47.57% |

表格属于上述限定测试选择下的 BUNDLE 测量，不是全部集成/模型测试的覆盖认证，也不是父 POM 默认阈值达标证书。tool-runtime 仍低于父默认 30%/20%，先补关键行为测试再决定阈值；本轮不把全局门槛直接升为 35%/25% 或 50%，不降低已有精确类门槛。

最终第一轮保留 419 份 Surefire XML 和 12 份 JaCoCo XML。验收产物位于工作目录 `.codex-output/offline-baseline/run-20261002-4/`；源码指纹为 `43be8ea2e6688b5d5ce55aa80979407549ca4eb02ebc017e919519c42130f955`，执行器 SHA-256 为 `51a70312a84c8ba7b0f155193544aeca759e8e401f1cbe00c845d778c07b46ba`，清单 SHA-256 为 `a167b3499cae55e90a6fa56cd5f3a345d1f91f052aa541758f5d246f04c64f95`。这些摘要绑定本轮工件，不是主分支未来不变的事实。

第二轮 `run-20261002-5/`同样保留 419 / 12 份 XML；Maven 与所有测试通过，但最初比较器要求覆盖命中位完全一致，因此其历史 `baseline.json.status=failed` 保留不改写。原始 JaCoCo 差异定位到 MemoryExtractor 第 94 行的空/缺候选短路分支，以及 UserProfileService 第 341、346、351、352 行的时间等待分支；没有失败或跳过增加。Common 指令范围 59.2259–59.2274%、分支 44.1679–44.1826%；Consumer 指令 52.5621–52.5831%、分支 40.8159–40.8375%。其他模块的六类覆盖计数相同。不能把两轮当作大量重复或独立性能实验。

修正比较契约后，[独立重核验](../scripts/audit_offline_repeat.py)重新读取两轮全部原始 XML，核对当前源码/清单绑定与 JSON 计数，输出新的 `.codex-output/offline-baseline/final-audit/repeat.json`：`status=passed`、`coverage_repeat_exact=false`、`tests_executed_by_audit=0`。没有重写历史失败或声称重核验又执行了 Java 测试。新比较器 SHA-256 为 `f60a01a8ec8e00f9a5529604f3fb70ca5cf9fda2b3057c24196448261e1e035d`，审核脚本 SHA-256 为 `f4a0e8d9fab33a1484c02abde010a601af53ec41388d47fd34e467c18388c1e2`；新 CI 会实际执行新入口两轮，而不是只上传该离线回放。全局硬阈值应基于下界和更充分样本评审，本轮未新设覆盖率回退阈值；既有精确类硬门槛仍生效。

复现（JDK 21、Maven 3.9+，依赖须已缓存；Windows 可用 `--maven` 指定 mvn.cmd）：

```sh
python3 -m unittest discover -s scripts -p 'test_offline*.py' -v
python3 scripts/run_offline_baseline.py --output .codex-output/offline-baseline/first
python3 scripts/run_offline_baseline.py --output .codex-output/offline-baseline/second --compare .codex-output/offline-baseline/first/baseline.json
```

必须使用新目录；入口不删除既有回执。依赖未缓存时会失败，不自行联网下载模型或启用生产配置。新增 CI 作业先以 `-DskipTests clean install` 缓存清理插件与构建依赖，再用 common 的客户端构造断言 EmbeddingClientTest（不发起 HTTP 请求）和 routing-contract 的纯键名断言 RoutingKeysTest 缓存 Surefire 动态选择的 JUnit provider/launcher，分别覆盖 common 额外仓库及其余模块父级仓库上下文，然后在无模型/存储凭证环境中独立运行两轮。预热用例不重复计入基线；两轮各自 clean，旧报告不可混用。此作业是测量和报告完整性门禁，不是新设的全局覆盖硬门槛。最终远端结论以对应提交的 Actions 为准。

### 首次 Linux CI 的排序兼容修复

[首轮远端运行](https://github.com/Yu-hk/SmartAssistant/actions/runs/36959509335)在测试清单预检阶段失败，未开始基线 Maven 测量；其余既有验收作业通过。实际复现确认六个模块的文件列表仅顺序不同：Windows 的 `Path` 比较忽略大小写，Linux 区分大小写，因此目录名和大写类名的相对顺序不同，449 个条目及选择分类本身没有变化。

策略核对现在按原样字符串统一排序后逐项比较；名称、路径、选择和排除原因仍须完全一致，双方的重复名称或路径明确拒绝，不使用会丢掉重复项的集合比较。审查清单字节和上述历史回执不改写。新增跨平台顺序与双方重复项回归，基线脚本合同合计 28 项。本次修改不改变 Java 源码或测试选择；远端双轮实跑须重新通过，不能把首轮预检失败算成测量通过。

[第二轮远端运行](https://github.com/Yu-hk/SmartAssistant/actions/runs/36960212906)已通过清单预检，但 Maven 在父模块的 clean 阶段发现 `maven-clean-plugin:3.5.0` 未缓存，约 1.2 秒退出，12 个模块均未执行测试；不是业务测试失败，也不计入 2,495 项的执行证据。失败 JSON、日志与当时报告已保留为 Actions 产物。本机已有此插件，CI 新环境只有先前 install 预热，因此修正预热为 `clean install`，不移除后续离线模式或 clean 要求。

[第三轮远端运行](https://github.com/Yu-hk/SmartAssistant/actions/runs/36960615711)common 的 878 项测试零失败/错误/跳过，之后多个模块无法解析 JUnit Platform Launcher 6.0.3：该文件已在 common 的额外仓库上下文缓存，但 Maven 不允许直接在未含该仓库的其余模块离线复用。约 44.9 秒失败，不视为完整基线通过。补充 routing-contract 的纯本地预热，使启动器也在父级仓库上下文完成正常依赖解析；不修改仓库地址、不手工篡改下载来源元数据，也不取消 `-o`。

## 停机演练状态

本机仅 Docker CLI 可用，Linux engine pipe 不存在；没有执行进程级停机演练，也没有停止线上 Consumer / Router。已有 JVM/Spring 销毁回归不能替代 SIGTERM/SIGKILL、SSE 排空或 MQ 重投。

下一批仍按 [既定矩阵](assessment-contracts-20261001.md#停机只读核对与未完成矩阵)使用独立 PG16 / Redis7 / RabbitMQ4.1，合成配置与假 Router/Agent，先核对生产入口和实际组件：ChatDispatchListener / ChatDispatchRabbitConfig、WorkflowRecoveryManager / WorkflowExecutionLeaseService。当前 integration compose 只有 PG 和 MQ，Redis 尚未加入；租约测试会删测试键，必须专用依赖。先停收和结算，依赖最后关闭；不以队列为空、对象重建或假 Harness 代替完整进程演练。
