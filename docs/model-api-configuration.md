# 模型 API 配置

## 模型分工

| 模型 | 是否必需 | 使用位置 |
| --- | --- | --- |
| `deepseek-v4-flash` | 是 | 通用/订单/商品 Agent、Router、对话摘要、偏好与关键词提取、Text-to-SQL |
| `deepseek-v4-pro` | 否 | Router 的 HEAVY 档位，仅处理退款投诉、多跳规划等复杂推理 |
| BGE ONNX | 是（RAG 开启时） | 本地向量化与重排；它不是聊天大模型，不通过 DeepSeek API |

项目的聊天推理统一通过 Spring AI 的 DeepSeek ChatModel 直连 API。生产运行时不再安装、启动或访问 Ollama。

## 环境变量

```dotenv
DEEPSEEK_API_KEY=填写真实密钥
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_CHAT_MODEL=deepseek-v4-flash
DEEPSEEK_LIGHT_MODEL=deepseek-v4-flash
DEEPSEEK_REASONING_MODEL=deepseek-v4-pro
```

若只希望使用一个模型，可将 `DEEPSEEK_REASONING_MODEL` 也设置为 `deepseek-v4-flash`。部署脚本会在启动任何模型服务前校验 `DEEPSEEK_API_KEY`，避免服务启动后才在对话阶段报错。

## 服务映射

- Consumer：摘要、用户偏好、关键词和个性化处理。
- Router：意图路由、任务拆解、结果合并、复杂度分档。
- General / Order / Product：带工具调用的专业 Agent。
- Order Text-to-SQL：复用 `lightChatModel`，不再自行调用 `/api/generate`。

视觉 OCR 的旧 Ollama 备选实现仍保留在公共库中，但未接入生产部署；默认 OCR/RAG 链路不会因此要求 Ollama。
