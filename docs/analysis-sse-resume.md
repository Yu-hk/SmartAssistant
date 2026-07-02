# SSE 断线续传——文章观点 vs 项目现状

> 原文：《Agent 面试拷打（十五）：用户刷新了页面，SSE 流怎么接着推？》（阿锦AI，2026-07-01）
> 对照项目：SmartAssistant
> 分析日期：2026-07-02

---

## 文章核心观点

SSE 断线续传的解决方案分三层递进：

**L1 — 常规答法（Last-Event-ID）**
每条 SSE 消息带 `id:`，浏览器用 `EventSource` 自动重连并携带 `Last-Event-ID`，服务端从断点补发。

**L2 — 生成结果落地**
模型生成的增量内容按消息 id 落地存（缓存/Redis），重连时补发未送达段。

**L3 — 架构思想（核心）**
> **把"生成"和"连接"彻底解耦。** 生成是后台任务，连接是取数管道。模型内容先写缓冲区，断了再来从缓冲区接着取。

---

## 项目现状

### 架构已解耦（L3 ✅）

SmartAssistant 的 SSE 架构天然符合文章所说的"生成和连接解耦"：

```
[前端] ←SSE→ [Consumer] ←HTTP→ [Router] ←HTTP→ [Agent: LLM]
```

- **生成**：Agent 服务中的 LLM 推理（独立进程）
- **连接**：Consumer 代理转发 SSE 流
- **任务关联**：`requestId` 跨服务贯通

### 具体对比

| 文章要求 | 项目现状 | 覆盖度 |
|---------|---------|:------:|
| L3: 生成和连接解耦 | ✅ 天然解耦——Agent 生成，Consumer 转发 | ✅ |
| L3: 会话 id 关联任务 | ✅ `requestId` 跨服务传递 | ✅ |
| L3: Redis 缓冲区 | ⚠️ Router 有 `storeSseEvent()` 存入 Redis List(TTL=120s)，但用于多 Agent 事件转发，非 LLM 增量文本 | ⚠️ 部分 |
| L2: 增量文本按 id 落地 | ❌ 无 SSE 事件级别的文本缓存 | ❌ |
| L1: 每条消息带 `id:` | ⚠️ ProductStreamController 有 `step` ID，但 Consumer 转发时**不处理 ID** | ❌ |
| L1: `EventSource` 自动重连 | ❌ 前端使用 `fetch().ReadableStream()`，非 `EventSource` | ❌ |
| L1: `Last-Event-ID` 处理 | ❌ 未实现 | ❌ |

---

## 关键缺失分析

### 缺失 1：前端未使用 EventSource

前端 `useChat.ts` 使用 `fetch` API 的 `ReadableStream` 读取 SSE：

```typescript
const response = await fetch('/api/chat', { method: 'POST', body: ... });
const reader = response.body.getReader();
// 手动读取流，无自动重连，无 Last-Event-ID
```

`EventSource` 内置自动重连 + `Last-Event-ID` 协议支持，但只能处理 `GET` 请求。项目当前 SSE 支持 `GET` 和 `POST`，可以部分迁移。

### 缺失 2：Consumer 转发时不管理事件 ID

`forwardSSE()` 逐字节转发 Agent 的 SSE 流，不注入递增事件序列号：

```java
// 当前：无 ID 管理
// 期望：每条 data: 转发前注入 id: seqNo
response.getOutputStream().write(("id: " + seqNo++ + "\n").getBytes());
```

### 缺失 3：无 SSE 事件缓冲区

Consumer 转发后的 SSE 事件不存 Redis。前端断开后，未送达的 `data:` 行丢失。

---

## 改进建议

### P0（半日）

**前端迁移到 EventSource（仅 GET 路径）** — 简单改动，ROI 高
- 前端 `useChat.ts` 对于 `GET /api/chat` 路径改用 `EventSource`
- 获得自动重连 + `Last-Event-ID` 协议支持
- Consumer 当前 `streamChat()` 已经是 `@GetMapping`

```typescript
// 当前
const response = await fetch('/api/chat', { ... });

// 期望
const es = new EventSource(`/api/chat?message=${encodeURIComponent(msg)}&...`);
```

### P1（1 天）

**Consumer 转发时注入事件 ID + Redis 缓冲区**

在 `forwardSSE()` 中：
1. 解析每段 SSE `data:` 行的 `type` 字段
2. 为每段数据生成递增 `seqNo`
3. 在转发前插入 `id: seqNo` 行
4. 将 `{seqNo, type, content}` 存入 Redis List（requestId 维度，TTL=300s）

```java
// 转发前注入 ID
int seqNo = getNextSeq(requestId);
output.write(("id: " + seqNo + "\n").getBytes());
// 缓存到 Redis
redisTemplate.opsForHash().put("sse:buffer:" + requestId, String.valueOf(seqNo), data);
redisTemplate.expire("sse:buffer:" + requestId, 300, TimeUnit.SECONDS);
```

### P2（1 天）

**Consumer 断线续传端点**

新增 `GET /chat/resume?requestId=X&lastEventId=Y`：
1. 从 Redis 读取 `sse:buffer:{requestId}` 中 seqNo > lastEventId 的事件
2. 补发给前端
3. 继续代理转发后续 Agent SSE 流

---

## 总结

| 文章层次 | 项目状态 | 建议 |
|:--------|:--------|:-----|
| L3 生成连接解耦 | ✅ **已实现** | — |
| L2 结果落地 | ❌ 未实现 | P1: Redis SSE 缓冲区 |
| L1 Last-Event-ID | ❌ 未实现 | P0: EventSource + P1: ID 注入 |

这是一个**有实际价值的改进方向**——用户咨询复杂问题时刷新页面，正在生成的回复不会丢失。三篇文章中，这篇文章是唯一一个项目尚未实现且值得投入的领域。ROI 中等：P0 前端改动小、收益明确；P1 需 Consumer 增加缓存逻辑，涉及 Redis 内存消耗。
