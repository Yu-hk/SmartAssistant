# 四维评测与检索反馈

本轮将此前仅在临时目录中的 Ragas 验证推进为仓库内可复现、可测试的工具。业务模型质检、RRF 分数、实体契约通过率与 Ragas 四维分数是不同指标，不得互相替代。

## 实际流程

1. `data/ragas_product_questions.json` 冻结四个合成只读问题、人工编写的参考答案与相关字段 ID。参考答案不发送给商品服务，不从候选答案自动生成。
2. `collect_ragas_product.py` 在服务器核对商品 JAR 摘要与两款目录价格，执行固定 `RESOLVE_READ_ONLY_PRODUCT` 操作，采集实际回复及按序字段证据，前后检查工件未变。资料缺失用独立 `:UNVERIFIED` ID 和 `known=false` 元数据表示，不能当作具体重量已被检索到。
3. `ragas_feedback.py` 针对相同采集输入重复评判四项指标，保留逐轮分数、错误类型、耗时、中位数、极差和总体标准差。上下文精度采用“单片段支撑参考答案任一事实即相关”的固定口径，模型输入视为数据而非指令。
4. `make_ragas_controls.py` 离线构造价格错误、答非所问、缺一款证据三个反例，明确标为 `negative_control`，不冒充线上失败。其参考答案保持不变。
5. 评分不完整归 `EVALUATION_UNAVAILABLE`，不足三轮归 `MORE_REPEATS_REQUIRED`；极差超过 0.2 要求校准。稳定低召回/精度对应检索覆盖/排序复核，低忠实度优先检查生成是否超出证据，低相关性检查回答范围。

这些阈值仅为调查建议，**没有经过生产分布校准，不是发布门禁**。所有反馈均带 `execute_retry=false`；没有自动重检索、参数热更新、答案再生成、订单重试或管理员新页面。基础调用四题各一次；重复的是评判模型，不是业务请求。

## 四项指标与限制

| 指标 | 本轮输入 | 不能据此声称 |
| --- | --- | --- |
| 上下文召回率 | 问题、冻结参考答案、字段证据 | 无金标情况下的线上真实召回率 |
| 上下文精度 | 问题、参考答案、有序证据 | BM25/稠密/融合召回排序已通过 |
| 忠实度 | 实际回复、字段证据 | 商品资料本身已被外部权威核实 |
| 答案相关性 | 问题、实际回复、项目 BGE 向量 | 价格正确或证据充分 |

定义依据 [Ragas 指标总览](https://docs.ragas.io/en/latest/concepts/metrics/available_metrics/)及[上下文精度说明](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_precision/)。额外输出 ID Recall/Precision/AP，但它们不是 LLM 四维分数；其中 AP 分母是已检索到的相关 ID 数，衡量当前列表内排序，不是以完整金标数作分母的全库 AP，漏召回必须另看 ID Recall。[答案相关性](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/answer_relevance/)使用余弦相似度，理论可为负数，适配器保留 -1～1 的真实数值，不把负值当作评分错误；其余三项为 0～1。

当前样本来自已上线目录事实链的结构化字段，不是原始入模型的完整 Prompt，也不是知识库候选排名。只验证四个合成场景，没有读取真实会话；不能解读为真实用户质量、NER 泛化或完整 RAG 检索质量。`DomainQualityResult.PASS` 仍不能当作 Ragas 分数。

## 运行

```sh
python -m unittest discover -s scripts -p test_ragas_feedback.py -v
# 在独立环境安装，业务服务不引入 Python 依赖。
python -m pip install -r scripts/requirements-ragas.txt
# dataset.json 必须由合成只读采集或受控样例生成；不要上传真实用户文本。
python scripts/ragas_feedback.py --dataset dataset.json --output fresh-report.json --repeats 3 --dry-run
# 通过环境配置 DEEPSEEK_API_KEY / RAGAS_EMBEDDING_URL，不写入仓库或终端输出。
python scripts/ragas_feedback.py --dataset dataset.json --output fresh-report.json --repeats 3
```

适配器仅接受 Ragas 0.4.3。四维运行须依赖模型与向量服务；CI 的 40 项契约测试使用注入评分器，不安装 ML 依赖、不调用模型，不能算模型质量通过。上下文 ID 与文本必须一一对应，评分异常、NaN/无穷或缺失不以 0 或默认高分掩盖；报告拒绝覆盖并以受限权限保存，不写 API 响应正文或密钥。向量非空、非零、数值有限且维度一致；报告记录实际依赖版本供复现。

评判配置显式使用 `thinking.type=disabled`、最多 4096 输出 Token；不继承当前 DeepSeek 的默认思考模式和 Ragas 的 1024 Token 默认限额。模式依据 [DeepSeek 官方说明](https://api-docs.deepseek.com/guides/thinking_mode/)，本轮还实测复现了默认配置下的截断。可用 `--judge-thinking enabled --judge-max-tokens 1024 --case-id different-fields --repeats 1` 聚焦重现旧配置；新旧报告分别保存，不能覆盖失败证据。聚焦选择保留父数据集摘要，不改问题、答案或参考标签。报告保留结束原因计数、推理 Token 计数与重试异常类型，不复制异常消息或模型思考正文。

服务器执行器 `run_ragas_feedback_server.py` 仅允许新的 `/opt/smart-assistant/eval/ragas-feedback-*` 目录、固定镜像 ID、最多十轮。短生命周期容器限 1 CPU/2 GiB、只读根文件系统，脚本/依赖只读挂载，仅报告目录可写；只读取现有配置中的 DeepSeek 凭证，不挂 Docker socket/生产卷，不重启业务服务。报告分别记录评判模型调用次数、输入/输出 Token 与向量调用次数，避免把四维评分次数误当作实际模型调用次数。生成的数据集和报告是运行证据，不进入版本控制。

本轮验证与观测结果见 [四维评测验证记录](ragas-feedback-verification-20260930.md)。

## 后续闭环

下一阶段应先采集脱敏、有权限与来源记录的真正检索轨迹，冻结独立留出集；在同一金标下比较原权重与候选权重/字段过滤。确认有稳定增益，再接在线只读、有超时/次数上限的重检索，保持 ACL、选域、事实检查、不可写业务边界不变。没有参考答案时不能伪造 ContextRecall；线上代理指标必须另名，不能与 Ragas 混报。

交互图保持 Archify 经典风格并放入图表总览：[四维评测流程](architecture/ragas-feedback.workflow.html)。当前规范 SHA-256 `15c5df0c1610897ebb3e83c06b7a3c0d41c36de3872dbf34fc23f65d2b98b3a1`（2379 bytes），HTML SHA-256 `e5d78f3a3b6c558655687264795d38ab575dcb3970b1bec6552bbde328e9f0e0`（805316 bytes）；showcase 9/9，0 错误/警告。四个桌面视口及亮/暗端点自动浏览器证据通过，已实际检查 2048×1320 亮/暗截图，`visual_review=passed`；修正一轮宽度后冻结。
