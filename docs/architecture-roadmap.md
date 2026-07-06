# SmartAssistant 架构演进路线图

> 评估日期：2026-07-06
> 基于 Snail AI 对标分析和完整源码调查

---

## 总览

| 改造方向 | 优先级 | 工作量 | 收益 | 风险 |
|---------|--------|--------|------|------|
| **管理端 API** | 🔴 **高** | 2-3天 | 前端管理功能可用 | 低 |
| **多数据库** | 🟡 中 | 5-7天 | 信创合规 | 高（pgvector绑定） |
| **gRPC** | 🟢 低 | 5-6天 | 性能提升有限 | 中 |

---

## 一、管理端 API（推荐优先做）

### 现状问题

前端 `AdminPage.tsx` 调用 `GET /api/stats`、`/api/sessions`、`/api/faq` 但后端**不存在**这些端点。
后端已有的 `AnalyticsController`、`Router/AgentDiscoveryAdminController` 等管理 API 前端**未对接**。
前后端 API 存在显著不匹配。

### 需要实现的 API

| API | 方法 | 紧急度 | 说明 |
|-----|------|--------|------|
| `/api/stats` | GET | 🔴 高 | 管理后台数据总览（对话数、满意度、转人工率等） |
| `/api/sessions` | GET | 🔴 高 | 会话列表（分页+筛选） |
| `/api/sessions/{id}` | GET | 🔴 高 | 会话详情 |
| `/api/sessions/{id}` | DELETE | 🔴 高 | 删除会话 |
| `/api/faq` | GET | 🔴 高 | FAQ 列表 |
| `/api/faq` | POST | 🔴 高 | 新增 FAQ |
| `/api/faq/{id}` | PUT | 🔴 高 | 更新 FAQ |
| `/api/faq/{id}` | DELETE | 🔴 高 | 删除 FAQ |
| `/api/agent-discovery/status` | GET | 🟡 中 | Agent 状态（已有后端，需对接前端） |
| `/api/experience/stats` | GET | 🟢 低 | 经验统计（已有后端，需对接前端） |

### 实现方案

在 Consumer 模块新增 `AdminController`：
- `GET /api/admin/stats` — 聚合 AnalyticsService + PageService 数据返回 AdminStats
- `GET /api/admin/sessions` — 会话分页查询
- `GET/DELETE /api/admin/sessions/{id}` — 会话详情/删除
- `GET/POST/PUT/DELETE /api/admin/faqs` — FAQ CRUD

### 预估工作量：**2-3 天**

---

## 二、多数据库适配（中期）

### 现状

**重度依赖 PostgreSQL 特有功能**：

| 特性 | 使用处 | 迁移难度 |
|------|--------|---------|
| pgvector (`<=>`, `::vector`, HNSW 索引) | 2个索引 + 1个 Mapper SQL | 🚫 **不可直迁** |
| JSONB | 2张表 schema + 1个 Mapper XML | 🟡 中（-> MySQL JSON） |
| 物化视图 | 3个 | 🟡 中（-> 触发器或定时刷新） |
| PL/pgSQL 函数 | 4个分区函数 + 1个触发器 | 🔴 高 |
| 数组 `text[]` | 1列 | 🟢 低（-> JSON） |
| `INTERVAL` 语法 | ~15处 | 🟢 低 |
| `FILTER (WHERE ...)` | 3处 | 🟢 低（-> CASE WHEN） |
| `BIGSERIAL` / `GENERATED AS IDENTITY` | 所有表 | 🟢 低 |

### 推荐方案：**双数据库策略**（非迁移）

```
业务数据 → PostgreSQL（主库，含 pgvector）
                          ↓
                    MyBatis-Plus 多方言
                          ↓
向量数据 → Milvus（已存在）   ← 替代 pgvector
```

核心思路：
1. 保持 PostgreSQL 为主库（9 张业务表基本兼容 MySQL）
2. 向量数据已通过 Milvus 存储，**pgvector 可逐步废弃**（`ExperienceEmbeddingMapper` 是用 pgvector 的路由经验表，可迁移到 Milvus）
3. 移除 pgvector 的依赖后，可增加 **MySQL 适配层**通过 Spring 的 `@Profile("mysql")` 或 MyBatis-Plus 的多租户能力切换
4. 达梦数据库适配需额外工作，使用独立 XML 映射文件

### 依赖清理路径

1. ✅ Milvus 已存在，向量数据已存 Milvus
2. 🔲 迁移 `experience_embeddings` 从 pgvector 到 Milvus
3. 🔲 移除 pgvector schema SQL（索引 + extension）
4. 🔲 移除 JSONB 改为普通 JSON 文本列
5. 🔲 物化视图改为定时任务刷新
6. 🔲 分区函数改为 MyBatis-Plus 自动分表
7. 🔲 增加 MySQL 数据源配置 + 方言切换

### 预估工作量：**5-7 天**

---

## 三、gRPC 通信（远期）

### 现状

当前 7 个服务间 HTTP 端点：

```
Consumer → Router (REST + Redis)
Router → Order/Product/General (HTTP POST)
Recommend → Product/Order (Feign Client)
```

### gRPC 在此项目中的收益分析

| 评估维度 | 说明 | 收益 |
|---------|------|------|
| **请求模式** | 全部为请求-响应模式，无 streaming | 📉 低（gRPC streaming 优势未发挥） |
| **数据量** | JSON 序列化 1-10KB | 📉 低（Protobuf 压缩收益有限） |
| **延迟** | Router→Agent 平均 200-500ms | 📉 低（网络序列化占 <1%） |
| **版本管理** | 7 个接口，变化低频 | 📉 低（Proto 版本管理优势不突出） |
| **现有设施** | Resilience4j + RestTemplate 成熟稳定 | 📈 放弃已有投资不划算 |

### 轻量替代方案

**优先考虑 HTTP/2 + 连接池优化**（非全套 gRPC）：

```java
// 改成：复用连接池
@Bean
public RestTemplate restTemplate() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    // 无连接池限制
    return new RestTemplate(factory);
}

// 改为：HTTP/2 + 连接池
@Bean
public RestTemplate restTemplate() {
    HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    return new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
}
```

**若日后需要 gRPC**，建议只在 **Router → Agent** 的流式响应场景（DeepSeek 推理过程 streaming）引入，其他同步请求-响应保持 HTTP REST 即可。

### 预估工作量：**5-6 天（全套）** / **1 天（连接池优化）**

---

## 推荐路线

```
Phase 1（推荐立即做，2-3天）
╔══════════════════════════════════════════════════╗
║  管理端 API 补齐                                    ║
║  ├── 实现 /api/admin/stats（聚合数据总览）           ║
║  ├── 实现 /api/admin/sessions CRUD（会话管理）      ║
║  ├── 实现 /api/admin/faqs CRUD（FAQ 管理）          ║
║  └── 前端对接 Router 端已有管理 API（Agent/经验）   ║
╚══════════════════════════════════════════════════╝

Phase 2（建议近期推进，1天）
╔══════════════════════════════════════════════════╗
║  HTTP 连接池优化（轻量替代 gRPC）                    ║
║  ├── RestTemplate 改用 HTTP/2 + 连接池复用          ║
║  └── 保持 HTTP REST 协议不变                        ║
╚══════════════════════════════════════════════════╝

Phase 3（建议长期筹划，5-7天）
╔══════════════════════════════════════════════════╗
║  多数据库适配（按需启动）                              ║
║  ├── 迁移 pgvector 经验表到 Milvus                 ║
║  ├── 移除 PL/pgSQL 函数和物化视图                   ║
║  └── 增加 MySQL 适配层 + Profile 切换               ║
╚══════════════════════════════════════════════════╝
```

---

## 决策总结

| 方向 | 我们的评估 | 是否建议立刻做 |
|------|-----------|--------------|
| **管理端 API** | 前后端不匹配是实际痛点，前端页面展示不全 | ✅ **是**（2-3天） |
| **gRPC** | 7 个请求-响应端点，收益有限，现有 REST+熔断足够 | ❌ **暂缓**，优先做 HTTP/2 连接池优化 |
| **多数据库** | pgvector 强绑定 PostgreSQL，但 Milvus 已存在可剥离 | ⏸ **按需启动**，有信创需求时再做 |
