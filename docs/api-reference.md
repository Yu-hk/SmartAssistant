# API 参考

> 由源码 `@RestController` / `@Controller` 与各模块 `application.yml` 提取，路径与方法与实际代码一致。
> 端口见 [`docs/local-setup.md`](local-setup.md#4-服务端口表)。`{var}` 为路径变量。

## 微服务清单

**11 个 Maven 模块 = 10 个可独立部署微服务 + 1 个共享库（common）**，另含独立 `frontend/`。

| 服务 | 端口 | 协议 |
|------|------|------|
| gateway | 8081 | REST |
| consumer | 8082 | REST + SSE + MCP(`/mcp/sse`) |
| router | 8083 | REST |
| product | 8084 | REST + A2A(`/a2a`) + MCP(`/mcp/sse`) |
| order | 8085 | REST + MCP(`/mcp/sse`) |
| user | 8086 | REST |
| general | 8087 | A2A(`/a2a`)（无传统 REST 控制器） |
| tool-registry | 8088 | REST + MCP(`/mcp/sse`) |
| recommend | 8089 | REST |
| embedding-service | 8091 | REST |
| common | — | 共享库（`@ConditionalOnWebApplication` 控制器由 Web 服务激活） |

---

## smart-assistant-user（8086）

### AuthController
- `POST /api/auth/register` — 用户注册
- `POST /api/auth/login` — 用户登录（记录 IP / User-Agent）
- `GET /api/auth/me` — 当前登录用户（Bearer Token）

---

## smart-assistant-recommend（8089）

### RecommendController
- `POST /api/recommend` — 跨模块推荐（请求体含 `userId` / `productCode`）
- `GET /api/recommend/health` — 健康检查

---

## smart-assistant-tool-registry（8088）

### RegistryController
- `POST /api/tools/register` — 注册工具
- `POST /api/tools/register/batch` — 批量注册
- `GET /api/tools` — 工具列表（按标签 / 状态 / 命名空间 / 能力过滤）
- `GET /api/tools/{name}` — 工具详情
- `POST /api/tools/deprecate` — 废弃工具
- `POST /api/tools/{name}/activate` — 启用工具
- `GET /api/tools/health` — 工具健康状态
- `GET /api/tools/{name}/dependents` — 依赖者列表
- `POST /api/tools/call/record` — 记录工具调用（ToolGateway 回调）
- 另暴露 MCP：`/mcp/sse`

---

## smart-assistant-embedding-service（8091）

### EmbeddingController
- `GET /api/embedding/health` — 健康检查（模型可用状态 / 维度）
- `GET /api/embedding/dimensions` — 向量维度
- `POST /api/embedding` — 单条文本嵌入（`{"text": "..."}`）
- `POST /api/embedding/batch` — 批量嵌入（`{"texts": [...]}`）

---

## smart-assistant-gateway（8081）

### TokenManagementController
- `POST /api/gateway/token/blacklist` — Token 加入黑名单（登出）
- `GET /api/gateway/token/check?tokenId=xxx` — 检查 Token 是否在黑名单

---

## smart-assistant-consumer（8082）

### StreamChatController
- `GET /api/math/stream/chat` — SSE 流式对话（参数 `message` / `sessionId` / `requestId` / `showThinking` / `priority`）
- `POST /api/math/stream/chat` — SSE 流式对话（JSON 体）
- `POST /api/math/stream/chat/cancel` — 取消流式对话（`{"requestId": "..."}`）

### DataQueryController
- `POST /api/data/query` — 智能数据查询（仅 ADMIN，`X-User-Role` 鉴权）

### SqlPerformanceController
- `GET /api/monitor/sql/health` — 数据库健康
- `POST /api/monitor/sql/explain` — 分析 SQL 执行计划（仅 SELECT）
- `POST /api/monitor/sql/check-indexes` — 索引使用检查
- `POST /api/monitor/sql/check-slow-queries` — 慢查询检查
- `POST /api/monitor/sql/analyze-tables` — 更新表统计
- `POST /api/monitor/sql/review` — 审查单条 SQL
- `POST /api/monitor/sql/review-batch` — 批量审查

### ChatController
- `POST /api/math/chat` — 智能对话（代理 + 数据增强，信任网关注入 `X-User-Id`）
- `POST /api/math/cache/stats` — 答案缓存统计（仅管理员）

### AnalyticsController
- `GET /api/analytics/stats` — 分析面板统计
- `GET /api/analytics/top-suggestions` — Top 建议点击
- `GET /api/analytics/ab-test` — A/B 测试数据
- `GET /api/analytics/preference-distribution` — 意图偏好分布
- `GET /api/analytics/click-trend` — 点击趋势

### PromptMonitoringController
- `GET /api/prompt-monitoring/stats` — 监控统计
- `POST /api/prompt-monitoring/reset` — 重置统计

### AdminController
- `GET /api/stats` — 数据总览
- `GET /api/sessions` / `GET /api/sessions/{id}` / `DELETE /api/sessions/{id}` — 会话管理
- `GET /api/faq` / `POST /api/faq` / `PUT /api/faq/{id}` / `DELETE /api/faq/{id}` / `POST /api/faq/{id}/hit` — FAQ
- `GET /api/check-login` — 登录状态
- `POST /api/save-env-config` — 保存环境变量配置
- `POST /api/permission-response` — 权限弹窗响应
- `GET /api/admin/costs` — 成本统计

### PageController（MVC）
- `POST /api/session/refresh` — 刷新会话（新 threadId）
- `GET /api/session/stats` — 活跃会话数
- `GET /speech-test` — 语音输入测试页（视图）

### FeedbackController
- `POST /api/feedback` — 用户反馈（点赞/踩）
- 另暴露 MCP：`/mcp/sse`

---

## smart-assistant-order（8085）

### OrderRecommendController
- `GET /api/order/user/{userId}/products` — 用户购买记录
- `GET /api/order/user/{userId}/orders` — 用户订单摘要

### OrderAgentController
- `POST /api/order/agent/process` — Agent 处理（意图识别 + RAG 预检索，含无证据拒答）

### KnowledgeController
- `POST /api/knowledge/reindex` — 重建知识库
- `POST /api/knowledge/status` — 知识库状态
- 另暴露 MCP：`/mcp/sse`

---

## smart-assistant-router（8083）

### RouterController
- `POST /api/router/route` — 智能路由
- `GET /api/router/health` — 健康检查
- `POST /api/router/react/route` — ReactAgent 路由测试
- `POST /api/router/compare/route` — 传统 vs ReactAgent 对比
- `POST /api/router/test/route` — 测试接口（免 JWT）
- `GET /api/router/tools/health` — 工具健康
- `GET /api/router/events/{requestId}` — Agent 事件

### ExperienceAdminController
- `GET /api/experience/stats` — 经验统计
- `GET /api/experience/list` — 列出经验（`type` 可选）
- `GET /api/experience/{expId}` / `DELETE /api/experience/{expId}` — 查询/删除
- `POST /api/experience/create` — 手动创建
- `DELETE /api/experience/clear` — 清空
- `POST /api/experience/export` — 导出经验为 MD 知识库
- `GET /api/experience/export/{agentName}` — 查看知识库 MD
- `GET /api/experience/feedback-patterns` — 反馈学习全局模式

### ExecutionDagController
- `GET /api/admin/execution-dag/{requestId}` — DAG 执行数据（JSON）
- `GET /api/admin/execution-dag/{requestId}/view` — 可视化 HTML

### AgentRegistrationController
- `GET /api/router/agents` — 已发现 Agent
- `GET /api/router/agents/cached` — 缓存 Agent
- `POST /api/router/agents/heartbeat` — 心跳
- `POST /api/router/agents/refresh` — 重新发现
- `GET /api/router/agents/{serviceName}` — Agent 详情

### AgentDiscoveryAdminController
- `GET /api/admin/agent-discovery/status` — 发现状态
- `POST /api/admin/agent-discovery/scan` — 手动扫描
- `POST /api/admin/agent-discovery/refresh` — 刷新

---

## smart-assistant-product（8084）

### ProductStreamController
- `GET /product/stream/chat` — SSE 流式对话（thinking/tool_call/tool_result/response/done）
- `POST /product/stream/chat/sync` — 同步对话（兼容）

### ProductRecommendController
- `GET /api/product/list` — 商品列表
- `GET /api/product/{code}/recommend` — 商品关联推荐
- `GET /api/product/{code}/info` — 商品详情
- 另暴露 A2A：`/a2a`；MCP：`/mcp/sse`

---

## smart-assistant-common（共享库，非独立部署）

以下控制器带 `@ConditionalOnWebApplication`，由引入该库的 Web 服务激活：

### ReviewQueueController
- `GET /api/knowledge/review/queue` — 复核队列（`status` 过滤）
- `GET /api/knowledge/review/queue/{id}` — 单条
- `POST /api/knowledge/review/queue/{id}/approve` — 审批通过
- `POST /api/knowledge/review/queue/{id}/reject` — 审批拒绝
- `GET /api/knowledge/review/queue/count/pending` — 待复核数

### IngestionWebhookController
- `POST /api/knowledge/ingest/webhook` — 对象存储事件回调

### IngestionJobController
- `POST /api/knowledge/ingest/submit` — 提交摄取任务
- `GET /api/knowledge/ingest/jobs/{jobId}` — 任务进度
- `POST /api/knowledge/ingest/jobs/{jobId}/retry` — 重试
- `GET /api/knowledge/ingest/jobs` — 任务列表（`tenantId` 可选）

---

## smart-assistant-general（8087）

- 无传统 REST 控制器，仅通过 **A2A** 暴露能力：`/a2a`（AgentCard 名 `general_chat`）。
