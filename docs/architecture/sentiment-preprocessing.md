# Consumer 会话情绪预处理

## 范围

情绪分析作为 Consumer 内部模块，统一接入同步 Chat 和 SSE Chat；不新增微服务，不改变 Router 的任务协调职责。业务问句保持原样，情绪不是订单、商品等业务参数，也不写成长期用户标签。

本阶段提供规则优先的五级情绪识别、本轮处理建议、有限会话趋势及降级保护。已有本地 BGE 与 `Function<String, Integer>` 分析适配器仍是可选依赖：只有实际配置对应 Bean 才启用，远程 EmbeddingModel 并不自动成为本地 BGE 分析器。当前分数是启发式信号，不是校准后的情绪概率；不声称覆盖反讽、隐含情绪等全部表达。

## 并发和依赖

1. 在 Consumer 完成原有身份与会话检查后，统一预处理按用户、会话、本轮 ID 和输入指纹读取情绪快照。
2. 未命中时将无状态情绪推理提交到独立的 `sentimentExecutor`。
3. 同时调用画像预取：独立有界 `profilePreparationExecutor` 执行 Redis 标记、已有快照读取及分析，调用线程不访问画像存储。
4. 预处理只在有限预算内等待情绪结果，不等待画像 Future；随后 Router 开始规划和执行业务。
5. Product 节点通过 `UserProfileContextAwaiter` 选择可选画像，整轮默认最多额外等待 500 ms（含存储 I/O 与读线程排队）。超时无画像继续。成功轮次在独立 `profileCommitExecutor` 发布有效候选，不依赖 AOP 启用。

两类分析可以同时进行。画像标记不再是路由准入条件，画像失败不影响情绪分析或商品任务；参见 [可选画像设计](optional-user-profile.md)。

## 降级与去重

- 情绪推理默认等待预算 750 ms；超时、推理失败和执行器饱和返回 `UNKNOWN`，不阻断业务，不伪造“中性”。这是推理等待预算，不是整个预处理（含 Redis 网络调用）的硬时延上限。
- 独立执行器默认 2 个工作线程、32 个等待位置；超时会取消未完成 Future。依赖如果不响应中断，单次底层调用仍可能继续，但工作线程数量有界；工作线程不写跨轮状态。
- Redis 原子脚本检查请求所有权，保存本轮快照，并仅保留最近 5 轮等级。最近 3 轮均为负面时给出升级建议；未知轮次会打断连续负面趋势。
- Redis key 使用用户/会话隔离与输入摘要，不保存问句原文。同一请求和相同输入重复执行复用情绪结果，不重复计入趋势；相同请求 ID 携带不同输入不复用快照，调用端仍须遵守原有请求 ID 合约。
- 情绪快照默认保留 1 小时。画像准备使用独立的 2 分钟请求去重，重试可再次调用预取入口，但不会在去重期内重复分析或重置标记；缺少画像不阻断路由。
- 情绪 Redis 状态不可用时只返回本轮观察，`stateRecorded=false`，不声称已记录跨轮趋势。
- 引用文本、代码块及 `【资料】…【问题】` 中的资料不作为用户本人情绪分类输入；业务与画像仍收到原始问句。

## 输出与处理

同步响应返回 `sentiment`，SSE 在路由之前发出 `preprocessing` 和 `sentiment` 事件。普通用户界面只显示准备上下文的进度，不展示“愤怒”等内部分类标签。

`TurnInsight` 包含 `status`、`level`、`confidence`、`escalated`、`handoffRequested`、`responseStrategy`、`suggestedPriority`、`reason`、`latencyMs` 和 `stateRecorded`。

- 负面情绪、人工协助诉求、趋势升级或未知状态绕过 Consumer 答案缓存的读取和写入，避免旧答复跳过本轮处理。
- 同步结果及 SSE Router 最终结果可加简短共情前缀，保留原业务结论，不把情绪前缀拼入原问题。旧式 Agent 流转发路径仍只提供情绪事件，不改写 token 流。
- 愤怒、投诉或赔偿咨询不会直接结束业务，更不会声称已转人工。`handoffRequested` 只表示明确诉求，不等于转接成功。
- `suggestedPriority=ELEVATED` 经 Consumer 服务端映射为 RabbitMQ 高优先级（5），其他情况为普通优先级（0）；同步与 SSE 的实际 Router 执行已接入同一 MQ 队列。参见 [MQ 调度说明](chat-priority-mq.md)。客户端不能直接设置该优先级；业务紧急程度等额外信号尚未接入，不应将情绪分级视为完整的业务紧急程度判断。
- 本次快照是短期 Redis 状态，没有新增长期审计表或管理后台情绪图表，也没有新增模型 Token 用量采集。

## 配置

配置项均提供默认值，可通过 Spring 外部配置覆盖：

| 配置项 | 默认值 | 约束 |
| --- | --- | --- |
| `consumer.sentiment.timeout-ms` | 750 | 20–3000 ms |
| `consumer.sentiment.workers` | 2 | 至少 1 |
| `consumer.sentiment.queue-capacity` | 32 | 至少 1 |
| `consumer.sentiment.state-ttl-seconds` | 3600 | 至少 60 秒 |

## 验证

新增测试覆盖并行启动、画像未完成时返回、超时取消、推理异常、队列饱和、Redis 降级、重复请求、引用资料隔离、同步/SSE 路由接入及缓存绕过。Redis 快照单元测试验证 key 隔离、过期设置及脚本调用参数；真实 Redis 的 Lua 原子性和多实例并发效果仍需部署环境集成验证。
