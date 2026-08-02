# 客服快捷提问修复与并发回归报告

- 测试日期：2026-08-02（Asia/Shanghai）
- 测试环境：阿里云测试服务器 `123.56.6.102`，4 vCPU / 16 GiB
- 测试入口：登录后调用前端同款 `GET /api/math/stream/chat` SSE 接口
- 测试账号：并发基础数据集中的 `load_user_*` 测试账号

## 1. 问题与修复

快捷提问在缺少订单号或商品型号时会继续进入模型/Agent 链路，可能长时间等待或没有有效回复。Router 新增了可验证的确定性客服响应：

| 快捷提问 | 修复后行为 |
| --- | --- |
| 查询我的订单物流进度 | 按认证用户查询数据库中的最近 3 笔订单，返回候选列表并询问需要查看哪一笔 |
| 如何申请电子发票 | 返回申请步骤，并在需要核验时要求补充订单号 |
| 商品如何申请退货退款 | 返回通用售后步骤，声明具体资格以订单和商家政策为准 |
| 咨询商品规格和库存 | 要求补充商品名称、型号或商品编号 |

部署过程中同时发现 Router 重建后 Consumer 仍连接旧容器 IP。部署流程已改为按 `Router → Consumer → Nginx` 顺序刷新，避免地址漂移导致 SSE 返回“路由决策获取失败”。

后续体验优化进一步打通了 `OrderMapper.findRecentByUserId → OrderDataProvider → OrderRagService`，并在 Consumer 会话中保存候选订单。用户可直接回复“第1笔的物流进度”，系统会在同一认证用户和 sessionId 范围内解析对应订单；多候选时不会默认选中某一笔，避免歧义和串单。

## 2. 功能验证

四条快捷提问均返回 HTTP 200，包含完整 `waiting → init → routed → text → done` 事件序列，回复内容命中预期业务标记。

修复代码的单元测试共 9 项，9/9 通过。

近期订单优化补充执行 Order、Router、Consumer 三层单元测试共 20 项，20/20 通过；线上两账号回归验证近期订单集合互不重叠，且“第1笔”追问正确解析到当前用户的第一条候选订单。

## 3. 20 路并发回归

使用 20 个不同测试账号及各自 JWT，同时轮换执行四类快捷提问。

| 指标 | 结果 |
| --- | ---: |
| 请求数 | 20 |
| 成功数 | 20 |
| 成功率 | 100% |
| HTTP 状态 | 20 × 200 |
| 整批耗时 | 3.074 s |
| 本轮吞吐 | 6.51 requests/s |
| 平均延迟 | 2,069.32 ms |
| P50 | 1,693.90 ms |
| P95 | 3,010.78 ms |
| 最大延迟 | 3,064.89 ms |

本轮包含近期订单数据库查询，是一次性并发回归，不等同于持续时间窗口内的容量 QPS 测试。

## 4. 容量配置与运行状态

并发检查发现 Router 原 cgroup 内存上限仅约 805 MiB，测试后占用约 711 MiB。服务器部署脚本已同步项目中的容量修复，将 Router 限额调整为 1,280 MiB，并重建 Router、Consumer 和 Nginx。

最终检查：

- Router 内存限额：1,280 MiB
- Router：`running`，`RestartCount=0`，`OOMKilled=false`
- Consumer：`running`，`RestartCount=0`，`OOMKilled=false`
- 真实订单 Agent 链路：HTTP 200，264 ms
- 商品 Agent 链路：HTTP 200，205 ms

Consumer 启动日志仍会记录可选 Tool Registry/MCP 初始化错误和未启用 `pg_stat_statements` 的监控告警；本次真实订单、商品和快捷客服链路均未受影响，后续可作为非阻断运维项单独清理。

## 5. 测试产物

- 并发回归脚本：`test-data/quick_prompt_concurrency_test.py`
- 近期订单会话回归：`test-data/recent_order_conversation_test.py`
- 近期订单会话结果：`test-data/recent-order-conversation-results-2026-08-02.json`
- 数据库查询版并发结果：`test-data/performance/quick-prompt-concurrency-recent-orders-2026-08-02.json`
- 内存修复后结果：`test-data/performance/quick-prompt-concurrency-after-memory-fix-2026-08-02.json`
- 修复前容量对照：`test-data/performance/quick-prompt-concurrency-2026-08-02.json`
