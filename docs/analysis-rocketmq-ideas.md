# RocketMQ 文章思想 → SmartAssistant 改进方案（不引入 MQ）

> 原文：《RocketMQ 已正式接入 AI 啦！》（2026-07-02）
> 核心思想：用事件驱动 + 持久化位点的思路，替代 HTTP 连接捆绑状态的架构。
> 约束：不引入 RocketMQ / Redis Stream 等新组件，仅在现有 HTTP + Redis 架构内改进。

---

## 三大改进点（按 ROI 排序）

### P0：SSE 缓冲区 Redis Sorted Set 改造（0.3天）

**当前问题：**
`sse:buffer:{requestId}` 使用 Redis Hash，`resumeFromBuffer()` 读取 ALL keys 后客户端过滤 seqNo > lastEventId。缓冲区 10000 条事件时每次续传需读取 10000 个字段 → O(n) 读放大。

**改进方案：**
改用 Redis Sorted Set，score = seqNo，value = SSE data。

| 操作 | 当前 (Hash) | 改造后 (Sorted Set) |
|:----|:-----------|:-------------------|
| 写入 | `HSET key seqNo data` | `ZADD key seqNo data` |
| 续传 | `HGETALL → 遍历过滤 seqNo > N` | `ZRANGEBYSCORE key (N +inf` |
| 清理 | `DEL key` (TTL 统一过期) | `ZREMRANGEBYSCORE key -inf end` |
| 复杂度 | O(n) 读放大 | O(log n) 范围查询 |

```java
// 当前
redisTemplate.opsForHash().put(redisKey, String.valueOf(seqNo), dataPart);
// 续传：HGETALL → 全量遍历

// 改造后
redisTemplate.opsForZSet().add(redisKey, dataPart, seqNo);
// 续传：ZRANGEBYSCORE — 仅返回 seqNo > lastEventId 的事件
Set<String> pending = redisTemplate.opsForZSet().rangeByScore(
        redisKey, lastSeq + 1, Long.MAX_VALUE);
```

**LiteTopic 类比：** Broker 侧消费位点的 Redis 版实现——SEQ 就是 MQ 的消息偏移量，ZRANGEBYSCORE 就是 MQ 的位点查询。

---

### P1：基于 Redis List 的异步 Agent 事件总线（1天）

**当前问题：**
Agent 间协作是同步 HTTP 调用——`HandoffCommand` → `executeHandoffNode()` → HTTP 请求 → 阻塞等待响应。慢 Agent 阻塞快 Agent，失败后无重试队列。

**改进方案：**
用 Redis List 做轻量事件总线，Router 发布事件，Agent 异步消费。

```
当前 (同步 HTTP):                      改进 (事件驱动):
                                      
Agent A ──HTTP──→ Agent B            Agent A ──RPUSH──→ Redis List
  (等待 B 返回)                          ↑ 立即返回
                                     Agent B ←──BLPOP── Redis List
  → 阻塞，A 不能做别的事                   → 异步，A 可继续处理其他请求
```

```java
// Router 端：发布 Agent 事件到 Redis List
String eventKey = "agent:events:" + targetAgent;
redisTemplate.opsForList().rightPush(eventKey, jsonEvent);

// Agent 端：每个 Agent 启动一个独立线程消费自己的事件队列
@PostConstruct
public void startEventConsumer() {
    Thread.startVirtualThread(() -> {
        while (!Thread.currentThread().isInterrupted()) {
            String event = redisTemplate.opsForList().leftPop(
                    "agent:events:" + agentName, 5, TimeUnit.SECONDS);
            if (event != null) {
                processEvent(event);
            }
        }
    });
}
```

**优势：**
- 解耦：Agent A 不等待 Agent B，可继续处理新请求
- 弹性：Agent B 处理慢时事件在 List 中堆积，不会拖慢上游
- 重试：消费失败可重新 RPUSH 回队列（或进入重试队列）
- 持久化：Redis List 可持久化，服务重启后事件不丢

**Handoff 的兼容：**
同步 Handoff 保留作为"需要立即返回结果的场景"使用。异步事件总线用于"无需等待返回"或"可容忍延迟"的 Agent 间协作：

```java
// 两种模式并存
if (handoff.isSyncRequired()) {
    // 同步 Handoff（旧逻辑）— 需要立即拿到结果
    return executeHandoffNode(cmd, ...);
} else {
    // 异步事件总线（新逻辑）— 发出去就行
    publishToEventBus(cmd.targetAgent(), cmd);
    return pendingResult;
}
```

**LiteTopic 类比：** 每个 Agent 对应一个 LiteTopic（`agent:events:{agentName}`），队列即 Topic。

---

### P2：会话级细粒度流控（0.5天）

**当前问题：**
`RequestQueueService` 使用全局 Semaphore（默认 5 并发）+ FIFO 队列。一个会话刷屏会耗尽所有槽位，其他正常用户排队等待；无优先级区分。

**改进方案：**
增加三层流控：

```
请求进入
  │
  ├── L1: 全局限流（Semaphore 5）— 已有
  │
  ├── L2: 会话级限流（每 sessionId 最多 1 并发）
  │     同一用户不能并行占用多个 LLM 槽位
  │
  └── L3: 优先级队列（vip 优先于普通用户）
         priority=1 用户先于 priority=0 用户获取槽位
```

```java
// L2: 会话级并发控制
private final ConcurrentHashMap<String, AtomicInteger> sessionConcurrency = new ConcurrentHashMap<>();

public boolean tryAcquireSessionSlot(String sessionId) {
    AtomicInteger counter = sessionConcurrency.computeIfAbsent(
            sessionId, k -> new AtomicInteger(0));
    if (counter.incrementAndGet() > 1) {
        counter.decrementAndGet();
        return false; // 该会话已有请求在处理中
    }
    return true;
}

// L3: 优先级队列（替代 FIFO）
PriorityBlockingQueue<Request> priorityQueue = new PriorityBlockingQueue<>(
    1000, Comparator.comparingInt(r -> r.priority));
```

**LiteTopic 类比：** 文章中的"按 LiteTopic 暂停/恢复"——对应 L2 的按 sessionId 暂停；"不同用户不同 QoS"——对应 L3 的优先级调度。

---

## 实施路径

| 优先级 | 改进 | 工作量 | 风险 | ROI |
|:------|:-----|:------:|:----:|:---:|
| **P0** | SSE 缓冲区 Sorted Set 改造 | 0.3天 | 低（纯替换数据结构） | **高** |
| **P1** | 异步 Agent 事件总线 | 1天 | 中（需设计事件 schema + 重试） | **中** |
| **P2** | 会话级细粒度流控 | 0.5天 | 低（独立模块改动） | **中** |

---

## 总结

| 文章理念 | 无 MQ 方案的等价实现 | 优先级 |
|:--------|:-------------------|:------:|
| LiteTopic 会话通道 | Redis Sorted Set (score=seqNo) | P0 |
| Broker 侧消费位点 | `ZRANGEBYSCORE (lastSeq +inf` | P0 |
| 多 Agent 事件驱动 | Redis List 异步事件总线 | P1 |
| 按 Topic 暂停/恢复 | sessionId 级并发控制 | P2 |
| 优先级 QoS | PriorityBlockingQueue | P2 |

三种改进都不需要引入 MQ，完全在现有 HTTP + Redis 架构内完成。
