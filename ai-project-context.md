# SmartAssistant — AI 项目指令手册

> 本文件为 AI 编码助手提供项目上下文，随代码 PR 同步更新。
> PromptManager.loadProjectContext() 自动加载此文件注入 System Prompt。

## 1. 技术栈

- **语言**: Java 21（虚拟线程、record、switch 表达式）
- **框架**: Spring Boot 3.4.8 + Spring AI 2.0.0
- **构建**: Maven 多模块
- **数据库**: PostgreSQL 16（主库）+ Milvus 2.4（向量库）+ Redis 7（缓存/会话）
- **注册中心**: Nacos 2.3
- **前端**: React 18 + Vite + MUI + Tailwind CSS
- **CI/CD**: GitHub Actions（评测门禁 + 安全扫描）

## 2. 微服务清单

| 服务 | 端口 | 职责 |
|------|------|------|
| Router | 8083 | 意图路由、任务分发 |
| Consumer | 8082 | MCP Agent、混合查询 |
| Order | 8085 | 订单查询、退款、物流 |
| Product | 8084 | 商品搜索、详情、推荐 |
| Tool Registry | 8088 | 工具注册中心、兼容性检查 |
| Gateway | 8081 | API 网关、鉴权 |
| Embedding | 8091 | 向量嵌入服务 |
| Recommend | 8088 | 推荐引擎 |
| User | 8086 | 用户管理、画像 |

## 3. 编码规范

### 3.1 工具开发

- 使用 `@Tool` 注解 + `ToolDefinition.builder()` 注册工具元数据
- 所有工具调用必须通过 `ToolGateway.execute()` 执行（鉴权/熔断/超时/审计/脱敏）
- 高风险工具（退款/删除/支付）设置 `needsApproval=true`
- 工具版本号遵循语义化版本 `MAJOR.MINOR.PATCH`，BREAKING 变更必须升 MAJOR
- capabilities 标签使用预定义枚举值（read-only/mutate-state/network-call/payment/data-access/ai-inference）

### 3.2 Prompt 管理

- 所有 System Prompt 文件化管理（`src/main/resources/prompts/` 目录），禁止硬编码
- 使用 `PromptBuilder` 分层组装：base → project-context → service → sections → dynamic
- 模板变量使用 `${varName}` 格式

### 3.3 代码风格

- 遵循 Google Java Style Guide
- 使用 Lombok 注解简化 POJO（@Getter/@Setter/@Builder）
- 公共方法添加 Javadoc
- 日志前缀格式：`[ClassName]`（如 `[ToolGateway]`、`[RegistryService]`）

## 4. 安全红线

1. **禁止**在 Prompt 中包含数据库连接信息、API 密钥或 Token
2. **禁止**工具返回原始密码、Token 或完整银行卡号（使用 SanitizeHook 脱敏）
3. SQL 工具仅允许 SELECT 操作，禁止 DDL/DML
4. 高风险工具（HIGH 风险等级）必须设置 `needsApproval=true`
5. DISABLED/REMOVED 状态的工具不可执行（ToolStatusHook 拦截）
6. PR 标题禁止包含危险字符（反引号、$、;、|、&）
7. CVSS ≥ 9.0 的依赖漏洞阻断构建

## 5. 测试规范

- 单元测试覆盖率 ≥ 80%
- Golden Suite 评测门禁：PR 必须通过 `GoldenSuiteEvalGateTest`
- 测试类命名：`*Test`（单元）/ `*IT`（集成）
- Mock 外部依赖（Redis/PG/Milvus），CI 中纯内存运行

## 6. Hook 机制

ToolGateway 支持 4 个执行钩子（按 @Order 排序）：

| Hook | @Order | 职责 |
|------|--------|------|
| ToolStatusHook | 10 | 状态拦截（DISABLED/REMOVED 拒绝，DEPRECATED 警告） |
| ApprovalHook | 20 | 审批检查（needsApproval=true 异步先拒绝） |
| SanitizeHook | 30 | 结果脱敏（手机/身份证/银行卡） |
| AuditHook | 100 | 结构化审计日志 |

新增 Hook 时 @Order 值避开 10/20/30/100，预留 40-90 区间。
