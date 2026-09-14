# 商品录入时提取结构化参数

状态：2026-09-14 已部署并完成 39 项线上检查，尚未推送 Git；范围与限制见 [部署验收记录](product-intake-deployment-verification.md)。

## 流程与边界

管理员进入 `/admin/products`：填写商品基本资料、简介和规格 → 提取预览 → 核对/修正 → 确认录入。

简介、规格、结构化字段和提取审计记录在 Consumer 的同一事务中保存。Product 沿用共享 `products` 表读取参数；用户发起推荐时不再临时执行这次录入提取，也不等待外部模型。原有单独的参数维护 API 保留，用于后续纠正，不是正常录入的必经步骤。

首版采用有边界的确定性提取规则，而非让大模型猜测规格：

- `整机净重1.2kg` → `weightGrams=1200`；只接受设备净重口径，不把包装、配件或单耳重量混入。
- `视频播放续航12小时` → `batteryLifeHours=12, batteryLifeScenario=video_playback`。听歌需明确降噪开启/关闭，综合使用单列；不同场景或多个时长不能自动选一个。
- `支持主动降噪` / `不支持主动降噪` → true / false；没有说明则 null，不把通话降噪当作主动降噪。
- “轻便、续航长”、电池容量、疑问/假设、包装重量、充电盒总续航、近似值和冲突不当成精确参数，需人工核对。简介和规格各限制 10000 字。

页面展示原文依据及待核对提示。修改原文使已有预览失效；修改参数会取消已勾选的确认。预览不写数据库，未确认的已知参数不能提交；没有可提取事实的商品允许所有参数为 null。

## API

管理员经网关携带合法 JWT 调用；网关去除伪造身份头，Consumer 再检查确切 ROLE_ADMIN 与用户 ID。

`POST /api/admin/products/extract-features`：

```json
{"description":"整机净重1.2kg，视频播放续航12小时。","spec":"不支持主动降噪。"}
```

返回 `version`、四个 `features` 字段、每字段原文 `evidence` 和 `warnings`。这只是提取预览，不代表外部事实已被核验。

`POST /api/admin/products`（以下为虚构格式示例，不可当成真实商品导入）：

```json
{
  "productCode":"EXAMPLE-ONLY-001",
  "productName":"虚构格式示例",
  "category":"笔记本电脑",
  "price":3999,
  "stock":"缺货",
  "description":"整机净重1.2kg，视频播放续航12小时。",
  "spec":"不支持主动降噪。",
  "color":"白色",
  "featuresConfirmed":true
}
```

创建接口本身也会从本次请求的简介/规格重新提取，不依赖调用方先调预览。调用方如需修正，可另传完整的四字段 `features` 对象；未知字段必须显式为 null。服务端对人工值再次执行单位、范围、精度、场景配对验证，并记录与提取值的差异。

商品编码不区分大小写，冲突返回 409，**不会 upsert 覆盖**。数据不合法返回 400；存储异常返回 503，整个事务回滚，不留下“只有商品没有参数”的半成品。保存结果不确定时应先按编码调用现有 `GET /api/admin/products/{code}/features` 核对，不能假定失败后无限重复新建。

## 数据与审计

显式执行迁移（新库的初始化 SQL 已包含这些列）：

1. `docs/database/migrations/20260914_add_product_structured_features.sql`
2. `docs/database/migrations/20260914_add_product_intake.sql`

新列：`description` 保存简介，`feature_ingestion_audit` 保存创建时的 JSON 审计快照，包括规则版本、原文摘要、提取依据、告警、提取值、确认值、人工覆盖字段、确认标志、操作人与时间。

`features_verified_at` 在管理员确认时写入服务器时间；来源明确标记为“商品录入简介/规格（管理员确认）”，审计明确记录 `externalVerification=false`，不冒充厂家认证或模型完成的外部核实。

审计是**创建时快照**，后续通过参数维护 API 修改字段不会重写这份原始快照；当前字段版本与最后修改人以原有 `features_revision/features_updated_by/features_updated_at` 为准。

历史 SQL 种子/直接数据库导入不会自动触发 Java 录入逻辑；后续批量业务导入应复用此服务。没有擅自回填现有 318 条线上商品，也没有创建真实订单或物理库存记录。推荐侧已有缓存 TTL 仍适用，本次未扩展目录变更的跨服务缓存失效策略。

## 本地验证

- Consumer 63 条：提取 22 条、录入/事务 8 条及既有持久化/权限回归。
- Gateway 21 条：包含普通用户伪造管理员头请求创建/提取的拦截。
- 前端 4 条：null/false 区分、非法数字、先预览不自动发布、独立管理员入口。
- TypeScript 与 Vite 生产构建通过。
- 本地生产构建浏览器验证通过：1440px 桌面和 390px 手机布局无横向溢出；预览不创建商品、false 与未知区分、修改原文使预览失效、修改参数取消确认。桌面还覆盖了保存失败提示、重新提交成功与成功后防重复提交。浏览器接口使用本地模拟数据，不代表生产环境联调。

持久化测试直接执行同一份迁移，在文件 H2 上调用真实 Service/HTTP，并在关闭数据库后重新读取；事务失败回滚也经过实际 Spring 事务代理验证。该测试不等于线上 PostgreSQL 联调，部署后仍需验证真实网关与数据源。
