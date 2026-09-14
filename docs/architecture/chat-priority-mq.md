# 对话优先级接入 RabbitMQ

## 链路与边界

Consumer 情绪/画像并行预处理 → 服务端计算优先级 → Redis 绑定请求和账号执行权 → 发布并等待 Broker confirm → RabbitMQ 排队 → Consumer 的专用 Listener → Router 协调并执行业务 → Redis 结果快照 → 同步响应或 SSE。

两个入口使用同一 `PriorityRoutingDispatcher`，排队发生在真正调用 Router 之前，不再依赖旧的 Agent SSE 转发前本地 Semaphore 来实现优先级。Router 的规划、工具选择、取消与恢复职责保持不变。响应仍透传工具及 Token 元数据；MQ 状态等待不消费 Router 的最终决策 key，避免同步与 SSE 相互抢走结果。

## Broker 优先级契约

部署文件固定使用 RabbitMQ `4.1-management-alpine`。本实现使用该版本 Quorum 队列的公平份额优先级：0 为普通，5 为提高优先级。只采信服务端 `TurnInsight` 中 ANALYZED + ELEVATED 的结果；未知、超时均为普通。HTTP 请求中的 `priority` 数值不会传给 Broker。

RabbitMQ 4.1 的 Quorum 队列将 0–4 视为普通，5 及以上视为高优先级，在两类消息都有积压时倾向按 2:1 分配。此规则不是严格抢占或响应时限保证；已交给消费者的任务不会被后来的高优先级任务打断。[官方版本文档](https://www.rabbitmq.com/docs/4.1/quorum-queues#priorities)

**不要未经复测升级到 RabbitMQ 4.3 或改为 Classic 队列**：优先级与防饥饿语义不同。本实现不使用 Classic 的 `x-max-priority` 参数，也不将用户的长期画像身份当作优先级。

队列为 `smart.chat.dispatch.v1.quorum`，使用独立 Exchange/DLX。每个 Consumer 实例默认 4 个 Listener，`prefetch=1`、手工 ACK；部署多个 Consumer 时执行并发为各实例之和，并非跨集群固定 4 个槽位。发布是持久化消息、mandatory、correlated confirm，队列满时拒绝新投递，不挤掉旧请求。

## 超时、重试与顺序

- 每个账号在 Redis 中只允许一个排队/执行中的请求，防止同一会话后发的高优先级请求越过前一轮。已有 SSE ConversationGate 保留；同步接口也受新的执行权约束。新的一轮如果被拒绝，应等待前一轮结束再提交，不是将它在另一队列里乱序重试。
- 绑定请求 ID、用户、会话和原始问题；同一 ID 改变身份或问题会被拒绝。同一 ID 重投复用已保存的命令与原始截止时间，不延长排队时限，不重新计算优先级。
- Redis 在 Router 调用之前原子执行 `QUEUED → RUNNING`。只有首次拿到执行权的 Listener 能调用 Router，结果写入完成后才 ACK。重复的已完成/已取消消息只 ACK。
- 排队默认 30 秒超时，由 Broker TTL 和执行前截止时间检查双重约束。取消/超时只会原子终止仍处于 QUEUED 的请求，不能假装已执行任务已停止。等待方断开后的过期 QUEUED 记录可在状态查询或下一次账号入队时安全回收。
- 发送 confirm 超时不代表消息没发出：仅尝试原子取消仍处于 QUEUED 的请求，**绝不降级为直接 HTTP 执行**。消息若已 RUNNING，就等待原结果；不会另起一次业务执行。
- RUNNING 重投、未知结果、格式错误或基础设施异常进入 `smart.chat.dispatch.v1.dead`，不做自动业务重试。RUNNING/UNCERTAIN 的账号执行权保留待核查，避免不确定的订单写操作被重复执行。
- 保留结果及防重状态 24 小时，死信默认 7 天。这是有时间窗口的重复执行保护，**不是分布式 exactly-once 保证**。Redis 丢失/清空状态、保留期外重投以及底层业务恢复仍需业务自身幂等与核查。生产 Redis 应启用持久化并避免淘汰这些执行权记录。
- 成功回复仍由原请求链路记录历史并提交画像。HTTP/SSE 客户端退出后，MQ 可继续完成已开始的业务，但本次没有新增“离线结果自动入会话历史”的消费者；可先查询原请求结果。

排队期间画像分析可继续运行。Listener 调用去重后的可选预取，不再同步探测画像标记。Product 整轮默认最多额外等待 500 ms；画像超时/失败不进入业务失败、熔断或死信路径。账号执行权和取消标记仍是业务安全条件，不能因为画像可降级而跳过。参见 [可选画像设计](optional-user-profile.md)。

## 状态查询和人工核查

`GET /api/math/stream/chat/requests/{requestId}`：沿用登录鉴权，只返回所属用户的状态、回复及 Token 用量，不返回工具原始参数、提示词或 Router 内部诊断。请求不存在/过期返回 `NOT_FOUND`，越权返回 403。原取消接口也会取消 MQ 中尚未执行的请求，并继续通知 Router 取消已开始的执行。

对于 DLQ 中 RUNNING/UNCERTAIN 的请求：

1. 用 requestId 检查 Router 执行记录、恢复工作流及订单等实际业务状态；不要直接将 DLQ 全部重新入队。
2. 若原工作还在运行，等待或使用原取消流程；不要删除执行权。
3. 确认原工作已结束且业务状态一致后，由运维修复该请求的结果与执行权记录，再处理死信。当前没有“一键强制解锁/重试”管理入口，以免跳过业务核查。
4. 重启后仍要核查 RUNNING 状态；不能仅凭 Listener 已退出就认为订单操作未发生。请求超过防重保留期后也不应盲目重放。

## 部署参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `CHAT_DISPATCH_ENABLED` | true | 发布与执行使用 MQ；false 是显式回退开关，不是自动容灾 |
| `CHAT_DISPATCH_CONCURRENCY` | 4 | 每个 Consumer 实例的执行数量，1–32 |
| `CHAT_DISPATCH_QUEUE_WAIT_MS` | 30000 | 排队预算，1000–60000 ms |
| `CHAT_DISPATCH_RESULT_WAIT_MS` | 150000 | 总等待上限，需不小于排队预算，最大 300000 ms |
| `CHAT_DISPATCH_MAX_LENGTH` | 100 | Broker 中等待消息数量上限 |

Consumer MVC 默认异步超时调整为 180 秒。修改等待预算时同步核对 MVC、网关、Nginx 和客户端时限。队列声明参数不能随意热改；调整 TTL/max-length 若与已有声明冲突，应使用运维策略或迁移到新版本队列，禁止直接删除有积压的队列。

生产切换前检查 RabbitMQ 4.1、Redis、发布确认、队列绑定和 DLQ 均正常。回退前停止新请求并排空/核查 MQ 中的排队和在途任务，之后才能设置 `CHAT_DISPATCH_ENABLED=false`；否则直连路径会绕过队列中未完成任务的执行权。

## 验证

单元与入口回归覆盖：消息优先级/持久化、NACK/returned、手工 ACK、预取数、账号排队约束、取消、超时、防重、错误不重跑以及 SSE/同步工具与 Token 回传。

真实服务测试默认连接本机测试实例，必须显式设置环境变量；在服务器容器内验证时可明确指定内部中间件地址：

- `CHAT_DISPATCH_TEST_REDIS_PORT`：执行 `ChatDispatchRedisIntegrationTest`，验证 Lua 竞争、防重、越权和过期回收。可选 `CHAT_DISPATCH_TEST_REDIS_HOST`、`CHAT_DISPATCH_TEST_REDIS_PASSWORD`。
- `CHAT_DISPATCH_TEST_RABBIT_PORT`：执行 `ChatDispatchBrokerIntegrationTest`；可选地址/用户名/密码变量为 `CHAT_DISPATCH_TEST_RABBIT_HOST` / `CHAT_DISPATCH_TEST_RABBIT_USERNAME` / `CHAT_DISPATCH_TEST_RABBIT_PASSWORD`。测试挂起一条未 ACK 消息，先积压普通请求、再积压高优先级请求，释放消费后断言真实出队偏好与普通请求进展。

Broker 测试使用随机测试队列并只删除自己创建的队列，Redis 测试只删除精确记录的测试 key；不清空实例、不操作生产队列。未提供测试实例时会明确跳过，不能将单元测试通过视为线上消息调度已经验证。
