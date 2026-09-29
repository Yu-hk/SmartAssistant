# 商品特征与适用标签：2026-09-29 生产验证

范围：重量、分场景续航、主动降噪继续按可核对参数处理；管理员显式维护适用人群、用途及来源。历史商品不自动推断标签。

## 发布与回滚材料

- 发布前生产数据库完整自定义格式备份：`/opt/smart-assistant/releases/product-suitability-20260929/before.dump`，`pg_restore -l` 可读取，SHA-256 `d5432c087bdbfed41ba7b3d823558b1c6a0ceca2d4f7360cd914ffc4917f650f`。
- 增量迁移 `20260929_add_product_suitability.sql` 校验哈希后成功执行；迁移后标签表为 0 行，未给历史商品回填。
- Data Intake JAR SHA-256 `58f8551f3e7855d34bc1466faa3a5636227b5b91ddacd2241dc907397a3ffc29`；最终 Product JAR SHA-256 `2315ba8e8b164837aa3af60a405637192a9b8ffeb434cddc05522aa743c1d7e4`。本地构建、上传文件和容器 `/app/app.jar` 一致。
- 先备份原挂载 JAR，再停止 Gateway、等待运行中的对话排空，切换 Data Intake 和 Product 并检查就绪，最后恢复 Gateway。回滚脚本和原 JAR 留在同一受限发布目录；旧版本 JAR 不因发布被删除。
- 管理员前端上传包 SHA-256 `4c6f30dec5fc2fd5bbef460f8ea9ad25b4c32dbc17e3cd374769f10d5f127f98`。原静态目录备份为发布目录下的 `frontend-before`；生产 `index.html` 和新商品录入 JS 资源的实际 HTTP 响应哈希与磁盘文件一致。

## 验证结果

- 生产内网的 Data Intake 对缺少管理员身份的请求返回 403；公网 Gateway 对无令牌、仅伪造管理员身份头的请求返回 401。规则预览能提取合成样例的 200g 重量。
- 唯一编码 `QA-SUITABILITY-20260929`、售价 1 元、库存“缺货”的虚构商品录入成功；人群、用途、标注来源、管理员操作编号及修订号可读。Product 能同时读到结构化参数与“目录标注（非性能保证）”。
- 适用标签修订号从 1 更新到 2；旧修订号再次提交返回 409。
- 验收脚本在 `finally` 中按编码、名称、价格与库存四项限定删除 QA 商品；清理后该商品的适用标签为 0 行。无订单或余额变动。
- 发布后 Data Intake、Product 的 readiness 和公网 `/healthz` 均为 `UP`。验证窗口中这两个服务及 Gateway 的 `ERROR`/`Exception` 日志匹配行数均为 0。
- 首次验证后，Product 文案又做了一次小修，避免部分商品已有声明时误提示“没有适用标签”；按 Product 单服务回滚流程更新 JAR，并重跑同一套生产 QA 脚本，全部通过。Data Intake 与前端版本未变。

## 边界

本轮生产写入验收通过内网服务接口执行，没有可复用的管理员浏览器登录态；因此未将“管理员页面点击保存”记为已完成的浏览器端到端验收。前端已通过本地测试与生产构建，并核对新版静态资源在公网可访问。适用标签是管理员确认的目录声明，不能作为实测性能或唯一推荐依据。现有目录商品仍未补录真实标签。
