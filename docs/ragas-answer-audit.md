# 四维评估的独立回答核验

`scripts/ragas_answer_audit.py` 在 Ragas 原始评分旁增加独立、零模型调用的核验记录，帮助区分“可确定的字面事实错误”和“需要进一步调查的模型低分”。它不改写模型指标、不批准生产权重变化，也不执行在线重检索。

## 输入与核验

`data/ragas_answer_assertions.json` 在本轮新采集前冻结两个目录商品的价格、请求字段和数量。问题与独立参考字段 ID 必须完全匹配；金标来自既有人工参考和独立目录核对，不从候选回复抽取，也不传给业务服务。已经看过上一轮同题答案，所以不是盲评或独立留出实验。

1. 按完整商品名称绑定每条目录价格/未知重量声明，不能把两款商品的值交换后仍按关键词命中判通过。
2. 对未知重量仅接受明确“尚未核实”的固定模板；识别到具体克数则标记编造未知值。未知不等于缺证据，也不应凭低相关性造数。
3. 数量必须分别绑定对应商品；独立使用 `Decimal` 计算目录单价 × 数量之和。合计正确但数量错误同样不通过。不核验真实运费、优惠、支付或余额。
4. 实际字段证据的 ID、商品名称、数量和字面值分别核对。正确答案但缺少第二款证据仍不能通过；不依赖原 manifest 的完整覆盖声称。
5. 处理整个回复，而不是只找一个正确数字。重复/矛盾价格、额外商品或字段、重复或不应出现的合计不能通过。

## 三态输出与边界

| 状态 | 含义 |
| --- | --- |
| PASS | 当前冻结模板内的请求字面事实、证据和数量合计一致 |
| FAIL | 能确定的模板内字面错误、证据缺失/不匹配或商品字段遗漏 |
| NOT_ASSESSED | 存在无法解析的自由表述；不能据此推断该句错误或整体正确 |

遇到语法不支持的改写、未收录型号、否定、政策或额外说明不会忽略尾文后判 PASS。即使存在未解析文字，已经识别到的错误仍保留 FAIL。商品价格与重量证据的格式变化也须人工核对，不能把此工具当作通用语义评价器。模板中的“未包含未核实的运费或优惠”仅限定目录合计范围，不被当作已核实政策。

所有输出始终 `whole_answer_semantics_verified=false`、`execute_retry=false`。核验完整不等于满足全部用户约束、资料外部真实性、通用答案相关性或四项 Ragas 指标达到生产标准。对比商品、场景推荐、单位转换、否定条件及别名等尚不在本工具范围。

## 反例与原分数

`--include-controls` 追加 8 个明确标记的合成反例：错价格、错合计、编造重量、漏商品、交换价格、答其他商品、漏证据、矛盾价格。这些是对真实合成采集输入的离线变异，不是生产故障、真实用户回答或独立泛化样本。变异删除 live trace，仅保留原 manifest 摘要；绝不重写摘要后冒充现场覆盖。

`--score-report` 严格验证原数据集/案例摘要、轮次、数值、错误和汇总，然后原样复制逐轮分数、时延、错误、ID 指标及汇总至 `ragas_original`。不复制重新分类的行动为“原始行动”；反例没有新的 Ragas 评分，也不继承父样本的评分。报告拒绝覆盖，权限为 600，摘要用于完整性核对而非防伪签名。

```sh
python -m unittest discover -s scripts -p test_ragas_answer_audit.py -v
python scripts/ragas_answer_audit.py --dataset dataset.json --assertions data/ragas_answer_assertions.json --include-controls --output fresh-audit.json
# 仅用于与原报告完全绑定的历史数据集，不把旧分数套到新请求。
python scripts/ragas_answer_audit.py --dataset historical-dataset.json --assertions data/ragas_answer_assertions.json --score-report baseline-3rounds.json --include-controls --output historical-audit.json
```

服务器执行器 `run_ragas_answer_audit_server.py` 只接受新的 `/opt/smart-assistant/eval/ragas-answer-audit-*` 专用目录、固定 Python 镜像和实际 Product JAR 摘要。主机采集器只执行 `RESOLVE_READ_ONLY_PRODUCT`；核验容器网络关闭，不安装模型依赖、不传入模型密钥、数据库凭证、Docker socket 或生产卷。核验前后对照 Product/Router/Consumer 的 JAR 和镜像，拒绝覆盖旧报告。当前验收集合固定为八道问题及八个反例，不应当作任意数据集的通用部署器。

## 与后续重检索的关系

事实核验与模型四维评分并列记录，而不是把 PASS 换算为四个 1。错误金额/未知值首先调查生成或计算；证据缺失调查定向召回；未知句式或低相关性调查评判口径。这些只是调查方向，不是自动重试开关。

下一阶段仍需独立困难问题/负例、真实知识库完整链路、逐商品语义评判及同金标策略对照。证明替代策略稳定有益、ACL/选域/实体约束不退步并验证回退后，才接入有次数/超时上限的只读闭环。线上没有独立金标时不伪造 ContextRecall，写业务操作永不参与质量驱动重试。

本轮真实结果见[验证记录](ragas-answer-audit-verification-20261001.md)。流程图保留 [Archify 经典样式](https://yu-hk.github.io/SmartAssistant/architecture/ragas-feedback.workflow.html)，与四维评分、候选检索选择及冻结留出验证放在同一流程中。
