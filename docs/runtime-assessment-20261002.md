# 工具执行层与隔离停机验收（2026-10-02）

## 本轮范围

基于 main `353170af`（PR #128）推进分析报告中的测试欠覆盖与真实停机证据缺口。本轮只新增测试、独立探针、文档与 CI，不改运行代码、生产 Dockerfile、业务流程或架构，因此不重新部署生产服务，也不重绘未变化的 Archify 架构图。生产停机入口的修正须作为下一批运行变更单独验证和发布。

## 工具执行层

工具模块由 2 套件 / 6 项执行增至 4 套件 / 79 项执行（含参数化输入，共新增 73 项），零失败、错误、跳过。覆盖以下实际行为：

- 计算结果舍入、非有限数提示、无效表达式的结构化错误。
- 9 种温度转换方向、长度/重量单位与 SI 基准的换算、缺单位失败、同币种规范化。
- 纠错查询作用域、脚本成功透传、错误码/恢复提示与 JSON 转义。
- 沙箱输入、行数、变量、表达式和输出限制；重赋值、局部失败可见、并发执行变量隔离。
- 超时取消、内部失败与线程中断恢复的确定性替身检查；真实沙箱到数据提供层的成功/失败映射。
- 完整目录名称与重复注册边界；共享只读风险标签；脚本和图片生成不被误标为只读、不增加自动重试。

| 被测类 | 指令覆盖 | 分支覆盖 | 边界 |
| --- | ---: | ---: | --- |
| ScriptSandbox | 524/530（98.87%） | 50/54（92.59%） | Future 异常分支采用替身，不证明实际时间预算或进程释放 |
| GeneralDataProviderImpl | 31/31（100%） | 2/2（100%） | 使用真实受限数学沙箱，不是任意代码执行 |
| GeneralToolCatalogRegistrar | 152/152（100%） | 无分支计数 | 不把零分母写为 100% |
| GeneralTools | 351/1552（22.62%） | 22/170（12.94%） | 联网新闻、搜索、汇率等尚未覆盖 |
| tool-runtime 整模块 | 1357/4037（33.61%） | 74/336（22.02%） | 图片/GIF及联网路径仍有缺口，不能宣称整个工具层覆盖完备 |

沙箱输出上限的现有语义是在每行执行前判断并标记截断，最终文本还会附加提示与结果；不是严格的最终字符/字节上限。测试保留此事实，不通过调整断言虚称硬上限。沙箱停止后再次提交任务的行为、本地执行器生命周期及多源联网竞速仍需后续专项审查。

CI 对 ScriptSandbox 设置 95% 指令 / 85% 分支最低门槛，对数据提供层设置 95% / 95%；不提高或降低父模块全局门槛，不从两个被测类外推整体覆盖。

## 两轮全模块基线

采用[同一审查入口](../scripts/run_offline_baseline.py)和[更新清单](../scripts/offline-baseline-policy.json)，新增的两个离线套件显式纳入。两轮干净测量均为 12 模块、421 套件源 / XML、2,568 项执行，零失败、错误、跳过；保持原 30 个非本组源文件的分类和排除理由。新进程 Fixture 不是 Surefire 套件，不能混入离线通过数。

原始回执保存在本机 `.codex-output/offline-baseline/runtime-first/` 与 `runtime-second/`；两轮分别保存完整原始 Surefire / JaCoCo。远程 CI 仍须实际执行，新增配置本身不等于通过。

两轮耗时为 234.50 / 233.11 秒；现有 28 项离线回执检查和新增 11 项进程回执防护检查均通过。防护检查涵盖不完整/重复矩阵、伪造成功、强杀冒充正常退出、远程 Docker 拒绝与越界清理拒绝，不作为真实进程用例计数。

## 真实进程探针的范围与复现

[Java 验证入口](../smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/service/dispatch/DispatchShutdownFixture.java)运行真实 ChatDispatchStore、ChatDispatchListener、ChatDispatchRabbitConfig，以及真实 Redis / RabbitMQ。Router 返回固定合成结果；在 ACK 前设置可控暂停点。只组装这一组组件，不扫描完整 Boot 应用，不启用 Nacos/HTTP/模型，不读取生产环境，也不连接 PG、商品、订单或钱包。

[启动与核验脚本](../scripts/run_dispatch_shutdown_probe.py)仅接受本机 Docker socket；创建随机标记的内部隔离网络，不发布端口、不使用共享数据卷，数据写入 tmpfs。只按精确容器 ID 且标签一致清理；移除前保留日志和配置回执。依赖工件、源码、镜像 ID 与 Git 提交均记入报告。tmpfs 清理后合成消息/状态不可恢复，证据文件保留。

```sh
mvn -B -pl smart-assistant-consumer -am -DskipTests install
mvn -B -pl smart-assistant-consumer dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/shutdown-classpath.txt
docker pull bellsoft/liberica-openjdk-debian:21-cds
docker pull redis:7.2.4
docker pull rabbitmq:4.1-management-alpine
python scripts/run_dispatch_shutdown_probe.py --classpath-file smart-assistant-consumer/target/shutdown-classpath.txt --output .codex-output/dispatch-shutdown/new-run --entrypoint-form exec
```

每次输出目录必须是新目录；不能在同时执行的 `clean` 过程中读取/封装 target 工件。打包准备阶段的 `-DskipTests` 不是验收通过证据；必须另行实际执行测试和下述进程核验。

`--entrypoint-form shell` 使用与当前 Dockerfile 相同的 `sh -c java` 启动形式（不是生产 JAR）。本机首次真实空载停止失败：12 秒宽限后退出 137，JVM TERM 开始/结束标记均为零。回执为 `.codex-output/dispatch-shutdown/run-4/`。这确认了此隔离镜像和入口形式的信号转发问题，不认证所有生产容器的当前行为，也不能据此把历史强杀唯一归因于入口。

前置启动失败另行保存在 run-1/run-3：日志显示 cookie 的 EACCES，与 root 健康探针抢先初始化 cookie 的竞态一致。改用指定 Alpine 镜像实际 UID 100 / GID 101 执行健康探针及队列检查、正确配置 tmpfs 属主后恢复；不修改生产权限，不读取 cookie 内容。run-2 为干净构建删除依赖清单后的准备失败，未启动测试容器。这些失败不算通过次数。

## `exec` 对照矩阵与未覆盖项

显式 `exec` 只应用于独立测试入口；正常 TERM 与刻意 KILL 单列。每组重复 3 次，并记录队列 ready/unacked/DLQ、Redis 状态与假 Router 的实际调用次数。正常 TERM 要求退出 143、非 OOM、且 JVM 关闭回调开始/结束各一次；不把队列空当成业务结果成功。

| 场景 | 必须满足的状态 |
| --- | --- |
| 空载 TERM | 无强杀、关闭回调完整 |
| QUEUED 后 TERM / 重启 | 停止前未执行；重启后执行一次且结果可查询 |
| 短 RUNNING 后 TERM，宽限内释放 | 正常结算 COMPLETED，调用计数始终 1，重启不重做 |
| 结果持久化后、ACK 前 TERM | 既有结果保留；重投确认而不再次调用 Router |
| RUNNING 刻意 KILL / 重启 | 状态保留 RUNNING、结果不伪造、调用计数不增加；重投进入待核查死信 |

最终本机收紧复跑为 2026-10-02 13:08:33–13:15:35（+08:00），15/15 场景通过。空载组也实际启动监听容器后停机；27 个工作进程的 `/proc/1/comm` 均为 `java`。24 次正常 TERM（含重启后的停止）均退出 143、非 OOM、关闭开始/结束标记各一次；正常停止最长约 5.563 秒。3 次刻意 KILL 均退出 137，单列而不计入正常停机成功率。QUEUED 重启后调用一次；短在途及 ACK 前状态均为 COMPLETED、固定结果保留、调用次数仍为 1；RUNNING 强杀后仍为 RUNNING，无伪造结果、重投入死信、调用计数不增加。

本机回执 `.codex-output/dispatch-shutdown/run-6/report.json` 的 SHA-256 为 `ab7c67767de204f6ff3b7c8a13df9597f99eb42134f1d6d973c24b8dcff6d048`。源码摘要已独立与当前文件核对；该轮 41 个自身标记的临时容器及隔离网络正常回收，未清理其他容器。合成 tmpfs 数据已回收不可恢复，完整日志和状态证据仍保留。run-5 的首轮 `exec` 对照也通过 15 组，但空载组尚未启用监听，仅作前置证据，不替代最终收紧轮。远端门禁结果须与 PR 绑定，不用本地回执替代。

仍未验证完整 Boot 入口、HTTP/SSE 跨 30–60s / 60s 预算、PG 与 checkpoint、Router 租约所有权切换、画像和日志尾任务。下一批须先修正并核验实际创建/停止/回滚入口，再按[完整停机矩阵](assessment-contracts-20261001.md)扩展；不能因为 `exec` 对照通过就标记全系统停机就绪。
