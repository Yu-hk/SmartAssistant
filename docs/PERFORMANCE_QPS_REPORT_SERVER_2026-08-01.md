# SmartAssistant 阿里云服务器 QPS 压测报告

- 测试日期：2026-08-01
- 服务器：Alibaba Cloud Linux 3，4 vCPU、16 GiB 内存、80 GiB ESSD
- 部署形态：Gateway、Router、Consumer、Order、Product、User、General、Embedding、Nginx 及 PostgreSQL/PGVector 等均部署在同一台服务器
- 压测方式：压测客户端在服务器内运行，固定并发闭环请求，HTTP/1.1 长连接；不计入公网和 SSH 隧道 RTT
- 数据规模：1,000 用户、5,000 会话、120 商品、20,000 订单、30,000 路由日志、3,000 反馈、4,000 优惠券及配套物流/退款/审批数据

## 1. 最终结论

当前 4 核单机、单副本配置下，认证用户经 Gateway、Router、Order Service、PostgreSQL 查询本人订单与物流的确定性完整链路：

- **最大零错误实测吞吐：60.15 QPS**，并发 100，649/649 成功，P50 1,505.61 ms，P95 2,249.17 ms，P99 2,635.64 ms。
- **P95 约 2 秒的工作点：约 48 QPS**，并发 60，504/504 成功，P95 1,702.27 ms。
- **建议持续容量：35–40 QPS/实例**。该值为最大实测吞吐的约 58%–67%，为 GC、异步后处理、混合业务和流量波动保留余量。
- Order Service 直连峰值为 **177.51 QPS**，并发 40，P95 376.88 ms；Router/编排层是当前主要容量瓶颈。

因此，对外描述当前服务器容量时建议使用：

> **4 核服务器上的确定性订单客服完整链路，短时零错误峰值约 60 QPS，建议按 35–40 QPS 持续容量规划。**

该数字不代表外部大模型生成、复杂 RAG、长工具链或长时间 SSE 的吞吐，这些链路需要独立压测。

## 2. 最终完整链路阶梯结果

以下结果来自内存与交易缓存问题修复后的干净部署。每档预热 1 秒、正式采样 10 秒，每个并发线程使用不同登录用户和有效 JWT，请求轮换合法订单并携带唯一 `requestId`。

| 并发 | 请求数 | 成功数 | 成功 QPS | 错误率 | 平均延迟 | P50 | P95 | P99 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | 185 | 185 | 17.95 | 0% | 1,085.18 ms | 1,025.76 ms | 2,165.63 ms | 2,295.40 ms |
| 40 | 343 | 343 | 32.28 | 0% | 1,189.61 ms | 1,079.26 ms | 2,084.41 ms | 2,381.70 ms |
| 60 | 504 | 504 | 48.12 | 0% | 1,197.65 ms | 1,203.30 ms | 1,702.27 ms | 1,805.31 ms |
| 100 | 649 | 649 | **60.15** | 0% | 1,555.61 ms | 1,505.61 ms | 2,249.17 ms | 2,635.64 ms |

并发从 60 增至 100 后，吞吐增长 25%，但平均延迟增长约 30%，已经进入排队区。继续提高并发对客服体验收益有限，因此没有继续加压。

## 3. 分层基线与瓶颈

### 3.1 并发 20 基线

| 链路 | 成功 QPS | 错误率 | 平均延迟 | P95 | P99 |
|---|---:|---:|---:|---:|---:|
| Order Direct | 159.59 | 0% | 123.56 ms | 205.33 ms | 242.56 ms |
| Router Direct | 28.87 | 0% | 676.52 ms | 953.09 ms | 1,322.89 ms |
| Gateway Full Chain | 21.48 | 0% | 887.11 ms | 1,310.08 ms | 1,450.04 ms |

### 3.2 各层最大零错误测试值

| 链路 | 最大测试值 | 并发 | P95 | 说明 |
|---|---:|---:|---:|---|
| Order Direct | 177.51 QPS | 40 | 376.88 ms | 并发 60 时回落到 160.11 QPS |
| Router Direct | 71.41 QPS | 100 | 1,570.83 ms | 交易缓存优化前的数据，作为保守基线 |
| Gateway Full Chain | 60.15 QPS | 100 | 2,249.17 ms | 最终修复后数据 |

PostgreSQL 在压测后的 CPU 快照约 1%–2%，Order 约 3%–4%，而 Router 和 Embedding 保持较高 CPU，占用表明主要瓶颈位于路由编排、异步语义处理和下游调用，而不是订单表查询。

## 4. 压测中发现并修复的问题

### 4.1 Router cgroup OOM

并发 60 的高压阶段，宿主机内核日志记录 Router 在 768 MiB cgroup 上限处触发 OOM。Podman 的容器状态显示 `OOMKilled=false`，但内核明确记录了 `mem_cgroup_out_of_memory` 和 Java 进程被终止。

修复：

- Router 容器内存上限从 768 MiB 提升到 1,280 MiB；JVM 最大堆仍保持 512 MiB，为线程、网络缓冲和本地缓存保留原生内存。
- 同步更新 `deploy/remote-rollout.sh` 和 `deploy/docker-compose.yml`。
- 最终压测后 Router 内存约 789 MiB，`RestartCount=0`，无新增 OOM 日志。

### 4.2 实时交易结果被做语义缓存和经验沉淀

订单状态等实时结果虽然走关键词快路由，但响应后仍会生成语义向量、缓存可变回复并提取经验。在高压下，这会持续占用 Embedding CPU，还存在跨请求复用动态订单事实的风险。

修复：

- `order_agent` 及订单/退款/物流意图只保存审计和精确路由映射。
- 不再保存可复用回复、不生成语义向量、不沉淀动态交易经验。
- 相关 Router 回归测试 10/10 通过。
- 修复后完整链路峰值从 47.47 QPS 提升到 60.15 QPS，提升约 26.7%。

### 4.3 PGVector 维度不一致

Embedding 服务实际输出 512 维，Router 的 `experience_embeddings` 原为 1,024 维，导致写入失败。

修复后数据库向量列为：

| 表 | 行数 | 向量维度 |
|---|---:|---:|
| `experience_embeddings` | 2 | 512 |
| `knowledge_docs` | 67 个分块 | 512 |
| `vector_store` | 0 | 1,024（Spring AI 独立空表，未参与当前知识链路） |

最终 Router 日志未发现向量维度错误或重复键错误。

## 5. 正确性与稳定性复核

- 部署验收脚本检查 Embedding、Gateway、User、Router、Consumer、Order、Product、General、Nginx 共 9 个入口，`FAILURES=0`。
- 最终真实客服回归 **15/15 通过**，包括鉴权、订单隐私、跨账号隔离、同步/SSE、商品、真实政策知识、退款预检、多轮上下文、人工转接、防编造和提示注入。
- 10 个登录用户并发 SSE 查询各自订单 **10/10 成功**，平均 1,174.80 ms，最大 1,334.23 ms。
- Router 最终内存上限 1,280 MiB，压测后无重启、无新增 OOM。

## 6. 适用边界与后续建议

- 采样窗口为 8–15 秒，用于短时容量和拐点定位，不是生产容量认证。上线前建议补做 30–60 分钟稳态压测。
- 压测请求为确定性订单读取，不调用外部大模型；应另测知识 RAG、生成式对话、退款写操作和混合流量。
- 当前所有服务为单机单副本。若持续流量接近 35–40 QPS，优先水平扩容 Router，再根据指标扩容 Order/Embedding。
- 建议生产告警初值：完整链路 P95 2 秒、5xx 错误率 1%、Router 内存 1.1 GiB、持续 QPS 40。
- 服务器无 Swap；需为 Java 原生内存保留足够 cgroup 余量，并监控宿主机内核 OOM 日志。

## 7. 测试产物

- 无依赖服务器压测脚本：`scripts/qps-load-test-py36.py`
- 最终 QPS 原始结果：`test-data/performance/qps-server-final-after-fixes-2026-08-01.json`
- 初始分层结果：`test-data/performance/qps-server-2026-08-01.json`
- 高并发分层结果：`test-data/performance/qps-server-high-concurrency-2026-08-01.json`
- 饱和测试结果：`test-data/performance/qps-server-saturation-2026-08-01.json`
- 最终客服结果：`test-data/customer-service-server-results-2026-08-01-final-after-performance-fixes.json`
