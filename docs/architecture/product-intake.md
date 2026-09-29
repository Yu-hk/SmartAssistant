# 商品录入与推荐读取架构

商品录入现由独立 `data-intake-service` 承载，推荐仍由 Product 服务读取共享 PostgreSQL。详细组件关系见 [在线独立录入服务架构图](https://yu-hk.github.io/SmartAssistant/architecture/data-intake-service.architecture.html)。

## 管理录入路径

管理员页面通过 HTTPS/Gateway 访问 Data Intake 服务。网关验证 JWT 和 ROLE_ADMIN、清理伪造身份头；Data Intake 的管理接口再次验证管理员角色和用户 ID。Gateway 保持 `/api/admin/products/**` 对外路径不变，新服务只在容器内网注册发现，不开放公网端口。

`POST /api/admin/products/extract-features` 只进行预览：根据简介、规格中的明确文本提取重量、续航及测试场景、主动降噪，附原文依据和告警，不写数据库。提取器属于 Data Intake 服务的确定性规则组件，不调用大模型；模糊、冲突或缺少场景的参数留空。

适用人群与用途不是可测量参数，不参与自动提取。管理员可在录入时显式填写标签、标注依据并确认；也可通过 `GET/PUT /api/admin/products/{code}/suitability` 按版本维护现有商品。未知商品不自动补标签，适用声明不等于性能保证。

商品别名只能由管理员显式维护，不能从简介或用户问题自动推断。录入时可一并填写，已有商品通过 `GET/PUT /api/admin/products/{code}/aliases` 按修订号完整替换。每件商品最多 10 个别名；冲突修订返回 409，维护操作记录管理员与时间。迁移 `20260929_add_product_aliases.sql` 增加别名表和审计字段。

用户核对或修正后调用 `POST /api/admin/products`。服务根据本次请求的原文重新提取，对已知参数要求显式确认；保存商品基础资料，再复用参数维护模块更新结构化字段与版本，整个过程在同一个 Spring 数据库事务内完成。异常回滚全部写入，重复编码返回 409，不覆盖已有商品。

PostgreSQL `products` 表同时保存简介/规格、结构化参数、来源、确认时间、版本和录入审计 JSON；`product_suitability_tags` 分别保存人群与用途标签，商品行记录标签来源、确认时间、修改人和修订号。审计包含原文哈希、规则版本、提取依据、原始提取结果与人工修改；明确记录 `externalVerification=false`。管理员确认不等于系统完成了厂商事实核验。

对应代码：

- [管理员录入页](../../frontend/src/admin/AdminProductsPage.tsx)
- [录入 API](../../smart-assistant-data-intake/src/main/java/com/example/smartassistant/intake/controller/AdminProductIntakeController.java)
- [录入事务](../../smart-assistant-data-intake/src/main/java/com/example/smartassistant/intake/service/admin/AdminProductIntakeService.java)
- [参数提取器](../../smart-assistant-data-intake/src/main/java/com/example/smartassistant/intake/service/admin/ProductFeatureExtractor.java)
- [参数维护模块](../../smart-assistant-data-intake/src/main/java/com/example/smartassistant/intake/service/admin/AdminProductFeatureService.java)
- [适用标签维护](../../smart-assistant-data-intake/src/main/java/com/example/smartassistant/intake/service/admin/AdminProductSuitabilityService.java)
- [商品别名维护](../../smart-assistant-data-intake/src/main/java/com/example/smartassistant/intake/service/admin/AdminProductAliasService.java)

## 推荐读取路径

用户对话仍经过 Consumer 的情绪/画像预处理、MQ 优先级调度和 Router 规划，再分配至 Product。录入不经过这条聊天任务链，也不依赖情绪分析或画像就绪。

Product 的 `JdbcProductBackend` 直接读取共享目录中的参数。硬条件筛选要求参数有明确依据；未给品类且特征过于模糊时澄清需求，热门榜说明站内销量来源。`StructuredProductRecommendation` 让模型选择候选编码和证据字段，服务校验真实目录并确定性生成金额、预算状态与推荐依据；默认结论不包含差额，只有用户明确要求时单独计算。

已确认的适用标签进入商品检索文本及推荐证据时，须标注为目录声明；不据此推断实测场景性能。

商品 BM25 索引按名称与别名、已确认的用途标签、规格与有来源的特征分别建立检索字段。规则能辨认的字段直接参与多标签匹配；字段归属不明时 Jev 仅提出高置信字段建议，超时或不可用则继续完整索引检索。未点名商品时，分字段排序只改变候选优先级，完整索引软兜底；明确点名商品时，只保留这些商品的候选，避免混入其他商品。价格、库存和数值条件仍以实时目录及确定性约束为准。明确商品的重量提问先走只读事实通道；只有带来源与核验时间的结构化重量才报数值，否则明确告知未核实。参见[交互式固定字段检索流程图](product-field-routing.workflow.html)。

没有匹配候选是正常业务结果；目录不可用不能冒充无匹配，也不回退到虚构商品。推荐阶段不临时抽取录入参数，既有缓存 TTL 不因本次新增录入接口而自动失效。

对应代码：

- [共享目录读取](../../smart-assistant-product/src/main/java/com/example/smartassistant/spi/JdbcProductBackend.java)
- [参数条件解析](../../smart-assistant-product/src/main/java/com/example/smartassistant/service/core/ProductFeatureRequest.java)
- [结构化推荐](../../smart-assistant-product/src/main/java/com/example/smartassistant/service/core/StructuredProductRecommendation.java)

## 约束与验收

历史 SQL 种子或直接数据库导入不会自动触发 Java 提取器；正式目录未自动回填未经核实的参数。后续批量业务录入应复用本服务。现有商品的独立参数维护 API 继续保留。

完整 API、迁移说明见 [录入特征提取](../product-intake-feature-extraction.md)。[2026-09-14 的 39 项线上验收](../product-intake-deployment-verification.md)针对拆分前 Consumer 内的录入实现，不能视为本次新服务已完成生产验收；本次迁移步骤见 [独立服务发布与回滚](../data-intake-service-rollout.md)。
