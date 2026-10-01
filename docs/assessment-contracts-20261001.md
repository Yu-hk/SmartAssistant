# 项目评估契约整改与验证（2026-10-01）

## 结论与基线

源代码基线为 `943b0af4`，工作分支为 `codex/assessment-contracts-20261001`。本轮针对评估报告中缺少的 Embedding 服务契约及旧生产就绪结论补证据，没有将定向测试扩写为全模块覆盖认证。

新增的 39 项服务端契约与 Common 的 2 项既有适配器回归共 41 项 Java 定向测试通过，另有 66 项离线产物、发布与探针防护测试通过。真实 Spring 关闭用例发现模型重复释放，已用最小 Bean 销毁配置修复；生产原服务的 8 项合成只读 HTTP 检查通过，实测维度为 512。此处的生产检查发生在候选部署之前，不能证明修复产物已经上线。

候选产物已于本次生产切换上线，旁路及切换后 HTTP 检查、Chrome/Edge 业务浏览器验收通过，具体工件和停止结果见下文。未执行 SSE/MQ/多副本的进程级停机演练；远程门禁须以关联提交的实际检查为准。上线结果不能覆盖下面的前置检查或历史失败。

## 本轮落实

### Embedding HTTP、装配与销毁契约

- [EmbeddingControllerContractTest](../smart-assistant-embedding-service/src/test/java/com/example/smartassistant/embedding/EmbeddingControllerContractTest.java)通过真实本机 HTTP 服务器和 JSON 转换器测试生产 Controller，而非直接调用方法。覆盖健康/维度、UTF-8 单条与批量、输入顺序、部分批量失败保留空数组、缺失/空输入、畸形 JSON、类型错误、请求方法及内容类型。
- 保留既有 HTTP 协议：模型不可用的健康查询仍返回 HTTP 200 / `DOWN`；空文本/空批次返回 HTTP 200 的错误对象；批次中失败项保留原位置的空数组。本轮没有改状态码、端点名称或为旧协议补造高质量向量。
- [EmbeddingApplicationContextTest](../smart-assistant-embedding-service/src/test/java/com/example/smartassistant/embedding/EmbeddingApplicationContextTest.java)启动真实 Spring 应用，检查单模型 Bean、配置路径、有限扫描范围和降级状态；显式覆盖配置位置和导入，关闭 Nacos/服务注册/遥测，不读取工作区 `.env`，不接生产存储。
- [EmbeddingApplicationLifecycleTest](../smart-assistant-embedding-service/src/test/java/com/example/smartassistant/embedding/EmbeddingApplicationLifecycleTest.java)通过真实 Spring 关闭流程触发生产 `@PreDestroy`，分别检查可用/不可用模型只释放一次、未初始化时安全，以及重复关闭上下文不重复清理。模型构造使用受控替身，不加载真实 ONNX；这不是原生资源泄漏、操作系统信号或容器停止验证。

### 重复关闭发现与最小修复

新增的正常、降级两条关闭测试在修复前都观察到 `BgeEmbeddingModel.close()` 被调用两次：一次来自 `EmbeddingApplication.cleanup()`，另一次来自 Spring 对 Bean 的推断销毁回调。两条用例的旧状态不计为通过。

[EmbeddingApplication](../smart-assistant-embedding-service/src/main/java/com/example/smartassistant/embedding/EmbeddingApplication.java)仅将模型工厂声明改为 `@Bean(destroyMethod = "")`，关闭推断的 Bean 销毁回调，保留 `Application.cleanup()` 作为唯一释放入口。没有改模型路径、扫描边界、HTTP 协议、降级策略或线上启动配置。

旧/新编译类的 `javap -p -c` 输出一致，差异是 `@Bean` 新增的 `destroyMethod=""` 注解值。受限 overlay JAR SHA-256 为 `32547b1a2ada5d8fbbd7e2492f4bc45310da77692ad1f5788de6e27718e62467`，不替换生产依赖或模型文件；本次切换后的运行容器已核对为此摘要。

### 受控线上协议探针

[verify_embedding_service_live.py](../scripts/verify_embedding_service_live.py)仅接受专用 Embedding 容器名，检查容器运行状态、固定内部网络和私网 IPv4，再调用限定健康/维度/单条/批量端点。使用冻结合成文本，不调用业务服务、LLM、下单或资金接口，不重启服务。响应大小和等待时间有上限，向量须长度一致、数值有限且非零；不会根据 health 的固定型号字符串猜测维度或模型身份。

[离线探针契约](../scripts/test_embedding_service_live.py)用模拟容器检查和 HTTP 返回，覆盖越界服务/网络拒绝、无效向量、模型不可用、批次顺序错误及完整检查。其通过不等于执行了真实生产探针。

探针拒绝 HTTP 重定向及链路本地地址（包括云元数据地址），不让私网验证逃逸到其他服务。向量形状与非零检查仍不能证明语义质量：固定的非零向量可以通过形状检查，不能因此宣称检索质量已认证。

### 单类产物与发布防护

[候选构建](../scripts/build_embedding_lifecycle_overlay.py)仅替换冻结生产 JAR 中的一个类。[独立二进制验证](../scripts/verify_embedding_lifecycle_artifact.py)按类、字段和方法绑定比较 visible/invisible 注解，并核对方法代码一致；仅允许目标模型工厂的 `@Bean` 增加空 `destroyMethod`，要求 `@PreDestroy` 仍绑定 `cleanup()V`。不是在常量池找到注解字符串就算通过。

[一次性发布工具](../scripts/release_embedding_lifecycle_20261001.py)保存 0600 原始配置快照，校验资源下限、工件及模型挂载，先做未启动容器预检、旁路真实模型验证和正常停止，再允许生产切换。停止失败、137/OOM、配置漂移及不完整旁路回执均拒绝放行；失败保留工件和回执，并尝试恢复原容器和 Gateway。它不是全系统排空认证。

### 生产就绪文档修正

[生产就绪清单](production-readiness-checklist.md)已重构为证据矩阵，明确 `2026-06-23 / 0cc6589` 是旧文档自述快照，当前基线另列。取消“12 项中 8 项通过”的混合总分，分别标注代码已实现、有范围验收、部分实现、待验证。

主要纠正了在线 PostgreSQL 受控 Agent 记忆与离线文件/Redis 镜像的边界、Gateway Redis 与内部本地限流、已存在的会话关闭/删除/取消归属保护、部分 Prompt 刷新与未接业务的灰度、已有 Checkpoint 和有界重检索。配置存在不当作观测闭环，局部恢复测试不当作多副本故障认证，在线词项覆盖不当作逐请求 Ragas 四维评分。

## 本地验证与覆盖率

| 验证 | 本轮结果 | 证据边界 |
| --- | --- | --- |
| EmbeddingControllerContractTest | 32 项通过，零失败/错误/跳过 | 真实本机 HTTP，模型替身，不是模型语义质量 |
| EmbeddingApplicationContextTest | 4 项通过，零失败/错误/跳过 | 真实 Spring 装配与配置隔离，不是生产环境装配验收 |
| EmbeddingApplicationLifecycleTest | 3 项通过，零失败/错误/跳过 | 修复后真实 Spring 关闭；不覆盖进程级 SIGTERM/SIGKILL |
| EmbeddingClientTest / RemoteBgeEmbeddingModelTest | Common 既有 2 项通过，零失败/错误/跳过 | 定向远程适配器契约，不是生产网络认证 |
| test_embedding_service_live.py | 9 项通过 | 离线模拟及本机重定向拒绝，不连接生产 |
| test_embedding_lifecycle_overlay.py | 4 项通过 | 单类替换、基线及文件保留拒绝条件 |
| test_embedding_lifecycle_artifact.py | 17 项通过 | 真实 JDK 编译夹具及绑定注解/代码篡改负例；不加载模型 |
| test_embedding_release_lifecycle.py | 36 项通过 | 发布、配置保留、正常停止与回滚纯模拟；不是生产演练 |
| CI YAML 结构 | 本地 PyYAML 解析及新增步骤/门槛/报告结构检查通过 | 不是远端 GitHub Actions 执行结果 |

干净构建后按 CI 相同测试选择进行本地离线验证，Java 共 41 项，零失败/错误/跳过；五份报告的执行数量检查与两类覆盖门槛都通过。当前服务模块 Surefire 报告位于 `smart-assistant-embedding-service/target/surefire-reports/`，JaCoCo 报告位于 `smart-assistant-embedding-service/target/site/jacoco/`；Common 对应报告在其模块 target 下。它们是本地生成物，不提交仓库。以下实测只表示被测类，不代表父模块或项目整体覆盖率。

| 类 | 指令 | 分支 | 行 | 未覆盖项 |
| --- | ---: | ---: | ---: | --- |
| EmbeddingController | 302/302（100%） | 22/22（100%） | 53/53（100%） | 当前报告无未覆盖计数；不证明全部外部异常已验证 |
| EmbeddingApplication | 55/60（91.67%） | 4/4（100%） | 15/17（88.24%） | `main` 方法未覆盖，不能标为 Application 100% |

新 [embedding-service-contract](../.github/workflows/eval-gate.yml)作业使用 JDK 21 / Python 3.12，选择上述三组服务测试及 Common 的 EmbeddingClientTest、RemoteBgeEmbeddingModelTest。分别要求最低测试数 32/4/3/1/1，且零失败/错误/跳过；缺报告不放行。作业独立校验两类 JaCoCo 指令/分支门槛，离线运行四组 Python 防护套件，并在成功或失败时上传 Surefire 和 JaCoCo 报告。此处记录提交前的本地门禁核对；远端执行结果以关联提交的 PR / Actions 检查为准，不能仅凭配置声明通过。

复现该定向选择：

```sh
mvn -B -pl smart-assistant-embedding-service -am clean test -Dtest=EmbeddingControllerContractTest,EmbeddingApplicationContextTest,EmbeddingApplicationLifecycleTest,EmbeddingClientTest,RemoteBgeEmbeddingModelTest -Dsurefire.failIfNoSpecifiedTests=false
python -m unittest discover -s scripts -p 'test_embedding*.py' -v
```

不得将这些测试指向生产数据库、Redis 或 MQ；本组测试不需要这些服务。需要外部依赖的其他套件必须另设隔离环境并记录是否实际执行。

## 覆盖门禁与全模块基线的边界

以下是配置门槛，不是本轮各模块的实测覆盖率。已有门槛未因本次新增服务契约而降低。

| 当前门禁 | 指令 / 分支最低值 | 口径 |
| --- | ---: | --- |
| EmbeddingController | 95% / 90% | 新增精确类级门禁 |
| EmbeddingApplication | 80% / 90% | 新增精确类级门禁；保留 main 未覆盖事实 |
| ProductEvidenceResponsePolicy | 85% / 70% | 现有精确类级门禁 |
| AdminFaqService | 80% / 60% | 现有精确类级门禁 |
| StreamTurnRecorder | 80% / 65% | 现有精确类级门禁 |
| RedisSseProgressForwarder | 80% / 60% | 现有精确类级门禁 |
| Router 选定测试形成的 BUNDLE 基线 | 14% / 9% | 12 个选定测试类，不是 Router 全量测试认证 |
| 父 POM 默认 BUNDLE 规则 | 30% / 20% | 默认 `jacoco.haltOnFailure=false`，不等于默认硬阻断 |

精确门槛见 [eval-gate.yml](../.github/workflows/eval-gate.yml)，父规则见 [pom.xml](../pom.xml)。[verify_jacoco_class.py](../scripts/verify_jacoco_class.py)拒绝缺目标类、缺计数器或空计数器；测试实际执行数量由 [verify_integration_reports.py](../scripts/verify_integration_reports.py)核对，跳过不算成功。

全模块覆盖率仍未建立稳定的全量基线，不能从这两类或已有四类门禁推导“所有模块达到 60%/80%”。下一阶段应逐模块固定测试配置，保存干净报告、总数/失败/跳过、外部依赖条件、提交及工具版本，再单独评审全模块阈值；缺报告或条件性跳过应记为不具备该基线。历史局部门禁及验证范围见 [2026-09-25 整改记录](assessment-remediation-20260925.md)。

## 部署前生产只读检查

运行现有 Embedding 服务的 8 项真实 HTTP 检查全部通过：应用健康、模型可用状态、维度接口一致、单条数值向量、双文本批次/顺序一致、缺文本、空白文本及空批次旧协议。实测 `dimensions=512`，`business_writes=0`，`restarts=0`。

受限回执：服务器 `/opt/smart-assistant/releases/assessment-contracts-20261001/before-live.json`；本地 `D:/workspace/SmartAssistant/.codex-artifacts/assessment-contracts-20261001/before-live.json`。本地文件 SHA-256 为 `e3ee82016defdf944b285ebad9dd5d5a93f3b58d960877bcd29270a11eb09b28`。原始运行证据不提交仓库，不记录凭证或真实用户文本。

health 的 `model` 字符串在 Controller 内固定为 `bge-large-zh-v1.5`，不认证实际模型权重身份；512 是本次响应观测，不是由名称推断的长期规格。向量非零/有限与输入顺序检查不能代替语义质量、生产语料召回率、LLM 答案质量或跨节点稳定性评测。

## 停机只读核对与未完成矩阵

本节的全系统范围仅核对配置、调用边界和既往记录，没有修改全系统停机预算、信号入口或历史发布脚本，也没有启动跨业务隔离故障演练。本轮独立 Embedding 的实际正常停止在下文另列，不能扩展为此矩阵通过。

| 当前证据 | 未能证明的目标 / 下一阶段 |
| --- | --- |
| Consumer / Router 等 HTTP 优雅停机每阶段 30s；Consumer 通用任务、定时/Embedding/Scheduler 等及 Router 并行执行器等待 60s，Consumer 路由日志另为 30s。见 [Consumer 配置](../smart-assistant-consumer/src/main/resources/application.yml)、[ThreadPoolConfig](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/config/ThreadPoolConfig.java)、[RouterThreadPoolConfig](../smart-assistant-router/src/main/java/com/example/smartassistant/router/config/RouterThreadPoolConfig.java)。 | 30s 和 60s 是不同阶段/组件值，不能只取最大值当成已验证总预算。先测实际阶段和销毁关键路径，再确定容器宽限；不直接把 60s/90s 当作足够。 |
| Consumer 长请求决策等待 120s、MQ 默认结果等待 150s、MVC 请求超时 180s。 | HTTP 30s 排空不能保证所有合法在途请求完成；需要跨预算 SSE 的成功/安全截断及持久化状态验证。 |
| [Compose](../deploy/docker-compose.yml)未统一 stop_grace_period；[根 Dockerfile](../Dockerfile)和 [deploy Dockerfile](../deploy/Dockerfile)用 sh -c java，无显式 exec。[9 月 28 日记录](profile-admin-production-verification-20260928.md)已有默认 10s 后 SIGKILL，彼时 MQ ready/unacked 为零。 | 信号转发需隔离验证，不能直接把启动形式归因为历史强杀原因。进程级 TERM、总耗时、退出原因与无强杀尚未验收。 |
| [发布脚本](../scripts/deploy_assessment_20260925.py)的停止调用使用 30s，[Consumer 切换脚本](../deploy/scripts/cutover_data_intake_consumer.sh)使用 45s；容器创建/等价校验未统一保留 StopTimeout/StopSignal。 | 调整要覆盖创建、停止与回滚路径，不能只改 Compose 或只提高 Boot 属性。实际运行配置须重新盘点。 |
| [ChatDispatchListener](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/dispatch/ChatDispatchListener.java)拒绝 RUNNING/MISSING/CONFLICT/UNCERTAIN 自动重放；[ChatDispatchStore](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/dispatch/ChatDispatchStore.java)保留尝试围栏和不确定占用。 | 强杀后的安全状态可以是“结果不确定、等待核查”，不是自动成功或盲目重试；MQ 队列为空不证明业务结算已完成。 |
| [ConversationGateService](../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/session/ConversationGateService.java)有请求租约与续租线程；[Router 执行租约](../smart-assistant-router/src/main/java/com/example/smartassistant/router/service/recovery/WorkflowExecutionLeaseService.java)在 checkpoint 写入前校验所有权。 | 需测试 TERM、租约实际过期和恢复竞争，证明旧 owner 不覆盖新 owner、完成节点不重复执行；聊天派发和恢复队列的重放语义不能混同。 |
| 当前 drain 检查聊天/画像队列及 SQL ACTIVE_RUNNING。 | 不证明 SSE 连接、Router 恢复任务/租约、日志与旁路任务全部排空；还须独立回执。 |

正确隔离顺序是先停止新接入/派发，在 PG/Redis/MQ 仍可用时排空和结算，再关闭业务组件，最后回收依赖。容器宽限应覆盖生命周期阶段、执行器销毁关键路径及持久化余量；不能让数据库先于仍在执行的请求关闭。

最小演练矩阵采用独立网络和 PG/Redis/RabbitMQ、合成账号/文本、固定假 Router/Agent；不继承生产 Env、不接真实用户、不执行订单/余额/支付。每组至少重复三次，保存工件摘要、TERM 时间、JVM 日志、退出原因、队列 ready/unacked/DLQ、请求/会话状态、lease TTL 与假工具执行次数。

| 待演练场景 | 最小通过条件 |
| --- | --- |
| 空载 SIGTERM | 实际候选入口下 JVM 收到 TERM，阶段及总耗时可核查，无 SIGKILL |
| SSE 短在途、30–60s 及超过 60s | 完成或明确安全截断；成功仅一次 done；失败/未确认回合不提交成功画像 |
| MQ QUEUED / RUNNING | 未取得执行权的请求重启后执行一次或明确过期；RUNNING 保留请求 ID/不确定状态，不重复调用假工具 |
| 结果持久化后、ACK 前 TERM | 重投不重复执行，既有结果可查询，无静默丢状态 |
| Router checkpoint/lease 切换 | 正常释放或实际过期才接管，旧 owner 不覆盖 checkpoint 或删除新租约 |
| 日志/画像尾任务 | 成功持久化与提交有回执；依赖最后关闭，失败/截断不冒充成功 |

刻意超预算病例单独报告，不混入正常停机通过率。[现有租约 Redis 集成测试](../smart-assistant-router/src/test/java/com/example/smartassistant/router/service/recovery/WorkflowExecutionLeaseRedisIntegrationTest.java)会删除测试租约键，绝不能指向生产或共享 Redis；已有单元/消息契约不能替代这张进程级演练矩阵。

## 流程图与后续回执

[Embedding 模型生命周期交互图](architecture/embedding-lifecycle.workflow.html)及[冻结规范](architecture/embedding-lifecycle.workflow.json)区分正常/降级路径与唯一 Application.cleanup 释放入口。最终 showcase 9/9、零错误/警告；四个桌面视口的自动浏览器检查通过，已实际审查两端尺寸的浅/深主题截图，`visual_review=passed`，共两轮聚焦修正。此回执仅对应图表，不是候选服务的业务浏览器验收。

- 规范 SHA-256：`51f3fe3820b9964c77c05415da469cbb96a81b9030f028e7b07bf70b7d60ce4c`，2669 bytes。
- HTML SHA-256：`48889638a7150f6e260886eb267b070152a2c62fabe3e331e9c47dbf4ea2fb43`，803037 bytes。

候选发布按 [发布/回滚检查单](deployment-rollback-runbook.md)记录精确目标工件/镜像/挂载、前后 SHA、备份、停收/排空、内部/公开健康、修复后协议回执及失败/回滚。业务浏览器验收和图表视觉验收分别记录；Embedding 内部协议检查不可冒充两者。

## 本次实际生产发布回执

生产目录为 `/opt/smart-assistant/releases/assessment-contracts-20261001`，基线 JAR SHA-256 为 `27b51d0a143fdf7b49d20e0105a4f3f54c89238f2998f8554dc240abcb9631c4`。原镜像、模型挂载及启动参数保持不变，仅 JAR 中的 EmbeddingApplication 类发生上述注解变化；二进制 v2 回执绑定实际注解与方法代码。原容器及基线 JAR保留供回滚，停止的旁路容器保留供核查。

预检先后发现发布工具误把 Podman 数字停止信号当作字符串、误把通用 `oci` 标记当作可执行运行时，以及环境变量顺序、生成的创建命令和各容器默认日志路径差异。所有拒绝发生在模型启动/生产切换之前。修复仅兼容这些已核对表示：实际运行时仍为 `runc`、注解全部复制并精确比较、环境变量名称唯一且值完全相同、仅自身容器 ID 绑定的默认 k8s 日志路径可规范化；未知差异仍拒绝。完整原始配置和错误证据存为 0600，不进入仓库或输出凭证。

| 本次实际操作 | 回执结果 | 范围 |
| --- | --- | --- |
| 未启动容器预检 | 最终通过，预检容器正常移除 | 配置等价检查，不加载模型；不强制删除任何原容器 |
| 旁路实际模型 | 8 项 HTTP 检查通过，512 维 | 不注册业务服务发现，只用合成文本 |
| 旁路正常停止 | 2026-10-01 21:47:18 +08:00；约 0.668s；退出 143；无 OOM；停止后 HTTP 不可达；模型释放日志计数 1 | 停止命令宽限 60s；这是空载 Embedding 的实际关闭，不是泄漏或全系统认证 |
| 生产停收/排空 | Gateway 停收；既有聊天/画像 MQ 队列及 ACTIVE_RUNNING 检查通过 | 不证明 SSE、Router lease、恢复及尾任务全部排空 |
| 原 Embedding 正常停止 | 2026-10-01 21:50:13 +08:00；约 10.756s；退出 143；无 OOM | 本次停止命令宽限 60s；容器原 StopSignal=15、StopTimeout=10 仍保留 |
| 候选生产切换 | `deployment.json.status=deployed`；运行 JAR 摘要与候选一致；8 项修复后检查通过；512 维 | 仅切换 Embedding；未替换其他业务服务 |
| Gateway 与公开健康 | Gateway 已恢复，公开健康检查通过 | 正常发布成功；本次未触发回滚，不能把模拟回滚测试称为真实失败回滚 |

原始旁路和部署回执分别为服务器的 `canary-verified.json`、`deployment.json`；不含环境凭证的本地副本保存在 `D:/workspace/SmartAssistant/.codex-artifacts/assessment-contracts-20261001/`。HTTP 回执中的 `restarts=0` 仅表示探针不重启容器，不表示本轮没有发布切换。

### Playwright 真实浏览器验收

使用新建专用 QA 账号，同账号分别登录真实 Chrome 和 Edge。冻结 4 个问题（库存计算/证据不足、锁定与释放差异、两商品数量总价、已知价格与未知重量），共 8 次咨询均正常；逐次刷新后答案、来源及 Token/工具快照一致，Edge 可恢复 Chrome 的首段会话，浏览器脚本异常为零。Chrome 的 4 次均为实时执行；Edge 的 2 次实时、2 次缓存分别明确显示来源，不将缓存零用量冒充实时统计。

本地回执为 `D:/workspace/SmartAssistant/.codex-artifacts/assessment-contracts-20261001/browser-after/browser-report.json`，8 次刷新及跨浏览器恢复均通过；已实际查看 Chrome 首题和 Edge 末题截图。此组验证写入并保留一个 QA 账号及 8 段合成测试会话，不修改真实用户数据，不下单、不改商品目录、不扣账户余额。回执 `businessWrites=0` 指上述订单/目录/钱包写操作为零，不表示注册和会话没有存储写入。

这不是全量页面、所有用户场景或 Embedding 语义质量认证；不会由本轮结果宣称停机矩阵、全模块覆盖基线已经完成。后续远程检查见[本次工作分支关联 PR](https://github.com/Yu-hk/SmartAssistant/pulls?q=head%3Acodex%2Fassessment-contracts-20261001)，必须等待实际门禁通过后再合并。
