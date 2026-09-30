# 多商品查询上线验收（2026-09-30）

## 结果与边界

多商品只读查询已在生产部署，Chrome 与 Edge 各完成三项真实页面测试。使用合成 QA 账号通过普通注册 API 建立登录态，再操作页面输入框与发送按钮；未直接写入对话结果，未下单、扣款、修改商品或删除历史会话。测试账号与对话保留。

| 场景 | Chrome / Edge | 核对结果 |
| --- | --- | --- |
| AirPods Pro 和 MacBook Air M3 分别多少钱、有货吗 | 均通过 | 目录价格分别为 1999 / 8999 元，库存状态分别为充足 / 紧张；每款展示一次 |
| 两款合计不超过 10000 元 | 均通过 | 每款各 1 件合计 10998 元，明确超预算；不把总预算当单款上限 |
| AirPods Pro 与不存在的 QA 商品合计多少钱 | 均通过 | 保留已知商品事实，未知商品要求核实，不计算不完整总价、不重复回复 |

以上价格/库存仅为验收时目录数据，不构成后续报价或实物库存保证。页面无 `pageerror`；人工检查 Chrome 未知商品页、Edge 总预算页的回复及布局。未知商品页出现商品名称/型号补全表单，但本轮没有验收补全提交后的后续对话。内部节点可能重复核查，因此右侧工具次数可以大于商品数，不能据此宣称“全链路只检索一次”。

最终 QA 账号：`qa_multi_cb6b087c7f`（Chrome）、`qa_multi_fcc0938e2a`（Edge）。另用 `qa_multi_a19ecb0b20` 在 Edge 重跑三项全部通过，截图关闭过渡动画以便核对。只记录用户名，不保存密码或令牌。

## 分层验证

- 商品/路由相关本地回归：398 项通过，无失败、跳过。
- CI 客服回复与资料补全同组选定测试：415 项通过，无失败、跳过；与前一组有重叠，不相加为独立测试总数。
- 发布保护脚本：7 项通过，覆盖仅替换指定类、基线漂移拒绝、禁止覆盖、停止状态核验以及权限失败不绕过。
- 本机非 ASCII 路径会使 JaCoCo agent 启动失败，因此本地关闭 coverage agent；没有关闭远程 CI 的正常覆盖率检查。
- 生产只读接口：`ANSWER`、`QUERY_PRODUCT`、`DISCOVER_PRODUCTS`、`ANALYZE_PRODUCT_DATA`、`RECOMMEND_PRODUCT` 保留整题预算及证据；价格/库存与 PostgreSQL 目录核对一致；另外检查单款预算、未知商品、兼容性不能擅自承诺、无可执行订单报价。
- 部署后服务及公网 `/healthz` 健康；验收后最近 5 分钟 Product / Router 日志 ERROR 行数均为 0。该短窗口不代表长期稳定性或压力测试。

复现入口：`scripts/verify_multi_product_query_20260930.py`（生产内网只读）、`scripts/verify_multi_product_browser_20260930.cjs`（真实 Chrome/Edge 页面）。后者每次创建 QA 账号并保留对话，运行前需确认目标环境允许这些测试写入。`QA_BROWSER_CHANNEL=chrome|msedge`，`PLAYWRIGHT_MODULE` 指向已安装的 Playwright；可选 `QA_BROWSER_PROXY`、`QA_SCREENSHOT_DIR`。本地截图与构建日志位于模块 `target/`，不提交运行时产物。

## 验收中发现并修复的问题

第一轮接口通过并不等于页面通过。真实未知商品页面暴露了两个问题：旧 `ANSWER` 协议入口只保留正文，丢失版本化证据；上游多个商品节点及 general 总结被重复拼接。补齐旧入口的数据输出，并让整题范围完全匹配的商品事实结果优先展示。仅无结构数据的 general / router_fallback 总结可被替代；其他业务域、结构化 general 输出、失败、交接和冲突仍保留既有处理。增加断言“未知提示恰好一次”，最终两浏览器重新通过。

本次不声称已改善 Ragas 的具体分数：上线的是有界拆分、证据输出与回归验证，没有启用线上模型评分或自动重检索循环。复杂数量、不同商品查询不同字段等未覆盖表达完整回到现有规划链，详见[实现范围](multi-product-query.md)。

## 发布与回滚证据

最终发布目录：`/opt/smart-assistant/releases/multi-product-answer-20260930`。

| 工件 | SHA-256 |
| --- | --- |
| Product 完整 JAR | `3cba4f8f1fd26325f7d43ed76a34618f5ce917655d8680fa92465dcd30f685f0` |
| Router 窄范围补丁 JAR | `55b3fae3715e89c54784c5710fac57ee0eeee52e0d41703b42a19e9fda8d4755` |

生产 Router 与 main 存在先前订单能力的工件差异，本次没有用 main 的完整 Router 覆盖线上。`build_result_merger_patch.py` 核对基线类摘要，仅替换 `ResultMerger.class` 及其既有内部类；验证其余所有 ZIP 条目和库保持原样。该差异仍需单独对账，不属于本次多商品查询修改。

发布脚本 `release_multi_product_answer_20260930.py` 复用受保护发布流程：工件快照 → 哈希核对 → 容器配置等价预检 → 暂停入口并排空 → 替换 → 健康及公网验证。保留旧容器、`before-artifacts.json` 和 `deployment.json` 用于回滚。未删除业务数据或旧发布备份。

Archify classic 流程图已纳入[图表总览](index.html)，图形验收单独记录于[架构图验证](architecture/runtime-diagram-verification.md)。
