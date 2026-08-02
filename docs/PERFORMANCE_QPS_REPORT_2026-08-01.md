# SmartAssistant QPS 压测报告

- 测试日期：2026-08-01
- 测试环境：本地 Windows + Docker Desktop，16 逻辑处理器，Docker 可用内存约 15.34 GiB
- 部署形态：Gateway、Router、Order、User、PostgreSQL 均为单实例，容器未设置独立 CPU/内存上限
- 压测模型：固定并发、闭环请求；每档预热 2–3 秒，正式采样 12–15 秒
- 数据规模：1,000 用户、5,000 会话、120 商品、20,000 订单及配套物流/退款/审批数据

## 一、结论

在当前单机、单副本配置下，以“认证用户通过 Gateway 查询订单，经过 Router、Order Service 和 PostgreSQL 返回订单及物流”为代表性确定性业务链路：

- **最大零错误实测 QPS：195.58**，并发 20，P95 231.21 ms，P99 400.74 ms。
- 热态多轮成功吞吐约 **195–208 QPS**；其中 208.12 QPS 的轮次伴随 15.13% 的 HTTP 429，不应作为无损容量。
- **建议持续容量：130–140 QPS/实例**，约为最大零错误实测值的 65%–72%，保留流量波动、GC、下游抖动和混合请求余量。
- 当前最佳工作点约为 **20 个在途请求**。并发继续升至 30–100 时，吞吐没有线性提高，P95 上升到约 1–2.8 秒。

因此，如果只需要一个当前项目 QPS 数字，应表述为：

> **确定性订单查询全链路实测约 196 QPS；建议按 140 QPS/单实例规划持续流量。**

该结论不代表调用外部大模型、长时间 SSE 或复杂工具链的 QPS；这类链路受模型延迟和 Consumer 全局 5 并发限制影响，需要单独测试。

## 二、测试链路

| 场景 | 请求入口 | 覆盖范围 | 目的 |
|---|---|---|---|
| Order Direct | `POST :8085/api/order/agent/process` | Order + PostgreSQL | 业务服务基线 |
| Router Direct | `POST :8083/api/router/route` | Router + Order + PostgreSQL | Agent 路由开销 |
| Gateway Full Chain | `POST :8081/assistant/api/router/route` | JWT、限流、Gateway、Router、Order、PostgreSQL | 用户实际访问链路 |

每个请求轮换使用 `ORD-LOAD{用户序号}{订单序号}`，并带唯一 `requestId` 和压测序号，避免所有请求命中同一个响应缓存。成功判定不仅要求 HTTP 200，还要求 Router 命中 `order_agent`，且响应中包含对应订单号。

## 三、关键结果

### 3.1 并发 20 的分层基线

| 链路 | 成功 QPS | 错误率 | 平均延迟 | P50 | P95 | P99 |
|---|---:|---:|---:|---:|---:|---:|
| Order Direct | 294.64 | 0% | 67.60 ms | 57.79 ms | 128.41 ms | 201.36 ms |
| Router Direct | 195.41 | 0% | 102.01 ms | 73.59 ms | 248.02 ms | 435.35 ms |
| Gateway Full Chain | 195.58 | 0% | 101.91 ms | 76.30 ms | 231.21 ms | 400.74 ms |

Router 加入后吞吐从约 295 QPS 降至约 195 QPS，说明主要吞吐损耗发生在路由编排层，而不是订单数据库读取层。Gateway 在未触发限流时没有进一步造成明显吞吐损失。

### 3.2 Gateway 全链路并发阶梯

| 并发 | 成功 QPS | 错误率 | P50 | P95 | P99 | 说明 |
|---:|---:|---:|---:|---:|---:|---|
| 10 | 106.27 | 39.74% | 62.16 ms | 82.62 ms | 125.37 ms | 单用户速率超过 10 QPS，触发 429 |
| 20 | 195.58 | 0% | 76.30 ms | 231.21 ms | 400.74 ms | 最佳零错误工作点 |
| 20 | 208.12 | 15.13% | 68.90 ms | 190.26 ms | 335.02 ms | 突发额度允许短时超过 200，部分 429 |
| 30 | 123.00 | 0% | 89.25 ms | 997.86 ms | 1818.92 ms | 出现明显排队与吞吐回落 |
| 40 | 140.63 | 0% | 91.73 ms | 1258.42 ms | 2247.13 ms | 尾延迟继续恶化 |
| 50 | 80.71 | 0% | 339.09 ms | 1939.82 ms | 3209.46 ms | 过载区 |
| 100 | 108.04 | 0% | 589.82 ms | 2778.77 ms | 4044.70 ms | 严重过载，继续加并发无收益 |

并发 30 以后 QPS 出现波动而不是稳定增长，同时尾延迟持续抬升，属于典型的排队饱和现象。因此不能用“100 并发仍返回 200”来宣称系统可稳定支持 100 并发低延迟处理。

## 四、限流与资源观察

Gateway 当前配置为每用户每秒补充 10 个令牌、突发容量 20。并发 20 的重复轮次最终稳定在约 200 成功 QPS，并将多余请求返回 HTTP 429，证明限流规则生效。

并发 20 压测期间的容器快照：

| 容器 | CPU | 内存 |
|---|---:|---:|
| Gateway | 64.71% | 747.1 MiB |
| Router | 102.42% | 972.9 MiB |
| Order | 50.15% | 853.1 MiB |
| PostgreSQL | 7.18% | 143.7 MiB |

Docker 的 CPU 百分比以单个逻辑核约 100% 计。Router 超过一个逻辑核，而 PostgreSQL 仅约 7%，与分层 QPS 结果一致：当前主要瓶颈在 Router 编排与下游调用控制，不在数据库。

Router 的 `routerParallelAgentExecutor` 当前为 `corePoolSize=5`、`maxPoolSize=10`、`queueCapacity=20`，并使用 `CallerRunsPolicy`。这能保护下游服务，但在并发超过约 20 后会产生排队和调用线程回压，符合本次 P95 快速升高的现象。

## 五、容量建议

1. 当前单实例告警阈值可先设置为：持续 QPS 140、P95 500 ms、5xx 错误率 1%。
2. 接近 140 QPS 时优先水平扩展 Router 和 Order；不要只提高客户端并发。
3. 调整 Router 线程池前先增加队列长度、活跃线程、拒绝次数和下游调用耗时指标，避免仅扩大线程池后把压力转移到 Order 或模型服务。
4. HTTP 429 应单独统计为限流事件，不能与 5xx 后端故障混为一类。
5. 上线容量确认前补做 10–30 分钟稳态压测、混合读写场景和故障注入；短时 QPS 报告主要用于定位当前吞吐量级和拐点。

## 六、适用边界

- 测试客户端与服务位于同一台机器，未包含公网 RTT、负载均衡器和 TLS 开销。
- 本次是订单查询确定性路径，查询可由结构化证据直接返回，没有调用外部 LLM。
- 采样窗口为 12–15 秒，已包含多轮热态复测，但不是长时间稳定性或容量认证测试。
- 数据库中已有 20,000 条并发订单，能够避免小数据集扫描结果失真；但生产数据分布和索引膨胀仍需另行模拟。

## 七、复现方式

```powershell
.\.venv\Scripts\python.exe .\scripts\qps-load-test.py `
  --scenarios order-direct router-direct gateway-router `
  --concurrency 10 50 100 --duration 12 --warmup 2 `
  --output test-data/performance/qps-results.json
```

原始结果：

- `test-data/performance/qps-results-2026-08-01.json`
- `test-data/performance/qps-results-refinement-2026-08-01.json`
- `test-data/performance/qps-results-repeat-c20-2026-08-01.json`
- `test-data/performance/qps-results-hot-scale-2026-08-01.json`
- `test-data/performance/qps-results-c20-baseline-2026-08-01.json`
