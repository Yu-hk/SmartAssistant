# 商品实体处理验证 · 2026-09-30

## 本地

- 商品/录入回归初次 339 项通过（商品 280、录入 59），数据库升级契约 9 项通过。
- 隔离 PostgreSQL 16 的 4 项真实 SQL/Spring 事务测试全部执行、无跳过：元数据持久化与审计、陈旧版本冲突、父关系存在/自身/循环校验、审计失败回滚。
- 追加 Router 会话封装、Product 历史适配器及控制器回归通过。这里只是本地无模型单测，不是生产浏览器验收。
- 合成金标 10 问/14 次提及：提及精确率/召回率/F1、链接准确率、字段绑定准确率均为 1.0。小样本、不含真实 Jev 质量，不代表 Ragas 四项指标或线上泛化。
- Windows 非 ASCII 用户目录使用本地 `jacoco.skip` 绕过代理路径问题；远程 CI 仍保留覆盖率门禁。

## 生产发布与发现

第一阶段只更新 Product/Data Intake，新增身份元数据迁移成功。13 个新增只读协议问题及旧多商品协议回归通过；没有修改生产商品价格/库存/身份资料、订单或余额。

首次 Chrome 真页面验收发现：首轮“AirPods Pro 重量和 MacBook Air M3 价格”字段归属正确；追问“两款合计”未收到历史，退回模型后误以为第一款价格无法查询。已经补充 Product 对有界旧历史封装的适配；Router 明确序号/复数引用封装角色历史，保留展示顺序。

线上 Router 含尚未进入 main 的下单预检，整类校验拒绝直接覆盖。后续补丁采用 ASM 仅替换 `RouterService.addConversationContextIfNeeded`：先核对该方法与已测 main 基线一致，再校验所有其他类成员完全保留，JAR 其余条目及依赖逐字节不变。没有全包替换 Router 或迁入未合并订单代码。

第一阶段目录 `/opt/smart-assistant/releases/product-entities-20260930`，第二阶段 `/opt/smart-assistant/releases/product-entity-context-20260930`。均使用文件哈希、运行时挂载和两阶段容器恢复校验；旧容器/JAR 保留以便回滚。

最终候选：

- Product SHA-256：`f495487fe05cc72f9aed19d246b85f241f465ebb5dc5cf63885cb9cd4e201109`
- Data Intake SHA-256：`ea1e067b0feca0461ff27c414eea0b5d0cf5b1f405a76ba0987fcac0dfb7959b`
- Router 最终限定补丁 SHA-256：`417a88935f1999798bef3dedffa4ad8ce8e55b140fa3f54df2236cdac631c04b`（包含第二阶段方法补丁）。

第二阶段一次 Edge 全部通过，但另一次 Chrome 追问仍被模型交给通用回答节点。这是规划波动，不是 Chrome 独有故障。第三阶段加 `ProductEntityReferencePlanGuard`：模型分析之后，仅对有界只读指代由 Product 显式认领，修复为携带历史的 READ 节点；未认领、查询失败或包含写操作/文档内容不改原计划。本地 33 项相关回归通过，另加模型先于认领的接线测试。

第三阶段只替换字节完全匹配基线的 `TaskAnalysisService` 类并添加这一守卫类，保留已补好的历史方法与其他所有 JAR 条目。目录 `/opt/smart-assistant/releases/product-entity-plan-20260930`。

第三阶段服务及公网健康检查通过。线上只读协议回归再次通过：新增 13 问及原有多商品操作入口覆盖字段归属、未知型号、严格/包含边界、展示顺序和数量合计；金额与线上目录核对。

最终 Chrome、Edge 各跑两轮，每轮 7 项，共 28 次真实页面检查全部通过：不同商品字段绑定、跨轮两款合计、序号指代、指定数量合计、未知商品禁止完整总价、新会话不复用上一会话实体、未知型号后缀不自动截断。四个 QA 账号均验证管理员身份接口返回 403；页面脚本错误为 0。测试账号及合成对话保留，不生成订单、物流、扣款或生产身份资料。

本轮保留账号：`qa_entity_ce68e08ec1`、`qa_entity_04b73ecfab`、`qa_entity_1e45637b6e`、`qa_entity_3368aeac46`。没有保存或公开其随机密码/令牌。测试属于当前合成场景，不是所有自然语言实体识别能力的完备证明。

模型先于能力认领的新增接线测试通过，最终相关 8 项重跑全部通过。Actionlint、浏览器脚本语法与 diff 空白校验通过。发布后日志未见 Product/Data Intake 错误；Router 有两条可选画像上下文 `RedisSystemException` 警告，未阻断这些对话，本次未扩大范围修复画像链。

远程 CI 与合并结果另在 PR 中记录；本节仅报告已经实际执行的本地和生产验收。

## Archify 验证回执

新增 `product-entity-resolution.workflow.json/html`，保留经典原风格，纳入统一图表总览（13 张、8 张业务流程）。

- 规范校验：退出码 0，showcase 9/9，无错误或警告。
- 生成交付：退出码 0，HTML 806734 bytes，SHA-256 `6d68e2a7e8bcdb73e3502c3b099bf760bf3c45cf1284f044b345a4fbf1e05417`。
- 规范 SHA-256 `90f2e1a321d61fb7af0b99ebe1bd77661b048f0c93ae7b9bdc18e1f509bca4f3`（2788 bytes）。
- 自动视觉检查：退出码 0，`status=pass`，1440×900、1600×1000、1920×1080、2048×1320 四种视口无溢出，亮/暗主题与按钮交互通过。
- 人工像素审阅：已实际查看亮/暗端点截图，节点、连接标签与证据说明可读；`visual_review=pass`，`correction_rounds=0`。检查 JSON 的 `visualReview=pending` 只是自动工具不代替人工判定，本记录补充人工结论。

原有运行时总图与旧多商品流程不替换，本次以商品处理边界的专项流程图说明新增实体逻辑。
