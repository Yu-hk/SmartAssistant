# 剩余改进点清单（2026-07-02 状态）

> 覆盖今天四篇文章分析 + 实施的 P0/P1/P2 之后，项目剩余的改进空间。

---

## 一、文章已识别但未实施的缺口

### RAG 动态更新（P1 ~2天 ｜ P2 ~2天）

| 优先级 | 改进点 | 工作量 | 说明 |
|:------|:-------|:------:|:-----|
| **P1** | **先删后增（PgVector/Milvus）** | 0.5天 | `KnowledgeBase` 接口增加 `removeByBaseDocId()`，三个实现类分别实现。当前仅 InMemory 有 `removeSuperseded()`，PgVector/Milvus 修改前不清理旧 chunk |
| **P1** | **缓存失效机制** | 0.5天 | `KnowledgeIngestionService` 成功入库后失效相关的 RAG 查询缓存。涉及 `AnswerCacheService` 的缓存键设计 |
| **P2** | **Chunk 注册表 + 状态机** | 1天 | 新建 `rag_chunk_registry` 表（PgVector）/ Collection（Milvus），`index_status: pending → ready / partial_failed` |
| **P2** | **三端对账（reconciliation）** | 1天 | `@Scheduled` 定时任务扫描 `partial_failed` chunk，以注册表为 truth 修复向量库/全文索引差异 |
| **P2** | **分布式锁 + 乱序保护** | 0.5天 | 文档级分布式锁 + revision 比较，旧版本不能覆盖新版本 |

**ROI 评估：** P1 先删后增 + 缓存失效是直接的生产级增强，建议优先。P2 注册表+对账项目前数据量下 ROI 较低。

---

### SSE 断线续传（P1 ~1天 ｜ P2 ~1天）

| 优先级 | 改进点 | 工作量 | 说明 |
|:------|:-------|:------:|:-----|
| **P1** | **Consumer 转发时注入事件 ID + Redis 缓冲区** | 0.5天 | 已实施 ✅ |
| **P1** | **前端 EventSource 迁移** | 0.5天 | 已实施 ✅ |
| **P2** | **断线续传端点 `/chat/resume`** | 0.5天 | 已实施（内嵌在 GET /chat 的 resumeFromBuffer）✅ |
| **P2** | **Redis 缓冲区自动清理** | 0.5天 | 当前只有 TTL，可增加"事件数上限"保护（如最大 10000 条） |

---

## 二、编译警告与代码质量

| 优先级 | 问题 | 文件 | 工作量 |
|:------|:-----|:------|:------:|
| **P3** | `ChatClient.Builder.defaultToolCallbacks()` 已过时 | `HybridDataQueryService.java:59` | 0.2天 |
| **P3** | `@Builder` 忽略初始化表达式（需加 `@Builder.Default`） | `StructuredPrompt.java:36,61` | 0.1天 |
| **P3** | 前端 TypeScript 编译错误（3 个文件） | `AgentConfigDialog.tsx`、`Header.tsx`、`NewChatDialog.tsx` | 0.5天 |

---

## 三、测试覆盖缺口

| 优先级 | 改进点 | 相关工作量 |
|:------|:-------|:----------:|
| **P2** | Graph 编排单元测试（ErrorType 分类、重试、重规划） | 1天 |
| **P2** | ExperienceService BGE 语义合并测试 | 0.5天 |
| **P2** | RouterRagService 回指测试（extractEntities + generateBackReferences） | 0.5天 |
| **P2** | HashUtil + ContentHashCache 单元测试 | 0.3天 |
| **P2** | SSE 断线续传集成测试（Consumer + Frontend） | 1天 |

---

## 四、跨文章/跨模块的架构机会

| 领域 | 机会 | ROI |
|:----|:-----|:---:|
| **Observability** | Grafana 仪表盘新增：ErrorType 分布图、SSE 缓冲区大小、HashUtil 跳过率 | 高（已有指标，缺面板） |
| **响应格式统一** | 之前识别过 ErrorResponse 字段不统一（msg/message 混用），对齐 OpenAI 格式 | 中 |
| **Token 追踪** | 已完成本地存储版，面板已配置，但未对接 SSE 事件的 token 回传 | 中 |
| **E2E 测试** | 当前 42 用例(29PASS/11WARN/2FAIL)，新增功能（SSE 续传/Replan/回指）缺 E2E 覆盖 | 中 |

---

## 五、按 ROI 排序的推荐优先级

```
高 ROI（P0-P1，< 1天）
  ├── P1: 先删后增（PgVector/Milvus removeByBaseDocId）
  └── P1: 缓存失效（AnswerCacheService 失效）

中 ROI（P1-P2，1-2天）
  ├── P2: HashUtil + ContentHashCache 单元测试
  ├── P2: GraphExecutionService 重规划测试
  ├── P2: Grafana ErrorType 面板
  └── P3: 编译警告清理（HybridDataQueryService + StructuredPrompt + TS）

低 ROI（P2-P3，> 2天）
  ├── P2: Chunk 注册表 + 对账任务（当前数据量小，ROI 低）
  ├── P2: E2E 集成测试全面覆盖
  └── P2: 响应格式统一（破坏性变更）
```
