# 诊断副本最小化与历史备份目录核查（2026-09-19）

本批接续 [副本清点与合成恢复演练](profile-copy-inventory-20260919.md)。服务入口重复输入框已在上一批修复，
本批不修改前端，也不删除用户对话、订单、评价或真实画像，不重放/清空任何队列。

## 新增诊断副本的收敛

- `PromptAuditAdvisor` 保持默认关闭；即使启用，也只记录请求标识、消息数量和返回条数。
  不再截取提示词或模型回复。普通偏好不一定匹配 PII 正则，因此不能仅依赖正则脱敏证明安全。
  追踪标识限制字符与长度，避免日志换行注入；实际发送给模型的提示词不变。
- `AgentFlowTraceStore` 写入 Redis 和进程缓存前，清空问题正文，使用固定节点名称和状态摘要。
  不保存模型生成的任务描述、回复摘要或原始异常；保留拓扑、节点状态、耗时、模型身份及问题字数。
  读取旧记录时同样投影为上述展示字段，避免管理员读取接口继续暴露旧摘要。
- 进程兜底缓存由无界 Map 改为最多 1,000 项、写入后 24 小时到期。
  Redis 成功返回不存在时使本地项失效；不能在 Redis 删除后从本地重新显示旧记录。
  Redis 异常时可以使用未到期的元数据缓存，日志仅记录异常类型。
- Token 用量采集与管理员调用日志不变。当前 `llm_received_question` 写入来源是用户原始问题，
  不是最终拼接画像后的模型提示词；这不证明历史版本的调用日志没有画像派生内容。

这些措施仅减少新增副本和旧记录的读取暴露，**不构成历史数据物理擦除**。
旧 Redis 值读取时不重写、不延长 TTL；其中原始正文仍可能存在，直到到期或经过授权的清理。
节点 ID、模型身份等保留元数据也未作历史逐行归属认证。Router 重启清除旧进程缓存，但不删除历史日志。

## 只读清点结果

`scripts/profile_copy_inventory.py` 对执行图增加 PTTL 元数据检查，不获取正文。
部署前观察 58 个执行图键，SCAN 完整，永久键 0，超过 24 小时键 0，扫描期间消失键 0；
最大剩余有效期 84,998,190ms。这是动态观察，不是所有实例及未来写入的持续证明。

`scripts/profile_backup_catalog.py` 仅接受服务器备份目录，检查普通 `.dump` / `.sql` 文件，
拒绝越界/符号链接及大于 64MiB 的文件。自定义归档调用 `pg_restore --list`，SQL 只解析表目录，
跳过 COPY 数据区；不恢复数据库、不执行 SQL、不输出行内容或归档所有者。

| 备份 | 目录观察 |
| --- | --- |
| `before-product-intake-20260914/products.dump` | 1 张表，未命中画像/诊断表名称 |
| `catalog-20260831-0938/products.sql` | 1 张表，未命中画像/诊断表名称 |
| `conversation-suspend-20260902-153921/conversation_session_state.sql` | 1 张表，未命中画像/诊断表名称 |
| `oauth-20260828-1610/schema-before.sql` | 23 张表，包含审计、调用日志及工作流恢复任务表 |
| `pre-5cddc920-20260812/a2a_system-20260812.dump` | 21 张表，包含审计及调用日志表 |

五份目录均未命中按 `profile` / `memory` / `preference` 名称识别的表，
**不表示其他表的行内容不含画像信息**。压缩归档、外部备份位置、逐行归属及权威墓碑备份仍未验收。
历史备份没有被修改或删除。

后续 10 份压缩归档的有界只读目录检查见 [压缩备份覆盖进展](profile-archive-inventory-20260919.md)；
目录检查不替代归属、完整性和真实恢复验收。

## 测试与发布

- 本地 130 项定向 Java 回归、31 项 Python 检查通过。
- 使用最终 Router 包及其嵌套 Common 包，在服务器内部专用 Redis / Java 容器运行 18 项测试通过。
  覆盖真实 Redis TTL、旧值读取最小化且不续期、删除优先于进程缓存、日志无正文及 Token 采集不退化。
  测试不挂载生产数据，专用容器和网络已移除。
- 四个服务均重新打包，逐包验证嵌套 Common 与本轮公共 JAR 逐字节相同。
- CI 新增目录解析测试及真实 Redis 执行图测试，并检查报告中至少 3 项实际执行、无跳过。
- 发布目录 `/opt/smart-assistant/releases/profile-diagnostics-20260919`，回滚备份
  `/opt/smart-assistant/backups/before-profile-diagnostics-20260919`；四服务、网关及公网健康检查通过。
- 公网 7 项价格/规格/颜色和订单边界回归通过；7 条请求准入有效，清理任务/回执仍为 0，
  没有暂停真实用户画像或推进其代次。另一次商品与退货知识组合只读查询正常返回。
- 执行图按 `sessionId`（不是传输请求 ID）读取，5 个不同测试会话的最新图均通过元数据及 TTL 断言。
  首次验证脚本误用请求 ID 导致查无结果，纠正查询标识后验证通过，没有因此改写生产业务数据。
- 公网 HTML/资源与部署文件一致；本批未作浏览器交互验收，不能以接口结果替代。
- 包 SHA-256：Consumer `48169081976da38eb77d370da1102e633f32a216c701fa3511a6e4f1d4ae109a`；
  Router `9995dae9b1e9ed1e077ae7ae8e2817c7ee0be2e6c8c08206df38c8c833711c25`；
  Product `5b40ebc5278128bc90546543516ed9bd72e65b0af03bf14e70df8793d1f0a5fc`；
  Order `d221404ed0ce79da08537dc6d2bcf7e03100b4a9f6fdbc6f402d370c8cb598ff`。
- 发布目录保留 `isolated-diagnostics-tests.log`、`deployment.json`、`after-artifacts.json`、
  `verification.json`、`admission-verification.json`、`trace-metadata-verification.json`、
  `combined-query-verification.json`、`backup-catalog.json` 和 `copy-inventory.json`。

## 尚未完成的验收

`LEGACY_STORAGE`、`DERIVED_COPIES`、`BACKUP_RESTORE` 继续为 BLOCKED，画像清理开关保持关闭。
上一批合成 PG 恢复演练的通过不等于真实全量恢复安全。
还需要证明旧实例/运行时路径覆盖，定位历史日志及其他派生载荷归属，并建设独立可信的墓碑备份、
恢复前重放与全存储放流量屏障。在这些条件完成前，不能开放或宣称“全部画像删除完成”。
