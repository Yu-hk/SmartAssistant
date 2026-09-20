# 真实检索评测与种子有效期修复（2026-09-20）

## 范围

本轮推进分析报告中的“真实模型效果评估”，先补一个可重复运行的**公开知识种子检索基线**。它使用实际 Java 检索代码、服务器正在运行的 embedding HTTP 服务，以及 17 篇源码种子文档，不使用模拟搜索返回值，也不挂载生产数据库或用户文档目录。

链路为 `InMemoryKnowledgeBase → ChineseTokenizer/BM25 → BgeReranker`。后者是标题/全文向量相似度加权重排，不是独立 Cross-Encoder 模型。服务健康接口自报模型名 `bge-large-zh-v1.5`，维度 512；本轮不据此认证模型权重身份。

这不是完整生产语料、PgVector/Milvus、用户上传文档、LLM 答案准确率、多租户跨节点压力或端到端稳定性认证。17 道问题与公开种子内容对应，不是独立用户问题留出集。重复三轮只度量本次观测的一致性，不等于 `pass^k` 概率估计。

## 真实评测发现的问题

旧 `scripts/eval_rag.py` 默认 `mock_search` 恒返回空列表；分类统计还会重新检索，同一题被执行两次。旧数据中的文档 ID 也不对应当前种子，不能据此宣称真实召回率。

现在评测进程仅将问题、样本 ID、知识库名发送给后端，**不发送答案标签**。返回数据必须通过语料目录、文档 ID、维度有效性、完整性和重复检查；向量服务失败、零向量、无效输出等均使本次评测无效，不将其包装为质量通过。分类统计复用已取得结果，不重复调用服务。

原数据原样保存为 `data/rag_eval_dataset.legacy.json`，不宣称旧样例通过。本轮 `data/rag_eval_dataset.json` 对应 `KnowledgeSeedData` 的 10 篇订单、7 篇商品文档，固定 Hit@5 门槛为 0.8。

真实旧产物在三轮共 51 次查询中 Hit@5 为 0。排查发现：`now - 30 * 86400000` 的乘法先按 32 位整数计算，2,592,000,000 毫秒溢出成负数，导致生效时间在未来约 19.7 天，17 篇种子全部被有效期过滤。

修复使用 `Duration.ofDays(30).toMillis()`，没有移除有效期或 ACL 检查，也没有改答案标签、放宽相似度阈值。新增 3 条种子有效期/可检索性回归，修复前 3 条均失败，修复后通过。

## 验证方法

- 本地 104 项跨模块回归通过（含 15 项种子、索引与 ACL 测试），零失败、零跳过。
- Python 评测契约 19 项、安全诊断契约 3 项通过；真实 JVM + 本地假向量 HTTP 协议契约 4 项通过。后者只认证协议，不能算真实模型效果。
- CI 增加 `Real retrieval probe contracts`，编译探针、执行种子回归和 HTTP 协议测试；真实线上度量与 CI 模拟向量测试分别记录。
- 服务器隔离 JVM 限制 1 GiB 容器内存、640 MiB 堆、1 CPU，根文件系统只读，仅挂载该次公开测试依赖，连接已有 embedding 服务。每轮结束按容器唯一标签核验后清除临时容器和 `/dev/shm` 依赖目录。
- 最初 512 MiB 容器的默认 JVM 堆不足，执行未形成有效质量结果；设置明确内存上限后复测。失败运行记录保留，不覆盖原报告。候选产物曾遇到向量请求异常，未将该次认证为通过。
- 候选评测的异常确认包含 `UnknownHostException`。后续仅在隔离评测中使用 Docker inspect 获得的私网 IP，并核验前后 embedding 容器 ID 与地址不变。没有修改生产 DNS；此测试不认证服务名解析可靠性。

## 实测结果

候选产物使用同一数据集三轮共 51 次检索，Hit@1/3/5、Recall@1/3/5、MRR@5 均为 1.0，17 题全部三轮命中。修复前对应指标均为 0。指标仅针对这套公开种子数据。

| 证据 | SHA-256 / 位置 |
| --- | --- |
| 原产物失败报告 | `/opt/smart-assistant/releases/real-rag-eval-20260920/report.json`；`ed2cc6a99f5b14fa39ab13a61fa23beb471da24312aec81c252a86b6e9bdb471` |
| 候选产物通过报告 | `/opt/smart-assistant/releases/real-rag-eval-fixed-20260920/report.json`；`e8a11ae7bc44406880867a3986d209c60fd11492755c712060ae80e04f6c782e` |
| 运行产物通过报告 | `/opt/smart-assistant/releases/real-rag-eval-production-20260920/report.json`；`4280f72bfb304fd070d0d13f217c70aa2591ce480bac68e6f2245f2e6704f7d9` |
| 修复后 common JAR | `b10bf361eb56233b77ac94f7c7342b5fbff6d09cbf59291782ea52608daae940` |
| 三轮数据集规范化指纹 | `10f26d47fa9f7037862425900f30a8d9c85e805475bea9222c73847065a46fa4` |

## 生产发布与业务冒烟

发布目录 `/opt/smart-assistant/releases/real-rag-eval-fixed-20260920`。入口暂停后确认派发、画像提交队列排空且无执行中会话，再替换 Consumer、Router、Product、Order 四个 JAR；保留上一版容器、独立恢复控制目录和来源 pin。四服务及 Gateway 健康检查通过后恢复入口，Gateway JAR、前端与生产网络配置未改动。

| 服务 | 实际部署 JAR SHA-256 |
| --- | --- |
| Consumer | `183c6d9acf9b2f7c9c9c3023ca01a8323b6171843fe220e24de79b19190f6304` |
| Router | `714ebdf8e5d17851ba9f7f17a030bb4d831bffb67f8195c116c94cd30b70c8e6` |
| Product | `049311a95b9872a91781bb86dee76e7c4daaea10ede879e596bf83bc51bc60c8` |
| Order | `62ffa71133a80219c171b28aa26fca1908717c69652e39a33f26b736148f6ddd` |

第一次业务冒烟中价格库存与规格追问通过；退货条件已返回实际知识库引用及条件，但测试将公开种子的“完好/二次销售”硬编码为必含措辞，而实际文档使用“保持完整”等表达，导致误判。保留首次 SSE 和失败日志，修正冒烟断言为期限、商品/附件完整性与引用的语义等价检查。这不是对法规准确性的认证，也没有修改 17 题召回评测标签或门槛。

最终三种公网问答均通过，包含工具用量、Token 和 done 事件；规格追问不混入颜色。匿名隐私接口拒绝访问、登录后可用。两轮测试会话均关闭，令牌撤销并移除测试凭据文件，没有执行真实下单、退款或画像删除。

发布后又从实际运行 Consumer JAR 提取依赖，三轮共 51 次检索均命中首位，`candidateOnly=false`，common 与 Consumer JAR 哈希同上。所有临时评测容器和 `/dev/shm/rag-eval-*` 目录均已移除；公网健康检查 UP，剩余磁盘约 16 GiB。评测证据中的 `productionServiceRestarted=false` 指评测进程本身不重启服务，不否认上述发布切换。

## 复现

```sh
mvn -B -f smart-assistant-common/pom.xml package dependency:build-classpath -DskipTests -Dmdep.outputFile=target/eval-classpath.txt
python scripts/prepare_rag_probe.py --classpath-file smart-assistant-common/target/eval-classpath.txt --classes smart-assistant-common/target/classes --output smart-assistant-common/target/rag-probe
# 在已配置的内部环境中设置 RAG_EVAL_EMBEDDING_URL，不放入 API Key。
python scripts/eval_rag.py --command '["java","-Xms64m","-Xmx640m","@smart-assistant-common/target/rag-probe/java.args"]' --repeats 3 --report new-report.json
```

服务器使用 `scripts/run_server_rag_evaluation.py`，需要将探针包、评测脚本、数据集放在新的 `/opt/smart-assistant/releases/real-rag-eval-*` 目录中，显式指定预期 common JAR SHA-256。默认从当前 Consumer JAR 中读取公共依赖；`--candidate-jar` 仅允许该目录中的 `consumer.jar`，明确标记候选测试而非已经上线。已有报告拒绝覆盖。

后续仍需独立留出题集、完整生产检索链路评测、答案事实/引用评审及多副本故障验证。隐私页面的真实浏览器视觉验收仍因浏览器连接两次超时而未完成，不能用 API 或组件测试代替。
