# 检索策略同金标对照

## 本轮目标与边界

将“低分后尝试其他检索方式/权重”的影子建议，推进为可复现的**开发集选择 → 冻结留出集验证**工具。先验证排序是否改善，不把 Ragas 分数或 RRF 分数直接用于真实会话重试。

本轮为公共知识种子的组件实验，不是完整 Product / Router 的线上回放：复用实际 `Bm25Scorer`、BGE 向量服务、`AdaptiveWeightHandler`、Product `RrfFusionHandler` 和 `RerankHandler`，但知识域由冻结问题指定，文档级候选直接进入 RRF。线上知识库的内部聚合、ACL 集成、查询改写、图谱、目录事实直答、生成答案均不在该探针中执行。不可把其文档级权重直接视为完整生产管线的最佳参数。

## 固定输入与策略

`data/retrieval_calibration_questions.json` 包含 20 个新合成问题：8 个开发题、12 个留出题，涉及商品知识与订单政策，含 3 个双事实题。参考文档 ID 在调用前人工冻结，不从候选答案或检索输出生成。开发/留出之间禁止重复问题、相同问题族和相同参考文档；金标、split 和参考答案不发送给检索器。实际输出绑定问题 SHA-256、知识域及种子正文摘要，防止同 ID 的问题漂移。

固定策略网格为：自适应稀疏权重、固定 0.2 / 0.5 / 0.8，各搭配语义单独重排与融合贡献 0.35，共 8 种。稠密权重为 `1 - sparse`，生产实现的 RRF 常数和归一化保持不变。每题固定 Top-3，BM25 保留正分，稠密候选保留余弦相似度至少 0.30 的文档；候选最多 20 条。每轮用新 JVM 重新检索，向量仅在该进程内缓存，三轮输入与语料必须一致；不是把一次结果重复计算三次指标。

`adaptive-semantic` 是本组件实验的基线，不冒充完整生产链路的端到端基线。开发集按 NDCG@3 选择且逐题 Recall@3 不退步；并列按预先固定顺序保留基线，不能看到留出集后改选另一个权重。报告展示其他策略的留出分数仅供分析，不用于重新选策略；后续改策略应冻结新的留出集，不能反复在这 12 题上调参。

## 指标与调查关卡

报告输出确定性 `ID Recall@K`、`ID Precision@K`、`ID NDCG@K`、`ID AP@K`。Precision 分母固定 K，缺少返回项也占空槽；AP 的分母为 `min(K, 全部金标数)`，不会把漏召回文档从分母中抹掉。重复执行不稳定、逐题召回/排序退步、留出样本不足或无明确增益都会记录原因。

这些 **不是 Ragas 四维评分**。本轮没有生成新回答或调用 LLM 评判器；没有新的忠实度/答案相关性评分。Ragas [Context Precision](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_precision/) 是参考答案相关片段的排序指标，不等于本工具的固定 K 命中比例；[Context Recall](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_recall/) 也不能由 RRF 分数替代。

“至少 20 个留出题、NDCG 增益至少 0.02”仅为未校准的调查条件，**不是统计显著性证明或上线门禁**。公共种子还不能代表生产分布；无论这些数字是否满足，工具都只生成报告，`parameters_applied=false`、`online_retries_executed=0`。不自动热更新、再生成答案或重试订单/余额/物流。

资料未知和数量合计保留此前独立诊断：未知字段不补造值；低忠实度的合计先核实确定性算术与政策范围。这次页面回归验证了实际回答行为，不代表 LLM 评判器已完成未知/算术校准。

## 运行与安全

本地契约和探针编译：

```sh
python -m unittest discover -s scripts -p test_retrieval_calibration.py -v
mvn -pl smart-assistant-product -am test -Dtest=RrfFusionHandlerTest,RerankHandlerTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl smart-assistant-common dependency:build-classpath -Dmdep.outputFile=target/calibration-classpath.txt
python scripts/prepare_retrieval_calibration.py --classpath-file smart-assistant-common/target/calibration-classpath.txt --common-classes smart-assistant-common/target/classes --product-classes smart-assistant-product/target/classes --output smart-assistant-common/target/retrieval-calibration
```

服务器准备新的受限 `/opt/smart-assistant/eval/retrieval-calibration-<日期>` 目录，仅放 `questions.json`、`calibration-probe.zip` 和三个 Python 工具（`retrieval_calibration.py`、`ragas_feedback.py`、`run_retrieval_calibration_server.py`），再执行：

```sh
python3 run_retrieval_calibration_server.py --root /opt/smart-assistant/eval/retrieval-calibration-20261001-2 --expected-product-sha256 <实际已部署Product摘要>
```

工具读取固定摘要的已部署 Product JAR，提取类和依赖到本次拥有的 `/dev/shm` 临时目录。三轮短生命周期容器固定完整镜像摘要、1 CPU / 1 GiB、只读根文件系统、cap-drop ALL、无新权限、PID 上限 128，仅挂只读类路径；不挂生产数据库、业务卷、Docker socket 或模型凭证。只请求已存在的内网向量服务，前后核对 Product/Embedding 容器和工件未变。不重启业务服务，不保存真实问题或账号密码。

报告和逐轮证据按 600 权限新建，拒绝覆盖；只清理核验标签/身份属于本轮的临时容器和准确临时目录。依赖不重新安装，业务服务不引入评测代码。CI 编译独立探针并跑契约，不调用生产或模型；模拟输出仅用于契约测试，不作为效果证据。

本轮结果见[验证记录](retrieval-calibration-verification-20261001.md)。经典交互图：[四维反馈](https://yu-hk.github.io/SmartAssistant/architecture/ragas-feedback.workflow.html)。
