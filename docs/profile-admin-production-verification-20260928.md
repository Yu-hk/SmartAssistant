# 管理员画像管理发布与验证（2026-09-28）

## 范围

- 新增管理员“画像管理”页、元数据/任务/审计查询，以及需要原因和用户名确认的代用户清理。
- Consumer 对该路由独立校验 access JWT、撤销状态与数据库当前 `ROLE_ADMIN`；操作事务锁定目标普通用户并同步提交暂停、清理任务和管理员审计。
- 保留普通用户原有“画像与隐私”自助入口。管理页不返回 `user_profile_snapshot.report`。
- 先执行加法迁移 `docs/database/migrations/20260928_add_profile_admin_cleanup_audit.sql`，再更新 Consumer JAR 和前端。

## 本地验证

- Consumer 编译成功；`ProfileAdminAuthenticationTest`、`ProfileAdminControllerTest`、`ProfilePrivacyAuthenticationTest`、`ProfileCleanupServiceTest` 共 9 个测试通过。
- `frontend/npm run build` 通过。
- 流程图 `profile-admin-management.workflow.json` 通过 Archify showcase 9/9 检查，0 错误、0 警告；浏览器 1440×900、1600×1000、1920×1080、2048×1320 及明暗截图验收通过。

## 生产切换与回滚

- 发布前 `/healthz` 为 200；MQ 所有相关队列 ready/unacked 均为 0；根分区剩余约 7.3 GB。
- 原 Consumer JAR SHA-256：`3d4611d62130f2f1e58365e8983bba0d8aa6760681151a0536ecf2e115ebbd5a`。
- 最终 Consumer JAR SHA-256：`ab69c9525e49a73790040de24c28d51270fa5f31cbd2e7f0f1bca7e3dc770842`；容器 `/app/app.jar` 与宿主挂载源相同。首次发布后的空原因值校验补丁已重打包、复测并二次切换到此最终版本。
- 原前端 `index.html` SHA-256：`e7323c219c25dd4a9af5f19b6e2525d64819e3f3d4375d61e913228fba0f5319`；新版为 `8a715a77ee67c569ce929bceeb8b32bc40c7459eb30969ee8bfa94b132d192b9`。
- 回滚文件：`/opt/smart-assistant/releases/profile-admin-20260928/consumer-before.jar` 与 `index-before.html`；已发布的哈希资源仍保留，回滚时先恢复入口，再恢复 Consumer JAR 并重启，随后复核哈希、内部健康及公开页面。
- Consumer 两次停止均在默认 10 秒后由容器运行时发送 SIGKILL；切换前相关 MQ 队列 ready/unacked 均为 0。后续应排查优雅停机耗时，并为业务发布设置足够的停止宽限期。
- 首次前端切换时，复制资源导致 `/opt/smart-assistant/frontend/dist/assets` 权限变为 `0700`，JS/CSS 返回 404、页面空白。已恢复为 `0755`；两类资源均返回 200。后续发布应避免把源目录权限覆盖到运行中的静态目录。

## 线上验收

- Consumer 内部健康 UP，公开 `/healthz` 200。未认证访问 `/api/admin/profiles` 和原 `/api/privacy/profile` 均返回 401。
- 仅用新建 QA 管理员与 QA 目标账号验收；管理员最终已降回 `ROLE_USER`。页面只显示元数据，不显示合成画像原文；错误用户名不能提交。
- QA 清理任务 `4461ae30-c314-457c-a29a-4fd155d0b948`：目标分析状态 `false`，快照已删除，任务 `ONLINE_CLEANED`；五个清理回执均 `SUCCEEDED`，管理员审计恰好 1 条。
- 普通 QA 用户访问管理员画像详情返回 403。未触碰真实用户画像；QA 账号与审计按既定测试留存。
