# 原生知识库子链路校准与验证

## 结论（2026-10-01）

已完成 52 个合成问题、4 个冻结策略、3 个独立 JVM 的生产隔离验证，共 624 组排名输出。**保留基线，不改变线上权重，不启用评估驱动的重检索。** 简单按分号拆分再做多路 RRF，导致两道开发题丢失原本能找到的参考资料；逐子问题轨迹确认资料已召回，丢失发生在合并取 Top-5 时。不是向量接口失败、权限阻断或外层重排造成。

这是一轮原生 **memory-KB / Product 知识子链路**实验，不是完整生产配置或端到端 RAG 质量验收。PostgreSQL 向量 SQL、目录结构化事实、图谱、Router、生成答案与 LLM 四维评判器未在该探针执行；不能用本轮 Recall=1 推断生产检索已完美。

## 实际组件与粒度

`NativeRetrievalCalibrationProbe` 使用已部署 Product JAR 中的实现，串接：

1. `ProductKnowledgeScopeSelector` 实际选择注册的 `product_knowledge`，另注册 Order 域作为不可跨域的诱饵。
2. `InMemoryKnowledgeBase` 实际 BGE / BM25、0.30 稠密候选门限、内部混合权重及 ACL 检查。
3. `KnowledgeRetrievalService` 取 5 个文档后格式化聚合成 **1 个上下文片段**。
4. `ProductRagService` / `RagSearchPipeline` 执行自适应权重、知识召回、Product RRF、去重、外层语义重排；关闭 agentic 补查，仅一次服务检索。

叶子文档 ID、每路原生候选、最终上下文中的 `[CID:…]` 顺序、正文摘要、实际权重、知识域、查询摘要和调用次数均记录并校验。ID 指标以文档为单位，不能把“1 个聚合片段”当成“只召回 1 份资料”。本次仅有知识库一路、一个聚合片段，外层融合贡献 0.35 **没有改变内部文档顺序**；不能据此断言该参数在多路、多片段场景无用。

## 冻结输入、选择与指标

数据集 `data/native_retrieval_questions.json` 有 37 个虚构说明书文档：32 个公开 Product 文档、4 个受限 Product 文档（其他租户、角色、用户、密级）、1 个公开 Order 域诱饵。16 个产品问题族各有明确名称、改述、多事实 3 个问题；开发与留出各 8 个独立问题族、24 个正例，另有 4 个留出 ACL 守卫题。所有参考 ID 在首次采集前冻结，未经过业务专家独立审核；检索器不接收金标、split、family、kind 或参考答案。看到留出结果后没有调整数据、拆分规则或策略网格。

策略固定为 `hybrid-semantic`（基线）、`dense-semantic`、`hybrid-blend035`、`hybrid-facet-rrf`。最后一种仅在 2～3 个完整分号子句、每句至少 4 字时拆分，否则保留原问题；每路原生 Top-10，再按原生 RRF 常数 60 合并为 Top-5。**不是 LLM 改写或线上拆分实现。** 当前原生 multi-query 接口仅接收 tenant ACL；本探针强制公开 ACL，不将其用于登录用户，不能宣称已验证完整用户权限下的多路检索。

报告输出 `ID Recall@5`、固定分母 5 的 `ID Precision@5`、`ID NDCG@5`、完整参考集分母的 `ID AP@5`，不是 Ragas 的四维分数。本轮没有新的忠实度或答案相关性分数。每个问题族内部先平均三个题，再在问题族之间宏平均；改述不能增加独立样本量。开发集按 NDCG 选择、逐题 Recall 不得退步，并列保留预设顺序；留出集只验证，不重选策略。20 个独立留出族、0.02 增益仍只是未校准调查条件，不是统计显著性或上线许可。

## 三轮真实结果

三轮全部排名与追踪输出一致，512 维向量，每轮 237 次实际向量 HTTP 请求（仅 JVM 内缓存），共 711 次。基线、纯稠密、外层混合三种策略的结果相同：

| 策略 / 集合 | ID Recall@5 | ID Precision@5 | ID NDCG@5 | ID AP@5 |
| --- | ---: | ---: | ---: | ---: |
| 基线 / 开发 | 1.000000 | 0.266667 | 1.000000 | 1.000000 |
| 基线 / 留出 | 1.000000 | 0.266667 | 1.000000 | 1.000000 |
| 分号拆分 RRF / 开发 | 0.958333 | 0.250000 | 0.947950 | 0.918056 |
| 分号拆分 RRF / 留出 | 1.000000 | 0.266667 | 0.994884 | 0.989583 |

低固定 K 精度与高召回并不矛盾：每题金标仅 1～2 份，Top-5 中其余候选仍占分母。开发选择仍为基线，留出增益 0；只有 8 个独立留出族、合成小语料且基线达到天花板，不足以证明泛化或收益。

### 漏资料原因

| 开发题 | 被丢文档 | 对应子问题原始排名 | 最终合并 |
| --- | --- | --- | --- |
| `dev-camera-2`：防结露；新卡处理 | `DEV-CAMERA-A` | 第一路第 1，第二路未进入 Top-10 | 不在 Top-5 |
| `dev-watch-2`：表带护理；户外定位 | `DEV-WATCH-A` | 第一路第 1，第二路未进入 Top-10 | 不在 Top-5 |

两题的第二个子句都不再包含产品实体，容易召回跨产品通用资料；等权 RRF 又奖励重复出现：仅一路第 1 为 `1/61 ≈ 0.01639`，两路第 10 都出现仍有 `2/70 ≈ 0.02857`。因此单路重要事实被多路共同噪声压过。逐路轨迹与原生源码共同支持这一原因，不是根据最终分数猜测。

下一步候选应保留原问题、将子问题绑定已确认的产品实体/字段，并为每个事实保留证据席位，再冻结新留出集对照。不能只增加 Top-K 或调外层混合系数来掩盖覆盖缺口；也不能在本次已看过的留出集上循环调参。这些改进本轮**未实现、未上线**。

## 复现、安全与证据

本地先编译 Common / Product、生成依赖类路径，再运行共享编译器（现在同时编译两种探针）：

```sh
python -m unittest discover -s scripts -p test_native_retrieval_calibration.py -v
python scripts/prepare_retrieval_calibration.py --classpath-file smart-assistant-common/target/calibration-classpath.txt --common-classes smart-assistant-common/target/classes --product-classes smart-assistant-product/target/classes --output smart-assistant-common/target/native-calibration
```

服务器新建受限目录，放 `questions.json`、探针 ZIP、`retrieval_calibration.py`、`native_retrieval_calibration.py`、`ragas_feedback.py`、执行器；明确选择 native，默认仍兼容此前 component：

```sh
python3 run_retrieval_calibration_server.py --profile native --root /opt/smart-assistant/eval/retrieval-calibration-20261001-5 --expected-product-sha256 <实际已部署Product摘要>
```

本轮目录 `/opt/smart-assistant/eval/retrieval-calibration-20261001-5` 为 700，逐轮证据/报告为 600，拒绝覆盖。每轮独立 JVM 容器固定原生产镜像、1 CPU / 1 GiB、只读根目录、cap-drop ALL、无新权限、PID 上限 128，仅挂只读类路径；不挂数据库、业务卷、Docker socket 或凭证，仅调用现存内网向量服务。前后业务工件与容器身份未变，不重启服务；已删除本轮拥有标签的 3 个临时容器，报告保留。第一次无逐路轨迹的 `…-4` 证据也保留；第二次只补观测，不修改题目或策略。

- 数据集规范摘要：`e787b2a0870d00f75b6ed76ee579b79c5e5f72b8172d4db55240de992c01d28e`。
- 实际语料摘要：`9733b4596fdaa5a700332bcddbc2a94785bbf3fe69e51a45ee0e9eb1e9218cb7`。
- 探针 ZIP：`9728962731d010465d82bf4777cd94fcabcd545b4ffa2d93f6671e0869cd445b`。
- 三份实际 trial 规范摘要均为 `49e39346467dd4d28317033eda2363ee1a566d077e788c5de9a67ed3926e573a`。
- 已部署 Product JAR：`1d9a04ea419f44c92542f2003810408088c5ceb35d0bdd930ae6ec15b1ddf9e2`。
- 镜像：`75d5beb3650dfab9ad5e46e247f6df0456780c737027e0906fab8606fbaae5a9`。

最终叶子、上下文与每路原生排名均未出现受限或 Order 域文档；4 个守卫题 × 4 策略 × 3 轮的 48 组输出通过权限/域检查。它们只证明这个固定公开 ACL 实验，没有证明所有生产身份和数据库后端均无问题。

本地全量 Python 回归及 Java 权限/选域/融合/重排回归通过；CI 编译两种探针并执行契约，模拟数据不计为效果证据。Chrome / Edge 同一新 QA 账号完成 4 次只读提问、4 次刷新及跨浏览器会话恢复，无页面异常：两副 AirPods Pro 加一台 MacBook 合计 12997 元；未知耳机重量明确未核实，MacBook 目录价 8999 元。已检查截图布局与回复。未下单、未扣余额、未生成物流、未改目录；QA 账号和 4 条测试会话保留。该页面回归覆盖目录事实咨询，不冒充本合成知识库实验的页面入口。

回归数量：26 项新增原生契约测试通过；全量 Python 255 项中 254 项通过、1 项仅适用于 POSIX 的文件权限测试在 Windows 跳过。Java 定向回归 27 项通过、0 失败/错误/跳过。首次全量运行缺少既有 Java 探针环境变量，补齐配置后重新执行全套，没有把环境错误当作通过。

## 架构图交付回执

流程图保留 Archify 经典风格，只更新“原生召回与片段对照”及文档/片段核对说明；README 与[四维反馈说明](ragas-feedback.md)同步。未绘制虚假的在线自动闭环。

```text
diagram_type: workflow
output: D:/workspace/SmartAssistant/.worktrees/retrieval-coverage-20260930/docs/architecture/ragas-feedback.workflow.html
specification_sha256: d5f4cbceb0fc8c8b297f3759202d0f94dbe201d8ca2edd17aa6dcd0c21906544
artifact_sha256: a67daf1a6df6e21ee7b138b203f1792ef3d3f26d51949f77827abbab401fe3dd
validation: 9/9 showcase, 0 errors, 0 warnings
browser_evidence: passed
visual_review: passed
correction_rounds: 0
```

规范 2632 bytes，HTML 807180 bytes。自动证据覆盖四个桌面视口及亮/暗端点；已实际检查 2048×1320 亮/暗截图，图、标签和结论卡片可读且无溢出。冻结后未编辑 HTML。在线经典图：[四维评测流程](https://yu-hk.github.io/SmartAssistant/architecture/ragas-feedback.workflow.html)。
