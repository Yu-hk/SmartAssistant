# DeepSeek 分层模型路由

## 目标

系统通过 DeepSeek 官方兼容 API 统一调用模型，不依赖本地 Ollama。用户原始问题较短时优先低延迟模型，长问题及复杂意图拆解使用能力更强的模型。

## 模型分层

| 档位 | 默认模型 | 使用场景 |
|------|----------|----------|
| LIGHT | `deepseek-flash`（V4.1 Flash） | 短问题、摘要及轻量辅助任务 |
| STANDARD | `deepseek-flash`（V4.1 Flash） | 普通业务问答及 Pro 失败后的降级 |
| HEAVY | `deepseek-v4-pro` | 长问题、多意图拆解及复杂推理 |

DeepSeek 官方将 `deepseek-v4-flash` 保留为临时兼容别名，最新 Flash 的正式 API ID 为 `deepseek-flash`；Pro 当前仍使用 `deepseek-v4-pro`。两种 ID 应按实际 API 提供商分别配置，不可直接套用到阿里云百炼端点。

Router 的任务分析以 Unicode 字符数选择档位。默认阈值为 160，可通过 `DEEPSEEK_PRO_MIN_CHARS` 调整；旧变量 `DEEPSEEK_REASONER_MIN_CHARS` 仍作为兼容回退。

```text
当前问题 < 阈值  ──> V4.1 Flash
当前问题 >= 阈值 ──> V4 Pro ──失败──> V4.1 Flash
```

LIGHT 与 STANDARD 默认指向同一个 Flash 模型。`TieredModelRouter` 会按模型名去重，因此降级时不会重复调用同一模型。

## 配置

```yaml
tier:
  light:
    model: ${DEEPSEEK_LIGHT_MODEL:deepseek-flash}
    temperature: 0.1
  standard:
    model: ${DEEPSEEK_STANDARD_MODEL:deepseek-flash}
    temperature: 0.3
  heavy:
    model: ${DEEPSEEK_REASONING_MODEL:deepseek-v4-pro}
    temperature: 0.2
  degradation-enabled: true

router:
  task-analysis:
    pro-min-chars: ${DEEPSEEK_PRO_MIN_CHARS:160}
```

现有 `DEEPSEEK_LIGHT_MODEL`、`DEEPSEEK_STANDARD_MODEL` 和 `DEEPSEEK_REASONING_MODEL` 变量继续保留，以兼容已有部署配置。

模型 ID 以 [DeepSeek 官方模型与价格说明](https://api-docs.deepseek.com/quick_start/pricing/)为准；更新生产环境前，先用不含真实用户信息的最小请求验证新 ID，并保留原模型配置用于回滚。

## 调用链

1. `TaskAnalysisService` 构造结构化意图拆解 Prompt。
2. `ModelRoutingService` 依据当前问题长度选择 Flash 或 Pro。
3. `AgentLLMGateway` 统一执行超时、重试和熔断。
4. `TaskAnalysisResult` 生成 LangGraph4j DAG，由工作流节点执行。
5. 模型、档位、耗时和节点状态写入 Agent 可视化链路，供管理员页面查看。

旧的关键词快路由、BGE 意图分类、经验命中路由和 `ModelRouterService` 门面已移除，主链不再有与 LLM 意图拆解并行的规则旁路。
