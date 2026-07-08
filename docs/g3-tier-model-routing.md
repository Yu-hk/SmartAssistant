# G3 Tier 多模型路由设计文档

> 对标文章④《腾讯混元架构面经》「多模型路由降级 Tier1/2/3 + 动态路由 + 平滑降级」

## 背景

SA 原全量固定 `deepseek-r1:7b` 模型，无分级无降级。文章②④反复指出：真实生产环境应根据查询复杂度动态分配不同档位的模型，并在模型故障时平滑降级，避免单点故障导致请求整体失败。

## 设计原则

1. **统一接入层**：路由逻辑沉淀到 common 中台，所有服务（Router/Consumer/General 等）可复用
2. **轻量决策**：复用 `QueryComplexityClassifier` 纯规则分类（<1ms），不引入额外 LLM 调用
3. **平滑降级**：选定档位失败时自动沿降级链兜底，绝不让单档位故障导致请求整体失败
4. **意图覆盖**：部分意图（退款投诉等）超过复杂度分类，需强制升档
5. **可观测性**：Micrometer 指标暴露档位分布、降级次数、时延

## 架构

```
请求 → QueryComplexityClassifier(规则<1ms)
         │ SIMPLE ───→ LIGHT  (qwen2.5:3b)
         │ MEDIUM  ──→ STANDARD (deepseek-r1:7b)
         │ COMPLEX ──→ HEAVY  (deepseek-r1:7b/云端)
         │
         ├ 意图覆盖: intentOverrides(Map<String,ModelTier>)
         │
         └ → 从选定档位沿降级链调用:
               HEAVY → STANDARD → LIGHT (LIGHT 永为兜底)
               ├ 成功 → 返回 TierSelection
               └ 全失败 → ModelTierUnavailableException
```

## 组件设计

### 档位枚举（`ModelTier`）

```java
public enum ModelTier {
    LIGHT(1, "light", "本地轻量模型"),      // Tier1
    STANDARD(2, "standard", "本地标准模型"),  // Tier2
    HEAVY(3, "heavy", "强模型");            // Tier3
    public ModelTier lower() { ... }  // HEAVY→STANDARD→LIGHT→LIGHT
}
```

### 注册表（`TierModelRegistry`）

持有每个档位的 `ChatModel` 实例（非 Spring Bean），提供：
- `get(tier) → ChatModel | null`
- `fallbackChain(from) → List<ModelTier>`：从指定档位到 LIGHT 的完整降级链

### 选项覆盖委托（`DelegatingOptionsChatModel`）

同底层 `OllamaChatModel` 以不同 model/temperature 参数复用：

```java
class DelegatingOptionsChatModel implements ChatModel {
    ChatModel delegate;
    ChatOptions options;
    call(Prompt) → delegate.call(new Prompt(instructions, options));
}
```

### 核心路由器（`TieredModelRouter`）

| 方法 | 职责 |
|------|------|
| `selectTier(query, intentTag) → ModelTier` | 按复杂度 + 意图覆盖选定档位 |
| `selectModel(query, intentTag) → ChatModel` | 选定档位的模型（无降级） |
| `call(prompt, query, intentTag) → TierSelection` | ⭐核心：带平滑降级调用 |

### 降级策略

```
选定 HEAVY 失败 → 尝试 STANDARD(成功) → 返回 TierSelection(degraded=true)
选定 HEAVY 失败 → STANDARD 失败 → LIGHT 成功 → 返回 TierSelection(degraded=true)
全失败 → 抛出 ModelTierUnavailableException
关闭降级 → 仅在选定档位尝试，失败即抛异常
```

### 可观测性指标

| Metric | Type | Tags |
|--------|------|------|
| `model.tier.selections` | Counter | tier, degraded |
| `model.tier.degradations` | Counter | from_tier, to_tier |
| `model.tier.latency` | Timer | tier, degraded |

## 集成点

### 1. `RouterService.inlineFallback()`

原固定使用 `lightChatClient`（LIGHT 档）做本地兜底。
G3 改造后：按复杂度选档 + 平滑降级。
无 `TieredModelRouter` 时退回原 `lightChatClient` 行为。

### 2. `ModelRouterService`

原实现依赖 `defaultChatModel`/`heavyChatModel` 两个从未定义的 Spring Bean（启动即崩），且自身从未被任何调用方引用。
G3 改造后：死代码修复，委托 common `TieredModelRouter`，对外保留 `selectModel(String)` / `getModelTierName(String)` API。

### 3. `application.yml`

新增 `tier.*` 配置前缀，绑定 `TieredModelRouterProperties`。

## 配置示例

```yaml
tier:
  light:
    model: qwen2.5:3b
    temperature: 0.1
  standard:
    model: deepseek-r1:7b
    temperature: 0.3
  heavy:
    model: deepseek-r1:7b
    temperature: 0.2
  intent-overrides:
    refund_complaint: HEAVY
    travel_plan: HEAVY
  degradation-enabled: true
```

## 与文章④对照

| 文章④要求 | SA G3 实现 |
|-----------|-----------|
| Tier 分级（小/中/大模型） | LIGHT(qwen2.5:3b) / STANDARD(deepseek-r1:7b) / HEAVY(deepseek-r1:7b) |
| 动态路由（按复杂度选档） | `QueryComplexityClassifier` 规则 → SIMPLE/MEDIUM/COMPLEX → 档位映射 |
| 平滑降级 | `TieredModelRouter.call()` 降级链 |
| 失败熔断不扩散 | LIGHT 永为兜底，全失败抛 `ModelTierUnavailableException` |
| 可观测 | Micrometer `model.tier.*` 三指标 |
| 意图维度覆盖 | `intentOverrides` 配置 Map（如 refund_complaint→HEAVY） |
| 云端/本地弹性 | 配置热更即可切换，heavy 可指向 DeepSeek/GPT |
