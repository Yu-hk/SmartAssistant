# 商品录入独立服务发布与回滚

> 2026-09-29 适用标签扩展会新增 `product_suitability_tags` 和商品行审计列，须先执行对应迁移并备份数据库，再发布 Data Intake、Product 和管理员前端。以下原始步骤只描述此前无表结构变更的拆分发布，不能直接用于本次扩展。

本次仅迁出管理员商品录入、确定性参数提取和参数维护；FAQ 导入与维护仍由 Consumer 承载。对外路径 `/api/admin/products/**` 不变，Gateway 将其定向至 `data-intake-service`（8092，仅容器内网），Product 继续从共享 PostgreSQL 读取目录事实。无数据迁移或表结构变更。

## 发布前

1. 保存当前 Gateway 与 Consumer 镜像/JAR、网关路由配置及部署文件的可恢复副本；确认商品表与既有迁移列可用，数据库备份可恢复。
2. 在 JDK 21 下运行 `./mvnw -pl smart-assistant-data-intake,smart-assistant-consumer,smart-assistant-gateway -am clean test`，并确认 `docker compose --env-file .env config --quiet` 成功。
3. 确认 5432 不开放公网，`data-intake` 未配置 `ports`，Nacos/数据库凭证只从服务器环境文件读取。

## 顺序

1. 构建并启动 `data-intake`，等待 `/actuator/health/readiness` 健康、Nacos 中出现 `data-intake-service`；此时流量仍由旧 Consumer 接收。
2. 更新 Gateway 路由并重建 Gateway。此时 `POST /api/admin/products/extract-features`、`POST /api/admin/products`、`GET/PUT /api/admin/products/{code}/features` 均经新服务；旧 Consumer 暂不重建，保留短暂回滚窗口。
3. 用管理员测试账号做只读提取预览和现有商品参数读取；确认未授权请求返回 401/403，普通账号不能录入。写入验收仅使用明确标记的 QA 商品，核对商品、参数、审计同事务保存及 Product 读取，再按既定 QA 清理授权处理。
4. 上述通过后重建 Consumer，确认商品录入处理器已不在 Consumer；FAQ 管理和普通对话仍可用。观察 Gateway、Data Intake、Consumer、Product 日志及 5xx/延迟。

## 回滚

在新服务验收失败时，先将 Gateway 回滚到原路由/镜像，确认录入请求重新由仍在运行的旧 Consumer 承接；若 Consumer 已重建，也必须先恢复旧 Consumer 再回滚 Gateway。不要先停止 Data Intake 再让 Gateway 继续路由到它，否则会形成 503。已由新服务提交的数据库事务无需逆向迁移，回滚不会删除商品；QA 商品按授权清理。确认旧路径、管理员权限、FAQ 和普通会话恢复后，才停止新服务。

2026-09-14 的 [原商品录入验收](product-intake-deployment-verification.md)仅验证拆分前版本，不代表本次服务迁移的线上验收结果。

2026-09-28 的现网使用挂载 JAR 容器，已按“新服务先启动 → Gateway 再切流 → Consumer 版本对比后升级”完成。实测结果、回滚顺序与当前浏览器验收限制见 [本次生产验证](data-intake-production-verification-20260928.md)。现网已存在手动启动的 `smart-data-intake`，切勿直接再执行 Compose 的 `up data-intake`：同名容器会冲突。待统一迁回 Compose 时，应在维护窗口先核对并接管该容器。
