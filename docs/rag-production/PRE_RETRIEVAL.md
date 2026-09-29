# 商品 RAG 预检索策略与 Multi-Query 评估

## 当前实现

商品咨询的 `ProductRagService` 在每轮检索前调用 `ProductKnowledgeScopeSelector`。选择器使用 `app.rag.pg.knowledge-base-name` 指定的商品库，并确认它已在 `KnowledgeRetrievalService` 中注册；不根据用户输入关键词扩张到订单库或其他库。若配置的商品库尚未注册，知识库路径不检索其他库，其他商品召回路径仍可工作。

`QueryRewriteHandler` 默认把问题改写成一条检索查询。`AdaptiveWeightHandler` 根据原问题长度、英文/数字比例和口语特征计算稀疏、稠密比例。`RrfFusionHandler` 对关键词和 BM25 路径使用 `2 × 稀疏比例`，对知识库路径使用 `2 × 稠密比例`；精确商品匹配及图谱路径维持固定权重 `1`。没有有效权重时回退到原先各路径等权的 RRF，质量分数按实际参与路径的权重归一化。查询改写目前只影响读取 `queryVariants` 的精确、关键词及 BM25 路径；知识库和图谱仍读取本轮原查询。

首次证据不足时，现有 Agentic RAG 最多补检一次，避免在每个问题上无条件扩大召回。选域发生在每轮检索前，补检仍沿用同一授权知识库范围。交互式流程图：[商品 RAG 检索准备与融合](../architecture/product-rag-pre-retrieval.workflow.html)（从 GitHub 文件页打开时需下载 HTML；部署后可从[在线图表总览](https://yu-hk.github.io/SmartAssistant/)打开）。

## Multi-Query 结论：暂不默认开启

目前 `MultiQueryHandler` 的开关默认是 `false`，且商品服务没有装配 `MultiQueryService` Bean；仅打开配置开关不会产生多查询。现有链路已有单查询 LLM 改写和证据不足时的补检，再无条件生成三个查询变体会增加一次模型调用，并让精确、关键词、BM25 三条路径对每个变体分别召回，带来延迟、Token 和重复候选成本。知识库与图谱路径目前不消费变体，因此收益范围更窄。对型号、SKU、订单相关的精确提问，改写过多还可能稀释实体约束。

建议维持关闭。若后续离线评测发现同义、简称或口语问题存在稳定漏召回，再以独立开关做受控试验：只对低置信且无明确商品编码的问题生成至多 2 个新增变体；保留原查询，限制总耗时；比较 Recall@K、答案事实错误率、P95 延迟、每问模型成本和补检触发率。只有召回改善且事实准确率不下降时，再考虑灰度上线。

本文件描述仓库实现和默认配置，不代表当前生产环境变量或线上效果已经验收。
