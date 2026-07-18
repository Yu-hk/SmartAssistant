# Tool Registry 改造方案

> 目标：将工具从 Agent 模块中剥离，注册到独立的 Tool Registry 服务，Agent 只保留业务流程编排，通过标签实现域隔离。

---

## 1. 现状与目标对比

| 维度 | 当前架构 | 目标架构 |
|------|---------|---------|
| **工具定义** | 分散在各 Agent 模块的 `@Tool` 方法 | 统一注册到 Tool Registry 服务 |
| **工具发现** | `AiToolRegistry.assemble()` 扫描本模块 Bean | Agent 从 Registry 查询（按标签过滤） |
| **工具执行** | Agent 内部直接调用 | Registry 统一路由转发 |
| **域隔离** | 靠模块边界天然隔离 | 通过标签 + 命名空间显式控制 |
| **版本管理** | 无 | SemVer + 双版本共存 + 废弃流程 |
| **生命周期** | 无 | EXPERIMENTAL → ACTIVE → DEPRECATED → DISABLED |
| **可观测性** | `ToolLogAspect` 模块级 | Registry 一站式看板 |

**架构演进关系**：当前 `common.gateway.tool` 包下的 `ToolRegistry`、`ToolGateway`、`ToolDefinition` 是基础设施层，方案在此基础上构建独立服务层。

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                  Tool Registry (8090)                    │
│                                                         │
│  ┌──────────┐  ┌───────────┐  ┌───────────────────┐   │
│  │ 注册 API  │  │ 查询 API   │  │ 执行 Proxy API    │   │
│  │ POST /   │  │ GET /     │  │ POST /execute     │   │
│  └────┬─────┘  └─────┬─────┘  └────────┬──────────┘   │
│       │              │                 │               │
│  ┌────▼──────────────▼─────────────────▼──────────┐   │
│  │              Core 引擎                          │   │
│  │  ┌──────────┐ ┌──────────┐ ┌─────────────┐     │   │
│  │  │ 注册中心  │ │ 标签引擎  │ │ 版本管理器   │     │   │
│  │  │ (元数据)  │ │ (过滤)   │ │ (SemVer)   │     │   │
│  │  └──────────┘ └──────────┘ └─────────────┘     │   │
│  │  ┌──────────┐ ┌──────────┐ ┌─────────────┐     │   │
│  │  │ 健康检查  │ │ 审计日志  │ │ 依赖追踪    │     │   │
│  │  └──────────┘ └──────────┘ └─────────────┘     │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
          ▲                        ▲
          │ 注册(REST)              │ 查询+触发(REST)
          │                        │
┌─────────┴──────────┐   ┌────────┴───────────────┐
│  工具提供方          │   │  Agent 模块             │
│  (各模块 @Tool Bean) │   │  (Order/Product/General)│
└────────────────────┘   └────────────────────────┘
```

**关键改动点**：

| 组件 | 改动方式 | 说明 |
|------|---------|------|
| `smart-assistant-tool-registry` | **新增服务** | 独立服务，端口 8090 |
| `ToolDefinition` | 扩展 | 新增 `tags`、`version`、`status`、`ownerTeam`、`deprecatedBy`、`sunsetDate` |
| `common.ToolRegistry` | 保留 + 扩展 | 本地缓存副本，定时从 Registry 服务同步 |
| `ToolGateway` | 保留 + 扩展 | 增加远程执行路由（Registry 作为执行代理） |
| `ToolGroupManager` | 保留 | 与 Registry 标签系统融合 |
| 各 AgentConfig | **简化** | 去掉 `AiToolRegistry.assemble()`，改为从 Registry 查询 |

---

## 3. 数据模型

### 3.1 扩展后的 ToolDefinition

```java
public record ToolDefinition(
    // ——— 已有字段 ———
    String name,                    // 工具名称（唯一，建议命名空间前缀：order.createOrder）
    String description,             // 工具描述（LLM 通过它决定是否调用）
    ToolRiskLevel riskLevel,        // 风险等级
    Duration timeout,               // 执行超时
    boolean needsApproval,          // 是否需要审批
    int maxRetries,                 // 最大重试次数
    int rateLimit,                  // 每秒允许调用次数
    String[] scopes,                // 允许访问的 Scope（保留现有鉴权体系）

    // ——— 新增字段 ———
    String[] tags,                  // 标签列表，用于域隔离 [ORDER, READ_ONLY]
    String version,                 // 语义版本号 "1.2.0"
    ToolStatus status,              // 生命周期状态
    String namespace,               // 命名空间 "order"
    String ownerTeam,               // 归属团队
    String endpoint,                // 工具执行端点（Registry 转发目标）
    String deprecatedBy,            // 替代工具名（DEPRECATED 时设置）
    String sunsetDate,              // 下线日期（DEPRECATED 时设置）
    long callCount30d,              // 近 30 天调用量
    double avgLatencyMs,            // 平均延迟
    double errorRate30d             // 近 30 天错误率
) {
    // 快捷方法
    public boolean hasTag(String tag) { ... }
    public boolean isActive() { return status == ToolStatus.ACTIVE; }
    public boolean isDeprecated() { return status == ToolStatus.DEPRECATED; }
}
```

### 3.2 新增 ToolStatus 枚举

```java
public enum ToolStatus {
    EXPERIMENTAL,   // 实验期：仅授权 Agent 可见
    ACTIVE,         // 稳定可用
    DEPRECATED,     // 已废弃：调用仍可用，响应含警告
    DISABLED,       // 已停用：调用被拦截
    REMOVED         // 已移除：仅保留审计记录
}
```

### 3.3 预定义标签体系

| 标签分类 | 标签值 | 说明 |
|---------|-------|------|
| **域标签** | `ORDER`、`PRODUCT`、`GENERAL`、`RECOMMEND` | 按业务域划分，Agent 只拉取本域工具 |
| **行为标签** | `READ_ONLY`、`DESTRUCTIVE`、`NETWORK`、`FILE_SYSTEM` | 执行行为特征，用于权限控制 |
| **性能标签** | `SLOW`、`HEAVY`、`LIGHTWEIGHT` | 预期性能特征，用于 Agent 调度决策 |
| **协议标签** | `LOCAL`、`MCP`、`OPENAPI` | 工具来源协议，用于健康检查策略 |

---

## 4. API 设计

### 4.1 工具注册

```http
POST /api/tools/register
Content-Type: application/json

{
  "name": "order.createOrder",
  "description": "创建新订单",
  "riskLevel": "HIGH",
  "timeout": "PT15S",
  "needsApproval": true,
  "maxRetries": 1,
  "rateLimit": 10,
  "scopes": ["order-service"],
  "tags": ["ORDER", "DESTRUCTIVE"],
  "version": "1.0.0",
  "status": "ACTIVE",
  "namespace": "order",
  "ownerTeam": "order-team",
  "endpoint": "http://order-service:8085/internal/tool/order.createOrder"
}
```

响应：
```json
{
  "code": 0,
  "message": "注册成功",
  "data": { "name": "order.createOrder", "version": "1.0.0" }
}
```

### 4.2 工具查询（按标签过滤）

```http
GET /api/tools?tags=ORDER,READ_ONLY&status=ACTIVE

响应：
{
  "code": 0,
  "data": [
    {
      "name": "order.queryOrder",
      "description": "查询订单详情",
      "riskLevel": "READ",
      "tags": ["ORDER", "READ_ONLY"],
      "version": "1.0.0"
    },
    ...
  ]
}
```

### 4.3 工具执行

```http
POST /api/tools/execute
Content-Type: application/json

{
  "toolName": "order.createOrder",
  "agentId": "order-agent",
  "parameters": {
    "userId": "U1001",
    "productName": "iPhone 15"
  },
  "idempotencyKey": "req-xyz-001"
}
```

响应：
```json
{
  "code": 0,
  "data": { "orderId": "ORD20260710001", "status": "CREATED" },
  "execution": {
    "latencyMs": 234,
    "version": "1.0.0"
  }
}
```

### 4.4 健康检查

```http
GET /api/tools/health

响应：
{
  "code": 0,
  "data": {
    "total": 12,
    "healthy": 11,
    "degraded": 1,
    "deprecated": 2,
    "tools": [
      { "name": "order.queryOrder", "status": "OK", "errorRate": 0.01, "avgLatencyMs": 45 },
      { "name": "order.createOrder", "status": "DEGRADED", "errorRate": 0.08, "avgLatencyMs": 1200 }
    ]
  }
}
```

### 4.5 版本废弃

```http
POST /api/tools/deprecate
Content-Type: application/json

{
  "name": "order.createOrder_v1",
  "deprecatedBy": "order.createOrder_v2",
  "sunsetDate": "2026-08-10",
  "reason": "参数格式变更：productId 改为必填"
}
```

### 4.6 依赖查询

```http
GET /api/tools/order.createOrder/dependents

响应：
{
  "code": 0,
  "data": [
    { "agentId": "order-agent", "lastCalled": "2026-07-10T09:30:00", "callCount30d": 1247 }
  ]
}
```

---

## 5. Agent 侧改造

### 5.1 当前模式

```java
// OrderAgentConfig.java — 当前
@Bean
public SmartReActAgent orderAgent(
        OrderTools orderTools,
        OrderMemoryTool memoryTool,
        ...
) {
    List<ToolCallback> tools = AiToolRegistry.assemble(orderTools, memoryTool, ...);
    return new SmartReActAgent(prompt, tools);
}
```

### 5.2 目标模式

```java
// OrderAgentConfig.java — 目标
@Bean
public SmartReActAgent orderAgent(
        ToolRegistryClient registryClient  // ← 注入 Registry 客户端
) {
    // 从 Registry 查询本域标签的可用工具
    List<ToolCallback> tools = registryClient.getToolCallbacks("ORDER");

    // Agent 只保留流程编排
    return new SmartReActAgent(prompt, tools);
}
```

### 5.3 Registry 客户端（common 模块提供）

```java
/**
 * Tool Registry 客户端 SDK。
 * 供各 Agent 模块通过 HTTP 查询 Registry 服务。
 * 内置本地缓存（30s TTL），避免每次会话都远程调用。
 */
public class ToolRegistryClient {

    private final String registryUrl;       // Registry 服务地址
    private final Cache<String, List<ToolCallback>> cache;  // 本地缓存

    /**
     * 按标签获取指定域的 ToolCallback 列表。
     * 结果直接可用于 SmartReActAgent.withPreset()。
     */
    public List<ToolCallback> getToolCallbacks(String tag) {
        return cache.get(tag, () -> {
            // GET /api/tools?tags={tag}&status=ACTIVE
            // 将返回的 ToolDefinition 动态构造为 ToolCallback
            return fetchAndBuild(tag);
        });
    }

    /**
     * 获取当前工具列表（用于注入 system prompt）。
     */
    public String getToolDescriptions(String tag) { ... }

    /**
     * 刷新缓存。
     */
    public void refresh() { cache.invalidateAll(); }
}
```

### 5.4 Tag 与当前 ToolGroup 的关系

`ToolGroupManager` 的组概念仍然保留，与 Registry 标签的关系如下：

| ToolGroup 概念 | Registry 标签 | 说明 |
|---------------|--------------|------|
| `group("order")` | `tag: ORDER` | 两者等价，只是管理位置不同 |
| `required(true)` | `status: REQUIRED`（可新增） | 必选组标识 |
| `activate("order")` | Agent 启动时通过 `tags=ORDER` 拉取 | 激活变成了查询过滤 |

**迁移策略**：ToolGroupManager 保留作为 Agent 内部的二级过滤机制。Agent 从 Registry 拉取到本域工具后，仍可通过 ToolGroupManager 做更细粒度的按需激活。

---

## 6. 标签隔离机制

### 6.1 默认隔离

```
Agent 启动时
  └→ registryClient.getToolCallbacks("ORDER")
       └→ GET /api/tools?tags=ORDER&status=ACTIVE
            └→ 返回所有 tag 包含 ORDER 且状态为 ACTIVE 的工具
                 └→ LLM 只知道这些工具存在
```

**关键保障**：其他域的工具对 LLM 完全不可见，不存在误选可能。

### 6.2 交叉授权

```java
// 场景：Order Agent 需要查询商品信息
// 方式 1：注册时声明交叉标签
ToolDefinition(
    name: "product.queryProductInfo",
    tags: ["PRODUCT", "ORDER"],  // 同时拥有两个域标签
    ...
)

// 方式 2：Agent 启动时拉取多个标签
List<ToolCallback> tools = registryClient.getToolCallbacks("ORDER");
tools.addAll(registryClient.getToolCallbacks("PRODUCT_READ"));  // 只读子标签
```

### 6.3 执行层拦截

`ToolGateway` 在 `execute()` 阶段对 Agent 身份做二次校验：

```java
// ToolGateway.execute() — 增强后的鉴权逻辑
if (tags != null && tags.length > 0) {
    // 校验调用方是否在工具标签的白名单中
    boolean authorized = Arrays.asList(tags).contains(agentScope);
    if (!authorized) {
        throw new ToolExecutionException(toolName, PERMISSION_DENIED,
            "工具 " + toolName + " 不允许 agent=" + agentScope);
    }
}
```

---

## 7. 实现阶段规划

### Phase 0：基础设施扩展（1-2 天）

| 任务 | 文件 | 改动 |
|------|------|------|
| 扩展 `ToolDefinition` | `common/gateway/tool/ToolDefinition.java` | 新增 tags, version, status, namespace, ownerTeam, endpoint, deprecatedBy, sunsetDate, callCount30d, avgLatencyMs, errorRate30d |
| 新增 `ToolStatus` | `common/gateway/tool/ToolStatus.java` | 新建枚举 |
| 给 `ToolDefinition` 新增快捷方法 | `ToolDefinition.java` | `read()/write()/highRisk()` 增加 tags 参数重载 |
| 扩展 `ToolGateway` | `common/gateway/tool/ToolGateway.java` | 增加标签鉴权逻辑 |

### Phase 1：Tool Registry 服务（3-4 天）

| 任务 | 说明 |
|------|------|
| 新建 `smart-assistant-tool-registry` 模块 | Spring Boot 项目，端口 8090 |
| 注册 API | `POST /api/tools/register`，含数据校验 + 命名空间去重 |
| 查询 API | `GET /api/tools`，支持 `tags`、`status`、`namespace` 过滤 |
| 执行代理 API | `POST /api/tools/execute`，转发到工具实际 endpoint |
| 废弃 API | `POST /api/tools/deprecate`，含依赖检查 |
| 健康检查 API | `GET /api/tools/health`，聚合各工具的健康状态 |
| 依赖追踪 | 每次调用记录 agentId，支持 `GET /api/tools/{name}/dependents` |
| 数据存储 | H2 或嵌入数据库（初期），后续可迁移到 PostgreSQL |

### Phase 2：Registry 客户端 SDK（2 天）

| 任务 | 文件 | 说明 |
|------|------|------|
| 新建 `ToolRegistryClient` | `common/tool/client/ToolRegistryClient.java` | HTTP 客户端 + 本地缓存 |
| 注册自动配置 | `common/.../ToolRegistryAutoConfiguration.java` | 从配置读取 Registry URL |
| 动态 ToolCallback 构造 | 在 client 内实现 | 将 Registry 返回的 ToolDefinition 转换为 ToolCallback |
| fallback 策略 | 缓存不可用时降级行为 | Registry 不可用时，使用本地缓存的最后一次快照 |

### Phase 3：Agent 模块改造（2-3 天）

| Agent | 改动 |
|-------|------|
| `GeneralAgentConfig` | 去掉 `AiToolRegistry.assemble()`，改为 `registryClient.getToolCallbacks("GENERAL")` |
| `OrderAgentConfig` | 同上，标签 `ORDER` |
| `ProductAgentConfig` | 同上，标签 `PRODUCT` |

### Phase 4：工具搬迁 & 验证（2 天）

| 任务 | 说明 |
|------|------|
| 将所有 `@PostConstruct + toolRegistry.register()` 改为调用 Registry 注册 API | 注册一致 |
| 验证各 Agent 的 ToolCallback 列表正确 | 标签过滤 + 域隔离正确性 |
| 验证 ToolGateway 标签鉴权 | 越权调用拦截 |
| 验证废弃流程 | 下线不中断已有 Agent |
| 清理废弃代码 | 删除各模块中不再需要的 `AiToolRegistry.assemble()` 调用 |

### Phase 5：运维工具（1 天）

| 任务 | 说明 |
|------|------|
| Registry CLI | `registry-cli list / deprecate / health / dependents` |
| 管理面板 | 简单的 Web UI（可选，初期用 API + CLI 替代） |
| 告警规则 | 工具错误率 > 5% 自动通知 ownerTeam |

---

## 8. 当前项目的复用关系

| 现有基础设施 | 在方案中的角色 |
|------------|--------------|
| `ToolRegistry` (common) | 保留，作为 Registry 服务的内存元数据存储基础 |
| `ToolGateway` (common) | 保留并扩展：增加标签鉴权 + 远程执行路由 |
| `ToolDefinition` (common) | 扩展：新增 tags/version/status 等字段 |
| `AiToolRegistry` (common) | **废弃**：由 `ToolRegistryClient` 替代 |
| `ToolGroupManager` (common) | 保留：作为 Agent 内部二级过滤 |
| `ToolGroup` (common) | 保留：作为 Agent 内部分组 |
| `ToolLogAspect` (common) | 保留：增加 Registry 维度的标签注入 |
| `ToolResult` (common) | 保留：执行结果格式标准化 |
| `RoutingToolChecker` (router) | 保留：检查维度从"关键工具是否注册"升级为"健康状态 + 标签匹配" |

---

## 9. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| Registry 单点故障 | Agent 无法获取工具列表 | 客户端缓存 + 本地快照降级（30s TTL） |
| 工具执行多一跳 | 增加网络延迟 | Registry 执行转发为可选模式（也可直调）；
| 版本兼容混乱 | Agent 调用已不兼容的工具 | SemVer 强制 + major 变更走废弃流程 |
| 标签命名不一致 | 跨团队工具难以归类 | 预定义标签体系 + 注册时校验 |
| 缓存不一致 | Agent 用到了已废弃的工具 | 主动推送失效事件 + 短 TTL |

---

## 10. 迁移示例：Order Tools

### 当前

```java
// OrderTools.java — 当前
@Component
public class OrderTools {
    @Tool(name = "createOrder", description = "创建新订单")
    public String createOrder(...) { ... }

    @PostConstruct
    public void init() {
        toolRegistry.register(ToolDefinition.highRisk("createOrder", "创建新订单", true));
    }
}
```

### 目标

```java
// OrderTools.java — 目标（工具定义仍在 Order 模块，但注册到 Registry 服务）
@Component
public class OrderTools {
    @Tool(name = "order.createOrder", description = "创建新订单")
    public String createOrder(...) { ... }

    @PostConstruct
    public void init() {
        // 注册到 Registry 服务，而非本地内存
        registryClient.register(
            ToolDefinition.builder()
                .name("order.createOrder")
                .description("创建新订单")
                .riskLevel(ToolRiskLevel.HIGH)
                .tags("ORDER", "DESTRUCTIVE")
                .version("1.0.0")
                .endpoint("http://order-service:8085/internal/tool/order.createOrder")
                .build()
        );
    }
}
```

### Agent 配置

```java
// OrderAgentConfig.java — 目标
@Bean
public SmartReActAgent orderAgent(
        ToolRegistryClient registryClient,
        ToolGroupManager groupManager) {

    List<ToolCallback> tools = registryClient.getToolCallbacks("ORDER");
    return new SmartReActAgent(prompt, tools);
}
```

---

## 11. 总结：架构变化一览

| 层面 | 从 | 到 |
|------|----|----|
| 工具存储 | 本地 ConcurrentHashMap | 独立服务 + 客户端缓存 |
| Agent 获取工具 | `AiToolRegistry.assemble()` | `registryClient.getToolCallbacks(tag)` |
| 域隔离 | 模块边界 + 信任 | 标签 + 命名空间 + 执行鉴权三重保障 |
| 版本管理 | 无 | SemVer + 废弃流程 + 双版本共存 |
| 可观测性 | `ToolLogAspect` 模块级 | Registry 一站式 + 调用链追踪 |
| 工具变更影响 | 改代码、部署 Agent | 注册/废弃/升级，Agent 自动感知 |
| 运维 | 翻日志查各模块 | `registry-cli` 一站式管理 |
