# 聊天框语音输入

## 接入边界

浏览器点击麦克风 → 显式授权录音 → 结束录音 → 本地转换为 16 kHz / 单声道 / PCM16 WAV → Gateway JWT 校验 → Consumer 的 Qwen ASR 适配器 → 识别文字追加到草稿 → 用户核对后发送 → 原有聊天、情绪/画像、MQ 和 Router 流程。

- 默认模型 `qwen3-asr-flash`，使用百炼 OpenAI 兼容协议的 `input_audio`，不是让聊天模型猜测语音内容。
- 无需新建微服务，也不把转写任务加入业务 MQ。转写成功不代表用户已经发起客服任务。
- 首页和会话页共用语音入口。保留已有草稿，识别文字作为纯文本追加，不自动发送、不执行工具。
- 用户可以随时取消；切换会话、会话关闭、卸载或页面隐藏时释放麦克风、取消网络请求并忽略迟到响应。
- 单次最长 60 秒；浏览器不同录音编码先本地统一为 WAV，服务端严格核验 WAV 头、实际长度、采样率及静音。
- 请求仅接受录音字节，不接受任意远程音频 URL，避免用户控制服务端抓取目标。
- 服务端不保存音频或转写到数据库、Redis、会话日志；识别文字只有用户发送后进入现有聊天记录。音频会传到百炼处理，供应商的数据处理规则仍适用。

## 配置和部署

Consumer 环境变量（不允许放入 `VITE_*` 或前端）：

```dotenv
SPEECH_ENABLED=true
SPEECH_MODEL=qwen3-asr-flash
SPEECH_API_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
# SPEECH_API_KEY 使用对应地域的百炼 Key；不设置则复用 DASHSCOPE_API_KEY。
```

默认开关关闭，未配置密钥时返回明确的不可用状态，前端不请求麦克风。密钥必须具备该模型的访问权限和可用额度，普通 DeepSeek 聊天模型密钥不能用于此接口。也可以把 base URL 改为百炼同地域业务空间专属域名。

使用普通百炼按量付费密钥（新版前缀 `sk-ws-`），不要将个人 Token Plan 的 `sk-sp-` 密钥配置到网站后端：个人套餐有调用场景限制，且套餐与通用 API 地址不能混用。`qwen-audio-3.0-asr-flash` 与本版 `qwen3-asr-flash` 是不同接口契约，不能只替换模型名。密钥应以私有环境变量或权限为 `0600` 的服务端配置注入，不提交仓库，不暴露给前端。

部署涉及 **Consumer、Gateway、前端和 Nginx 配置**。必须同步：

1. Consumer 注入开关、密钥和模型配置；Docker Compose 已补充对应变量。
2. Gateway 增加 `/api/speech/**` 及带 `/assistant` 前缀的转发，保留 JWT 认证；Consumer 8082 不得对公网开放。
3. Nginx 的 `Permissions-Policy` 从 `microphone=()` 改为 `microphone=(self)`。保持 HTTPS，浏览器仍需用户单独允许麦克风权限。
4. 重建前端。检查真实入口响应头，避免只更新源码而线上仍禁止麦克风。

## 接口及限制

- `GET /api/speech/capabilities`：登录后查询 `{enabled,maxSeconds}`，不返回密钥。
- `POST /api/speech/transcriptions`：`Content-Type: audio/wav`，原始二进制请求体。返回 `{text,model,durationSeconds,usage}`，不创建业务对话。
- 最多 `1,920,044` 字节，按有界流读取，也覆盖无 Content-Length 的请求；最短 0.3 秒。
- 单 Consumer 实例最多 4 个识别任务，同用户最多 1 个在途任务、10 秒内最多接受 1 次有效音频请求；Gateway 继续执行原有分布式限流。多实例部署时这些额外限制是实例级，不应当作全局计费配额。
- 连接超时 5 秒、上游读取超时 45 秒；前端转码及识别总等待 60 秒，授权等待 30 秒。取消无法保证供应商已经开始的调用不计费，但不会回填迟到文字。
- 401 登录失效、413 超出体积、422 空语音、429 限流、503 未启用、502/504 模型异常/连接超时。供应商错误正文和密钥不会返回前端。
- `usage` 只返回模型真实提供的数字，缺失为 null。本版尚未将独立 ASR 用量并入客服会话 Token 合计，避免混淆识别和回答的用量。

## 验证

```sh
npm --prefix frontend run test:voice
npm --prefix frontend run build
mvn -B -pl smart-assistant-consumer,smart-assistant-gateway -am test -Dtest=SpeechRecognitionServiceTest,SpeechControllerTest,GlobalJwtAuthFilterTest -Dsurefire.failIfNoSpecifiedTests=false
```

本轮本地验证：前端 17 项、Consumer 13 项、Gateway 22 项测试全部通过，TypeScript/Vite 生产构建通过。Edge 无头浏览器使用合成麦克风和模拟模型响应，验证真实 MediaRecorder → WAV 转码、保留原有草稿、不自动发送，以及桌面/390px 手机布局；无页面脚本错误。

回归覆盖 WAV/大小/静音校验、模型请求协议和用量、缺少配置、错误脱敏、限流、鉴权、录音取消、权限拒绝/迟到授权、切换会话、迟到转写、自动停止、草稿保留。

2026-09-15 已部署到 `https://xiaoyuai.cloud`，使用北京地域普通百炼密钥完成真实模型预检及 15 项线上验收。Edge 无头浏览器使用合成麦克风，经过实际录音、转码、公网鉴权、Consumer、真实 ASR 和文字回填，无 API 模拟；桌面及 390px 手机布局均通过。合成音频“请查询我的订单物流。”识别正确；循环音频录音末尾的重复片段如实保留在测试结果中。尚未验证真人麦克风或 Safari/iOS 实机，详见 [部署验收记录](voice-input-deployment-verification.md)。

验收时建议朗读“请查询我的订单物流”“AirPods Pro 有货吗”“预算 2000 元，推荐一款耳机”，核对文字后手动发送；同时测试拒绝权限、取消、切换会话和手机浏览器。订单号、数字和商品名需要人工核对。

接口依据：[百炼 Qwen-ASR 官方文档](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)。
