# 商品结构化参数持久化

日期：2026-09-14。状态：Consumer、Product 已部署到演示服务器，PostgreSQL 增量迁移及持久化接口验收完成。上线记录见 [部署验收](product-feature-persistence-deployment.md)。

## 存储与接口

沿用共享商品目录：Consumer 的管理员接口负责参数维护，Product 继续从同一个 `products` 表读取参数进行筛选和推荐，不增加独立服务，不将写库能力暴露为模型工具。

- `GET /api/admin/products/{code}/features`：查询参数、当前 `revision`、最后修改人和时间。
- `PUT /api/admin/products/{code}/features`：完整替换该商品的结构化参数；不会创建不存在的商品，也不修改价格和库存。

客户端使用管理员登录后的 Bearer Token 经 Gateway 访问；不要自行设置身份头。网关验证 JWT、拦截普通用户、清除伪造的 `X-User-*` 请求头，Consumer 再次检查确切的 `ROLE_ADMIN` 和有效用户 ID。与既有管理接口一样，Consumer 内部端口不得绕过网关对公网开放。

GET 返回的 `revision` 必须作为下一次 PUT 的 `expectedRevision`。以下只表示请求格式，参数为虚构测试值，不能作为真实商品资料导入：

```json
{
  "expectedRevision": 0,
  "features": {
    "weightGrams": 1200.125,
    "batteryLifeHours": 12.5,
    "batteryLifeScenario": "video_playback",
    "noiseCancelling": false,
    "source": "synthetic-test-source-not-a-real-product",
    "verifiedAt": "2026-09-14T01:00:00Z"
  }
}
```

所有约定字段必须出现，未知值写 `null`；遗漏字段或额外字段会被拒绝，避免误清空、拼写错误或越权字段赋值。全部清空时将六个特征字段都设为 `null`。`false` 表示明确不支持主动降噪，不能用来代替未知。

保存规则：

- 重量单位为克，正数且最多三位小数；续航单位为小时，正数且最多两位小数。
- 续航时长与场景同时提供或同时清空。支持 `video_playback`、`audio_anc_on`、`audio_anc_off`、`mixed_use`。
- 任何已知参数（包括明确不支持降噪）必须有来源和带时区的核验时间；核验时间不能晚于当前时间。
- 来源引用只保存，不自动访问外部 URL；服务不会宣称替管理员完成了事实核验。
- 单条条件 UPDATE 同时保存参数、递增版本、记录修改人和服务端修改时间。版本不符返回 409，不覆盖他人修改。
- 参数不合法返回 400，不存在的商品返回 404；数据库连接或迁移异常返回 503，不回退内存、不假报保存成功。若保存请求结果不确定，先 GET 确认版本再重试。

## 数据库迁移

执行 `docs/database/migrations/20260914_add_product_structured_features.sql`，补齐六个特征字段和：

- `features_revision BIGINT NOT NULL DEFAULT 0`
- `features_updated_by BIGINT`
- `features_updated_at TIMESTAMP WITH TIME ZONE`

该脚本可重复执行，不填充未经核实的真实商品参数。新数据库的 `docs/database/init-entities.sql` 也包含这些列。项目未启用这些业务表的自动 Flyway 迁移，本次不擅自添加启动时改表行为；部署前仍需显式执行脚本。

Product 现有 `JdbcProductBackend` 读取的字段名称与保存列一致，无须再复制回内存或文件。GET 保存结果立即读取数据库；推荐链路现有结果缓存仍可能在配置的 TTL 内展示旧结果（节点缓存默认一分钟），本次未改缓存失效策略，也未新增前端参数编辑页面。

后续已增加并部署“商品录入 → 简介/规格提取 → 核对 → 一起入库”入口；详见 [商品录入时提取结构化参数](product-intake-feature-extraction.md) 和 [部署验收记录](product-intake-deployment-verification.md)。既有商品的独立参数编辑页面仍未新增。

## 验证结果

部署前共 259 条相关回归通过：Consumer 33 条、Gateway 20 条、Product 163 条、Common 38 条、Python 遥测 5 条。线上复测发现“不计算差额”的否定表达遗漏后，增加 7 条回归并重新运行 Product 170 条及 Common 38 条，全部通过。

新增验证包括：

1. 对文件型 H2 数据库直接执行同一份迁移脚本，重复执行不覆盖原有数据。
2. 管理接口保存后关闭数据库，创建新数据库连接、新 Service 和新 HTTP 测试入口，仍能读取全部参数、版本及修改信息。
3. 显式清空后的 null 持续保留，不转成 false 或零。
4. 两个并发写入使用相同版本，只有一个提交成功；另一个返回 409。
5. 非法参数不写入；未知商品不被自动创建；普通用户及伪造管理员身份头不能写入。
6. 数据库错误不会返回成功，也不会向调用方泄露原始数据库错误细节。

HTTP 持久化测试使用真实 Service、文件数据库和 MockMvc，不仅是模拟 JDBC 调用。服务器上的 22 项接口及数据检查也已通过：真实 PostgreSQL 提交、新连接读取、Product 读取已存参数、并发版本保护、显式清空及网关普通用户越权拦截。

管理员写入功能在服务器内网模拟已认证网关身份头验收，没有冒充完成公网管理员账号登录。测试商品使用生产推荐过滤掉的 `E2E-PROD-` 前缀，标明虚构及禁止售卖，验证后已删除；原有 318 条目录记录未被改写。未填充未经核实的真实商品参数，未推送 Git。
