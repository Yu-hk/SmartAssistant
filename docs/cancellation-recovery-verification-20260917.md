# 用户取消与恢复入口修复验收 — 2026-09-17

## 原因与修复边界

- 用户取消后，LangGraph 将 `WorkflowCancelledException` 包装为多层执行异常。RouterService 的通用异常处理先把它当作失败，再在中断线程上写失败事件；Redis 的中断异常覆盖了取消原因。Consumer 因此按结果不确定处理，保留账号占用。
- 在 RouterService 的失败处理前提取并传递类型明确的取消信号；Controller 返回 `CANCELLED`，清除 Servlet 线程中断标记。租约释放期间临时清除中断标记，仍使用 owner 比较删除，随后恢复标记。不把任意超时、连接异常或中断都视为用户取消。
- 线上恢复开关关闭，旧版前端却显示恢复入口，访问条件未注册的 Controller 后出现 `NoResourceFoundException`。用户恢复 Controller 现始终提供能力探测；底层恢复服务未启用时返回明确的 503，前端默认不提供恢复入口。不隐式开启生产恢复执行器。
- 前端将 SSE `cancelled` 视为终态，不转成断流失败、不显示恢复按钮；恢复轮询有次数上限，终止错误不再持续重试；历史恢复错误和新增错误使用公开文案，隐藏内部类名/路径；`unknown` 来源显示为“智能助手”。
- 经用户单独授权，只使用原子校验删除截图旧请求对应的账号占用指针。校验请求 ID、用户、会话、状态、命令内容及无运行租约；请求记录与状态保持不变，没有修改历史回复、删除对话或自动重跑查询。

## 本地验证

- Maven Router 相关回归及打包：Common 1 项通过，Router 31 项通过、6 项外部依赖集成测试未启用而跳过，共 32 项通过、6 项跳过。
- 增加真实 RouterService → Controller 取消传播测试，验证不进入 Redis 失败事件写入；覆盖嵌套异常、非取消异常不误判、恢复服务启用/关闭、带中断标记的 owner 校验租约释放。
- `npm run test:recovery`：5 项通过，覆盖入口能力、用户可读名称、历史错误脱敏、网络错误公开文案、已停止/已完成消息。
- `node scripts/run-session-gate-tests.mjs`：2 项通过，包含服务端取消事件的 Hook 终态测试。
- 前端 TypeScript/Vite 构建与仓库事实检查通过。前端验证为组件 SSR / JSDOM 测试，未将其记为线上浏览器点击验收。

## 线上验证

- 发布目录：`/opt/smart-assistant/releases/cancel-recovery-v2-20260917`。
- 更新 Router 与前端；暂停 Gateway 入口、等待聊天 MQ 无就绪/在途请求后切换。备份制品，校验实际挂载文件和 SHA-256，健康检查通过后恢复入口。
- Router SHA-256：`c2630372ff5064c99f1358c2517a4dc411e9cb7751a8b01e07c480e8e1cd57b7`。
- 前端资源：`index-oelBNUpt.js`；公网首页内容与发布文件一致。
- 首轮线上取消复测发现 RouterService 仍覆盖取消信号，未将失败版本推送；补充真实服务测试、修复并再次部署后验收通过。
- 最终通过真实 HTTPS/JWT/SSE 链路，新建普通测试用户，不修改原用户对话：
  1. 恢复能力接口返回 `available: false`。
  2. 提交 AirPods 价格库存查询，等待 Router 日志确认 Product 调用已经开始，再请求取消。
  3. SSE 返回 `cancelled`，无失败/超时事件，无“业务处理结果尚未确认”回复。
  4. 关闭的恢复接口返回 503，不再出现静态资源异常。
  5. 同用户、同会话继续查询 AirPods 价格库存，返回 1999 元与库存信息，SSE 正常完成，请求状态为 `COMPLETED`，无占用冲突。
- 最终复测后续请求：`cancel-followup-e7a57564e033450880bacf37319744b5`；服务端结果记录位于发布目录的 `verification.json`，`passed: true`。测试会话已关闭。
- 旧请求复查：账号不再被其占用、无活动 Router 租约、原请求仍存在且仍为原始 `UNCERTAIN` 状态。保留历史事实，不把旧失败伪造为成功。
