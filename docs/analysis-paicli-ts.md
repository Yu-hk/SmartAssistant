# PaiCLI-TS 项目分析 —— 可借鉴点

> 项目：https://github.com/itwanger/paicli-ts
> 定位：TypeScript 终端 AI Agent CLI（12 个提交，最近更新 2026-06-29）
> 对比：SmartAssistant（Java Spring Boot 微服务）

---

## 项目概览

PaiCLI-TS 是一个面向终端的 AI Agent CLI，技术栈为 TypeScript + Node.js，支持：

- DeepSeek LLM（与 SmartAssistant 相同模型）
- ReAct Agent 循环
- 内置工具（读文件、写文件、grep、glob、web search、web fetch 等）
- MCP Server（HTTP / stdio）
- 安全策略（路径围栏、命令黑名单、HITL）
- SQLite 长期记忆

---

## 可借鉴的特性（按 ROI 排序）

### P0：webInputGuard — URL 幻觉守卫（0.3天）

**解决的问题：** LLM 在调用 web search 工具时，经常编造不存在的 URL。例如用户说"查一下 ai.javabetter.cn"，LLM 可能调用 searchWeb 时传入 `ai-javabetter-cn.com` 这样的幻觉域名。

**实现方式（PaiCLI-TS 的做法）：**

```
用户消息: "访问 https://example.com/docs"
LLM 生成的工具参数: { url: "https://example.com/other-page" }
                    ↓
webInputGuard 介入:
  1. extractExplicitWebTargets(用户消息) → [{host: "example.com", path: "/docs"}]
  2. 对比 LLM 的 host vs 用户指定的 host
  3. 不匹配 → buildCorrectedFetchUrl() 修正
  4. 最终执行: { url: "https://example.com/docs" }
```

**项目对应位置：** `GeneralTools.searchWeb()` / `GeneralTools.webFetch()`

**实现方案（~30 行）：**

```java
// GeneralTools.java 中增加 URL 守卫
private String webInputGuard(String userMessage, String llmGeneratedUrl) {
    // 从用户消息中提取显式 URL
    Pattern urlPattern = Pattern.compile("https?://[^\\s<>\"'，。！？；、]+");
    Matcher matcher = urlPattern.matcher(userMessage);
    if (matcher.find()) {
        String userUrl = matcher.group();
        // 如果 LLM 生成的 URL 域名与用户指定的不同 → 修正
        if (!extractHost(userUrl).equals(extractHost(llmGeneratedUrl))) {
            return userUrl; // 用用户的 URL 替换 LLM 的幻觉
        }
    }
    return llmGeneratedUrl;
}
```

---

### P1：AbortSignal 支持 — 真正取消请求（0.5天）

**解决的问题：** 前端 `handleStop()` 目前只设置 `isLoading=false`，但后端 SSE 流和 LLM 推理仍在继续。用户点击停止后，GPU 还在白烧。

**PaiCLI-TS 的做法：** 全链路传递 `AbortSignal`：

```
Agent.run(msg, abortSignal)
  → query({ abortSignal })
    → llmClient.chat(..., { abortSignal })
      → fetch(url, { signal: controller.signal })
```

**项目改造方案：**

**前端 `useChat.ts`：**
```typescript
// 新增 AbortController
const abortControllerRef = useRef<AbortController | null>(null);

const sendMessage = useCallback(async (...) => {
  abortControllerRef.current = new AbortController();
  // EventSource 情况：
  // EventSource 不支持 signal，但可以调用 es.close()
  // fetch 情况：
  const response = await fetch(url, { signal: abortControllerRef.current.signal });
});

const handleStop = useCallback(() => {
  if (abortControllerRef.current) {
    abortControllerRef.current.abort(); // 真正取消请求
  }
  setIsLoading(false);
}, []);
```

**Consumer 端：**
SSE 转发检测连接是否断开，断开时停止转发并释放 LLM 槽位。

---

### P3：MCP Server 模式（1天）

**解决的问题：** 其他 MCP 客户端无法直接调用 SmartAssistant 的工具。

**PaiCLI-TS 的做法：** 将内置工具通过 MCP 协议（HTTP JSON-RPC）暴露出去。

**项目的现有条件：** 已有 `ToolGateway` + `ToolRegistry` + `@Tool` 注解全覆盖。只需加一个 MCP Server 端点，将 `ToolRegistry` 中的工具映射为 JSON-RPC 接口。

**但当前 ROI 不高：** 项目没有外部 MCP 客户端需要调用我们的工具。暂时不实施。

---

## 项目对比总结

| 特性 | PaiCLI-TS | SmartAssistant | 借鉴价值 |
|:----|:---------|:-------------|:--------:|
| webInputGuard | ✅ 实现（100行） | ❌ 无 | **P0 高** |
| AbortSignal 全链路 | ✅ | ⚠️ 前端 handleStop 是空操作 | **P1 中** |
| MCP Server 模式 | ✅ HTTP/stdio | ❌ 无 | P3 低 |
| SQLite 长期记忆 | ✅ | ❌ 不用 SQLite（用 PG+Redis） | 不适用 |
| 路径围栏 | ✅ | ⚠️ ScriptSandbox 仅限脚本 | 不适用 |
| 命令行 CLI | ✅ Commander.js | ✅ React 前端 | 不同场景 |
| 多 Agent 编排 | ❌ 单 Agent | ✅ DAG + Handoff | 超越 |
| 重试/重规划 | ❌ 单次重试 | ✅ ErrorType + 指数退避 | 超越 |

---

## 建议立即实施

### webInputGuard（P0，已分析完毕，可随时实施）

30 行代码改动，针对 LLM 编造 URL 的典型幻觉问题，ROI 最高。需要现在实施吗？
