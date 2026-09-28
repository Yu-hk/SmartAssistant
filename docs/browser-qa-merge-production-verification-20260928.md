# 归档浏览器修复合并：生产验证（2026-09-28）

- 集成提交：`733374cc46e9e2365ac6350475e3af9028456625`，父提交为 `10c5b290`（原 `main`）与 `40131d8b`（归档分支）。
- 只移植仍适用的修复：服务评分后关闭当前会话、商品详情追问按当前轮路由、前端入口页禁缓存。旧分支“切换时关闭其他活动会话”的逻辑没有启用，保持各会话独立。
- 本地验证：`npm run build`、`npm run test:session-rating`（2 项）、`npm run test:service-entry`（7 项）、Product 定向 Maven 测试（40 项）通过。Windows 上 Surefire 的 JaCoCo fork 启动失败，定向测试改用 `-DforkCount=0` 运行；源码编译与打包通过。

## 生产切换与回滚基线

目标为 `smart-product`、前端 `dist` 与 `smart-nginx` 配置；无数据库迁移。消息队列切换前 ready/unacked 均为 0。发布目录：`/opt/smart-assistant/releases/merge-browser-qa-20260928-733374cc/`，其中 `before/` 保存切换前的 Product JAR、Nginx 配置及前端 `index.html`。Product 容器原有 bind mount 路径不变，停止后在原 inode 上替换 JAR，再启动并核对容器内哈希。`docker stop` 曾返回 libpod 内部错误，但只读检查确认容器已停止，替换后正常启动并通过健康检查。

| 产物 | 切换前 SHA-256 | 切换后 SHA-256 |
| --- | --- | --- |
| Product JAR | `8dc03d387a87242d0be7294aff45324244a800325e2e2d5030e892da251b98cb` | `f33185b7e6fd8747c9319d58b5331a5600265fb5fc95116da26850407671f0f3` |
| Nginx 配置 | `92b4820e7c90dd6c9c27449dd7ddd7da58f085c0e7e3f8d623e0754072c6d13f` | `bee4e604a2c00aae046a3cdab2c04409e926fb44856f180d86fd4fe08c17c8d9` |
| 前端入口页 | `47fc435e69df7257fd0675cecc79b38b77dde5e48270b6458b463344a0ce245a` | `9e4ca452993f3d5b6ea20097f1c27895463cc30535bb91832693ca3f5cd9e14d` |

前端 18 个内容哈希资源先复制并逐文件比对，再原子替换入口页，旧资源保留。Nginx `nginx -t` 通过后平滑重载；`/` 和 `/index.html` 返回 `Cache-Control: no-store, max-age=0`，新版 JS 返回 200，`/healthz` 返回 200。Product `/actuator/health` 为 `UP`，容器内 `/app/app.jar` 哈希与本地构建一致。

## 业务验收

- 合成只读商品事实查询返回 AirPods Pro（第二代）价格、库存，质量状态 `PASS`；没有订单写入。
- 带历史“笔记本推荐”上下文、但当前轮询问 AirPods Pro 规格的请求返回当前商品规格，未被重新导向推荐；质量状态 `PASS`。
- Playwright 驱动真实 Chrome：注册专用 QA 账号 `qa_merge_733374cc_53fbf8`，发送只读商品咨询、收到回复、评分 5/5 后界面显示已结束；浏览器没有未捕获的页面错误。此轮未验证不同浏览器同时登录。
- 两次验收脚本定位错误另创建了 `qa_merge_733374cc_ca09bc`（未发送）与 `qa_merge_733374cc_c4bc01`（发送一条只读咨询）。三个测试账号及其对话均保留；脚本使用随机临时密码，未写入仓库或日志，也不涉及真实用户或订单。未经另行授权不清理。
- 验收窗口 Product 日志无 ERROR。Consumer 有两条 `/api/notifications/stream` 的 `AsyncRequestTimeoutException`，对应测试浏览器关闭后的通知长连接；未见商品或评分链路报错。

回滚时先停止 Product，恢复 `before/product.jar` 到原 bind 路径并启动；将 `before/index.html` 原子恢复到前端入口页；将 `before/default.conf` 复制到原 bind 文件后执行 `nginx -t` 和平滑重载。恢复后须重新核对上述旧哈希、内部健康、公开 `/healthz`、页面加载及只读商品请求；不可因复制命令成功就宣称回滚完成。
