# 语音输入部署验收（2026-09-15）

状态：**已部署，真实 ASR 和 15 项线上验收通过。**

- 本地重新执行前端 17 项语音回归和 TypeScript/Vite 构建，全部通过。
- Consumer 13 项、Gateway 22 项测试通过，两个部署 JAR 已打包。
- SSH 主机密钥校验通过；部署后服务器 14 个正式服务容器运行，公网 `/healthz` 为 UP。
- 使用用户授权的新版普通百炼密钥，北京地域普通 API、模型 `qwen3-asr-flash`；准确识别“请查询我的订单物流。”，输入 74、输出 13、总计 87 Token。
- 密钥通过无回显输入和 SSH 加密通道注入，仅在成功后保存为服务器 `0600` 配置，没有提交到 Git。

历史两次预检使用旧凭据或个人套餐凭据请求普通 API，返回 HTTP 401；不能据此判断套餐密钥在其专属平台无效。本次使用普通按量付费密钥完成预检，不使用个人套餐调用公共网站后端。

参考：[普通 API Key](https://help.aliyun.com/zh/model-studio/get-api-key)、[Qwen ASR 协议](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)、[个人套餐使用范围](https://help.aliyun.com/zh/model-studio/token-plan-personal-overview)。

## 生产切换

- 同步更新 Consumer、Gateway、前端、Nginx；允许同源麦克风并设置音频上传大小和超时边界。
- 暂停 Gateway 并等待聊天 MQ 排空后切换，健康失败时自动回滚；无数据库迁移，没有删除用户对话或评价。
- 备份：`/opt/smart-assistant/backups/before-voice-input-20260915/`。
- 旧 Consumer 保留为停止状态的 `smart-consumer-before-voice-20260915`，便于回滚。
- 当前 Consumer JAR：`/opt/smart-assistant/releases/voice-input-20260915/consumer.jar`。
- 当前 Gateway JAR：`/opt/smart-assistant/smart-assistant-gateway/target/smart-assistant-gateway-1.0.0-SNAPSHOT.jar`。
- Consumer SHA-256：`4bff1191fff40514aeddf0df95abc6faefb7b6362bb0178fb7720262ea4f9111`。
- Gateway SHA-256：`0e89dee862f9f2e764735847338e3080d029a821fe3b3fe2bb82f0e16baf721c`。
- 部署脚本因服务器 Python 不支持 `hashlib.file_digest` 在预检阶段停止过一次，改为分块 SHA-256 后通过；当时尚未切换生产。

## 线上验收

使用独立普通账号、实际 HTTPS 页面、Edge 无头浏览器和合成麦克风，ASR 调用真实供应商接口，未模拟 API。

1. HTTPS 页面包含 `microphone=(self)` 权限策略。
2. 未携带 JWT 的伪造用户身份返回 401。
3. 普通账号注册成功。
4. 浏览器登录成功。
5. 登录后语音已启用、最长 60 秒。
6. 非 WAV 返回 400。
7. 超限音频返回 413。
8. 静音返回 422。
9. 公网语音接口正确返回订单物流文本及真实用量 87 Token。
10. 连续有效音频请求返回 429。
11. 浏览器 MediaRecorder → WAV → Gateway → Consumer → 真实 ASR 成功，返回真实用量 109 Token。
12. 回填保留原有草稿，不自动发送客服请求。
13. 取消录音保留草稿。
14. 390px 手机布局麦克风可见可点击，无横向溢出。
15. 无浏览器页面脚本错误。

浏览器循环播放合成音频，结果为“请查询我的订单物流。请查询。”，末尾片段来自循环音源，不作为真实用户录音准确率证据。取消按钮验收脚本曾因定位名称错误超时，修正为实际“取消”后整套复跑通过，未修改业务实现。

验收仅创建普通测试账号，未发送客服消息或创建订单。未验证真人麦克风、Safari/iOS 实机、噪声和方言场景。独立 ASR 用量目前未纳入客服对话 Token 合计。
