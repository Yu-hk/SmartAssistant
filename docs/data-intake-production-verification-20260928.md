# 商品录入独立服务：2026-09-28 生产验证

范围：把管理员商品录入、规则参数提取和既有商品参数维护从 Consumer 拆到 `data-intake-service`；FAQ 暂不迁出。对外 `/api/admin/products/**` 路径保持不变。

## 已验证

- 新服务 JAR SHA-256 `1e693e9ff209e1cea5766c9fa6c2c2bfd3e7aef97b09bc283cef999d315e3e4d`，Gateway JAR SHA-256 `9bb7ff4bcc7ac8c849895ccc0f6e603376aa63c75ecc94ebf1e553c0005f5913`；服务器上传后与本地逐一比对一致。切换前 Gateway JAR 备份 SHA-256 `a055461e558638d075c5988b39675f7d01d8b9438b421528c5bd90cc7d726867`。
- `smart-data-intake` 仅加入 `smart-network`，未发布公网端口；readiness 为 `UP`，Nacos 日志确认 `data-intake-service:8092 register finished`。Gateway 内部路由检查确认裸路径与 `/assistant` 前缀路径均指向 `lb://data-intake-service`；Gateway readiness 为 `UP`，公网 `/healthz` 返回 200。
- 只读预览返回规则版本 `intake-rules-v1`、净重 200 克及原文依据。
- 唯一 QA 编码 `QA-DATA-INTAKE-20260928`、库存状态“缺货”的虚构商品通过新服务创建，HTTP 201。数据库核对价格 1.00、净重 200.000、降噪 `false`、参数版本 1、`externalVerification=false`；Product 的商品详情接口可读取该行。随后按完整编码、名称、库存、价格、版本五项限定删除，`DELETE 1`，再次查询无该行。未创建订单。
- 无令牌与伪造 `X-User-Role`/`X-User-Id` 的 Gateway 请求均返回 401。检查窗口内 Gateway 和 Data Intake 无 ERROR/Exception 日志。
- 本地原工作区三个相关模块完整干净测试通过；从最新 `origin/main` 整理的独立分支，针对录入、参数持久化、应用启动与路由的集中测试通过，Compose 配置校验通过。Windows 工作树路径下 JaCoCo 原生代理启动失败，`-Djacoco.skip=true` 后测试通过；这不是测试断言失败。

## 尚未覆盖及发布边界

未持有普通管理账号的浏览器登录态，本轮仍未执行“管理员页面 → Gateway → Data Intake”带真实登录令牌的端到端 UI 写入。

## Consumer 后续切换（同日）

- 从已合并 `main`（`e27c7592`）重新构建 Consumer，与线上旧 JAR 的 ZIP 条目差异降至 23 项：旧录入类及规则资源被移除；另有会话/澄清相关类、`application.yml` 和一个公共接口类变化。三项嵌套项目 JAR 中只有公共接口类有实质差异。完整 GitHub CI 均已通过。
- 候选 JAR SHA-256 为 `3d4611d62130f2f1e58365e8983bba0d8aa6760681151a0536ecf2e115ebbd5a`；旧 JAR SHA-256 为 `86486049dcc3458dbe3f05d8b59a87420ee45a653043722670c1836a4d05699d`。上传后逐一校验，旧版备份保存在同一受限发布目录。
- 第一次停止 Consumer 时，Podman 默认 10 秒超时后报告 conmon 退出码异常；自动回滚已确认旧 JAR 哈希、容器运行及 readiness `UP`。修订脚本为 45 秒优雅停止，并要求运行时明确确认停止状态后才复制文件。第二次切换成功，新 JAR 哈希一致；Consumer、Data Intake 与 Gateway readiness 均为 `UP`，Gateway 商品录入路由仍指向 `lb://data-intake-service`。切换后磁盘剩余约 8.0 GiB。
- Consumer 启动时 `generateTrendGif` 远程工具注册曾报错并降级本地；旧版日志亦有同一报错，因此不归因于本次切换。其他两个服务检查窗口内无 ERROR。浏览器管理员端到端验收仍单列为未覆盖项。

## 回滚

生产为挂载 JAR 的现有容器，而非本次直接重建 Compose。发布目录 `/opt/smart-assistant/releases/data-intake-20260927/` 保留新服务 JAR、Gateway 新 JAR、旧 Gateway 备份及旧 Consumer 备份。若需要整体回滚，**先**停止 Consumer，把 `consumer-before-main-e27c7592.jar` 恢复到其已核实的挂载源 `/opt/smart-assistant/releases/order-clarification-v2-20260926/smart-assistant-consumer-1.0.0-SNAPSHOT.jar`，启动并验证 Consumer；**再**停止 Gateway，将 `gateway-before.jar` 恢复到 `/opt/smart-assistant/releases/checkout-history-20260927/gateway.jar`，启动并验证 Gateway；最后停止 `smart-data-intake`。不可先回滚 Gateway，因为当前 Consumer 已不含旧录入端点。不回滚数据库表结构，已清理的 QA 商品不会恢复。
