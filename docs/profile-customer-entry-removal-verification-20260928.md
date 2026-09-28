# 普通用户画像入口移除与图表重绘（2026-09-28）

## 变更边界

- 普通用户侧栏不再挂载“画像与隐私”弹窗；管理员“画像管理”仍可查看元数据、清理任务和审计，并可按原确认流程代普通用户清理。
- Consumer 及数据库均未改动；原有仅允许本人访问的画像状态和清理接口暂保留，兼容旧客户端。隐藏入口不等于删除画像数据，也不会自动关闭既有用户的画像分析。
- 新架构图将只读查询与代清理分成两条支路，标出 Gateway、Consumer 管理员鉴权、同事务任务/审计和异步清理边界。

## 本地与图表验证

- `frontend/npm run test:scenarios`：11/11 通过，新增断言普通用户侧栏不出现画像入口；`frontend/npm run build` 通过。
- [新架构图源文件](architecture/profile-admin-management.architecture.html)采用 Archify `architecture` 类型，可[在线交互查看](https://yu-hk.github.io/SmartAssistant/architecture/profile-admin-management.architecture.html)；showcase 检查 9/9、0 错误、0 警告，浏览器自动检查 1440×900、1600×1000、1920×1080、2048×1320 均无溢出，明暗截图均已人工查看。

## 生产切换与验收

- 仅发布前端静态文件；未重启服务或运行数据库迁移。切换前 `index.html` SHA-256 为 `8a715a77ee67c569ce929bceeb8b32bc40c7459eb30969ee8bfa94b132d192b9`，切换后为 `47fc435e69df7257fd0675cecc79b38b77dde5e48270b6458b463344a0ce245a`。
- 入口备份位于 `/opt/smart-assistant/releases/remove-customer-profile-20260928/index-before.html`。若回滚，仅需以该备份原子恢复 `/opt/smart-assistant/frontend/dist/index.html`；旧哈希资源仍保留。
- 公网 `/healthz` 返回 200，新 JS/CSS 哈希资源均返回 200；`dist/assets` 保持 `0755`。
- 独立 Chrome 使用新建合成 QA 普通账号 `qa_profile_ui_1790588950981` 登录成功：新建会话、服务入口和主题切换仍可见，侧栏无画像入口、无页面脚本错误。QA 账号保留，未执行清理或订单操作。
