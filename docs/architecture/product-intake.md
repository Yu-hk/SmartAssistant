# 商品录入与推荐读取架构

代码版本：`773401019a0cbd81f5ec2ff6135d1062923bc295`。录入和推荐沿用 Consumer、Product 及共享 PostgreSQL，没有新增微服务。

## 管理录入路径

管理员页面通过 HTTPS/Gateway 访问 Consumer。网关验证 JWT 和 ROLE_ADMIN、清理伪造身份头；Consumer 的管理接口再验证管理员角色和用户 ID。

`POST /api/admin/products/extract-features` 只进行预览：根据简介、规格中的明确文本提取重量、续航及测试场景、主动降噪，附原文依据和告警，不写数据库。提取器是 Consumer 内的确定性规则组件，不调用大模型；模糊、冲突或缺少场景的参数留空。

用户核对或修正后调用 `POST /api/admin/products`。服务根据本次请求的原文重新提取，对已知参数要求显式确认；保存商品基础资料，再复用参数维护模块更新结构化字段与版本，整个过程在同一个 Spring 数据库事务内完成。异常回滚全部写入，重复编码返回 409，不覆盖已有商品。

PostgreSQL `products` 表同时保存简介/规格、结构化参数、来源、确认时间、版本和录入审计 JSON。审计包含原文哈希、规则版本、提取依据、原始提取结果与人工修改；明确记录 `externalVerification=false`。管理员确认不等于系统完成了厂商事实核验。

对应代码：

- [管理员录入页](../../frontend/src/admin/AdminProductsPage.tsx)
- [录入 API](../../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/controller/AdminProductIntakeController.java)
- [录入事务](../../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/admin/AdminProductIntakeService.java)
- [参数提取器](../../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/admin/ProductFeatureExtractor.java)
- [参数维护模块](../../smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/admin/AdminProductFeatureService.java)

## 推荐读取路径

用户对话仍经过 Consumer 的情绪/画像预处理、MQ 优先级调度和 Router 规划，再分配至 Product。录入不经过这条聊天任务链，也不依赖情绪分析或画像就绪。

Product 的 `JdbcProductBackend` 直接读取共享目录中的参数。硬条件筛选要求参数有明确依据；未给品类且特征过于模糊时澄清需求，热门榜说明站内销量来源。`StructuredProductRecommendation` 让模型选择候选编码和证据字段，服务校验真实目录并确定性生成金额、预算状态与推荐依据；默认结论不包含差额，只有用户明确要求时单独计算。

没有匹配候选是正常业务结果；目录不可用不能冒充无匹配，也不回退到虚构商品。推荐阶段不临时抽取录入参数，既有缓存 TTL 不因本次新增录入接口而自动失效。

对应代码：

- [共享目录读取](../../smart-assistant-product/src/main/java/com/example/smartassistant/spi/JdbcProductBackend.java)
- [参数条件解析](../../smart-assistant-product/src/main/java/com/example/smartassistant/service/core/ProductFeatureRequest.java)
- [结构化推荐](../../smart-assistant-product/src/main/java/com/example/smartassistant/service/core/StructuredProductRecommendation.java)

## 约束与验收

历史 SQL 种子或直接数据库导入不会自动触发 Java 提取器；正式目录未自动回填未经核实的参数。后续批量业务录入应复用本服务。现有商品的独立参数维护 API 继续保留。

完整 API、迁移说明见 [录入特征提取](../product-intake-feature-extraction.md)，生产验证见 [39 项线上验收](../product-intake-deployment-verification.md)。上线验证涵盖内网管理员 API、真实 PostgreSQL、Product 读取及公网普通账号权限与对话；不冒充已经完成管理员浏览器登录实操。
