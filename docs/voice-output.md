# 配套语音回复（第一版）

## 用户流程

语音输入 → ASR 文字草稿 → 用户核对并发送 → 原有客服任务完成 → Consumer 获取该用户的完整最终回复 → 百炼 TTS → 浏览器播放。

- 语音输入仍然不会自动发送、执行工具或创建订单。
- 输入区提供“语音回复”开关，默认开启，仅作用于语音草稿确认发送的提问；普通文字提问不自动播报。
- 完成的回复提供“朗读回复”，支持暂停、继续和停止；关闭自动播报后仍可手动朗读。
- 浏览器禁止自动播放时显示“播放语音”，需要用户点击；不绕过浏览器权限。
- 开始录音、发送下一条问题、切换会话、页面隐藏或卸载时停止当前播放并取消等待，迟到的响应不会突然播放。打开已有会话不自动重播完成的历史回复。
- 仅播报本轮完整回复，包括正常的订单号/城市等澄清问题，不读执行步骤、工具参数、提示词或失败任务。语音失败只影响播放，不修改文字答案。

## 服务边界与隐私

无需新增微服务或业务 MQ：使用 Consumer 中独立的 `SpeechSynthesisService`，沿用 Gateway 已有 `/api/speech/**` JWT 鉴权路由。

`POST /api/speech/syntheses` 只接收 `{requestId}`，不接收任意待合成文本或下载 URL。服务端通过 `ChatDispatchStore.ownedCommand` 校验请求所属用户，要求任务状态为 `COMPLETED`，并读取原始最终结果。不能使用审计日志中的 `response_summary`，该字段截断为 500 字，可能丢失金额条件或结论。

本版完整结果依赖现有 Redis 调度记录，TTL 为 24 小时。记录过期或清理后不再支持合成，只提示查看文字；数据库中的历史文字仍可阅读，不用截断摘要冒充完整播报。

回复会发送至百炼合成，供应商数据处理规则适用。服务端不持久化音频，不向前端暴露密钥或供应商签名下载地址；音频响应为 `audio/mpeg`、`Cache-Control: private, no-store`。浏览器仅在当前会话内存保留最近 3 份音频（每份不超过 8 MiB），切换会话或离开页面释放，不写浏览器存储。开关偏好单独保存在 localStorage。

## 配置

Consumer 环境变量：

```dotenv
SPEECH_TTS_ENABLED=true
SPEECH_TTS_MODEL=qwen-audio-3.0-tts-flash
SPEECH_TTS_VOICE=longanhuan_v3.6
# SPEECH_TTS_API_KEY 留空时使用 SPEECH_API_KEY，必须是具备模型权限的普通百炼密钥。
# 可选 SPEECH_TTS_ENDPOINT，默认北京地域原生 HTTP TTS 接口：
# https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer
```

默认关闭。密钥只通过服务端私有环境注入，不放入前端、Git 或日志。本版不是 OpenAI-compatible chat completions 协议，不能仅替换 ASR 的模型名。

- `GET /api/speech/output-capabilities`：登录后返回 `{enabled,maxCharacters}`。
- 原生模型请求设置 MP3、24 kHz 和 AI 合成标识。供应商返回音频地址后，仅允许北京官方结果桶，升级为 HTTPS，禁止重定向，不携带模型 Authorization 下载。
- 去除 Markdown 标记、代码块及链接 URL；保留商品、金额和订单文本。最多 3,000 个字符（原始文本最多 8,000 字符），超出后拒绝播报，不静默截断。
- 单 Consumer 实例最多 3 个并发合成，同用户仅 1 个在途请求，10 秒内最多接受一次；这是保护限制，不是分布式计费配额。
- 有界读取模型响应和音频；模型响应头超时 20 秒、正文 15 秒，音频响应头 10 秒、正文 10 秒，前端总等待 60 秒。客户端取消不保证上游已开始的合成免计费。
- 返回真实 `X-Speech-Characters`（供应商提供时）。TTS 按字符用量返回，不混入聊天 Token 合计；本版未新增独立 TTS 计费看板。
- 401 未登录、404 不属于当前用户或结果过期、409 尚未完成/无可播报结果、422 过长、429 限流、503 未启用、502/504 上游异常。供应商原始错误不透传。

## 验证

```sh
npm --prefix frontend run test:voice
npm --prefix frontend run test:voice-output
npm --prefix frontend run build
mvn -B -pl smart-assistant-consumer,smart-assistant-gateway -am test -Dtest=SpeechRecognitionServiceTest,SpeechControllerTest,SpeechSynthesisServiceTest,SpeechOutputControllerTest,GlobalJwtAuthFilterTest -Dsurefire.failIfNoSpecifiedTests=false
```

本地相关测试共 69 项通过：语音输入前端 17、语音输出前端 7、Consumer 23、Gateway 22；TypeScript/Vite 构建通过。新增测试覆盖身份归属、完整结果、正常澄清与失败/待审批状态区分、长度及 URL 限制、模型协议、下载不带密钥、私有响应头、限流、暂停/继续、内存重播、取消和迟到响应、自动播放被拒、切换会话及脱敏错误。

Edge 浏览器模拟 API 的完整流程验证了录音后确认发送、语音自动播报、文字不自动播报、关闭开关、手动朗读及 390px 手机无横向溢出。线上结果在部署后单独记录，不能用模拟测试替代真实模型验收。

已完成真实模型与页面线上复测，17 项通过，详见 [部署验收记录](voice-output-deployment-verification.md)。
