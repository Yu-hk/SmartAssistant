# 知识不足与执行阻塞的误判修复

## 原因与复现

2026-10-01 合成浏览器请求 `6b20bc52-5dc7-473b-af00-4fa893d88905` 已完成有界补检并安全保留首轮证据，但当前知识库未提供可售、锁定与质检库存的计算规则。模型正常说明“暂时无法确定”，旧 `LoopGuardService` 对整段文本做宽泛关键词匹配，把它判为 `PAUSE_BLOCKED`；`SmartReActAgent` 随即丢弃原答复，返回通用执行阻塞提示。

第二层错误是商品质检：通用阻塞提示没有具体事实声明，且检索结果分数较高，于是被标为 PASS。这不是检索超时，也不能靠重复检索或重新发送消息修复。

## 修复边界

- 守卫区分“无法确定事实”和“无法执行操作”。缺公式、未知参数、缺商品名称、索要规则文档等不再单独触发阻塞；按当前执行主体、操作动词、凭据和工具前提识别真实阻塞。
- 判断限制在句级，不能因整段有“资料不足”就豁免其他句子的真实权限或工具故障。
- 基础设施错误仍优先暂停，明确下单/支付等确认仍停止于工具执行之前；不绕过写操作批准。
- 商品质检在证据捷径之前识别完整系统执行恢复文案，判 `PRODUCT_EXECUTION_FAILURE`，不可因检索分数高或已有工具事实改判 PASS。只匹配完整恢复文案，不把正常“暂时无法确定”当执行失败。
- 不增加模型调用、检索次数、工具重放或业务写权限。`SmartReActAgent` 本身、公开 API 和枚举不变。

这是有界的确定性规则修正，不是完整语义分类器。基础设施关键词的历史引用识别并未重新设计；不要声称所有可能的自然语言误判都已消除。

## 验证与发布范围

本次限定生产 Product：当前工件基线 `1524087b617c1df38744e7c06337c2e0f5f178ee95307b4662ce9e666c930693`。仅替换 Common 内嵌库里的 `LoopGuardService.class` 和 Product 的 `ProductDomainQualityValidator.class`，逐条验证内外 ZIP 的其他条目原字节不变；不替换整个 Common 库，不覆盖线上模型配置、凭证或重检索开关。其他生产服务未随本次替换 Common，后续构建会包含源码修复。

验证工具：

- `LoopGuardServiceTest` / `SmartReActAgentBehaviorBaselineTest`：固定资料不足文本、混合真实阻塞、基础设施和确认；实际 Agent 返回与停止工具的行为。
- `ProductDomainQualityValidatorTest` / `StreamingProductAgentServiceTest`：执行失败不可标 PASS，友好失败保留 FAIL 状态，知识不足原样返回；检索/模型/工具仅执行一次。
- `scripts/build_knowledge_guard_overlay.py`：固定基线、限定两类、Java 兼容性和内外无关条目保持检查，不覆盖已有候选。
- `scripts/verify_knowledge_guard_binary.py` / `LoopGuardRegressionProbe.java`：生产 Java 运行时、关闭网络，不调用模型；对完全相同文本验证旧误判与新结果。
- `scripts/verify_knowledge_guard_live.py`：合成只读 Product 请求、实际同请求检索轨迹与目录合计；不冒充页面验收。
- `scripts/verify_knowledge_guard_browser.cjs`：独立 Chrome/Edge、新建 QA 账号、真实发送、资料不足/目录合计/已知未知混合查询、刷新和跨浏览器恢复。不下单、不充值、不删除真实会话。
- `scripts/release_knowledge_guard_20261001.py`：独立发布目录、旧基线核对、金丝雀、排空、上线检查和受限回滚；所有生产环境项保持一致，旧容器保留。

### 2026-10-01 验证结果

| 验证层 | 结果与边界 |
| --- | --- |
| 先复现旧实现 | Common 固定回归 52 项中 17 项失败，Product 15 项中 6 项失败；实际 Agent 会丢失资料不足的安全答复。保留红灯记录，不用随机重新生成答案代替复现。 |
| 本地完整回归 | Common、SPI、Product 共 1285 项，零失败/错误，7 项既有跳过；最终代码再独立运行聚焦回归 164 项，全部通过、零跳过。 |
| 发布工具契约 | 新增 Python 契约 9 项、既有有界补检契约 12 项通过。 |
| 生产二进制对照 | 相同固定文本、相同 Java 21 运行时，旧包 8 项、新包 8 项均符合预期；旧包误判 `PAUSE_BLOCKED` 且执行失败标 PASS，新包分别 CONTINUE 和 FAIL。网络关闭、模型调用 0 次。 |
| 金丝雀及上线后 | 各 3 个合成只读请求通过。前两题真实补检 `attempted=true` / `NO_SAFE_IMPROVEMENT`，保留首轮并明确规则缺失；第三题目录合计 12997 元。 |
| 真实页面 | 新 QA 账号 `qa_guard_70bc623dd89a` 在 Chrome、Edge 各 4 轮，共 8 次发送、8 次刷新和同账号跨浏览器恢复通过，零 JS 异常。6 次实时处理、2 次缓存复用，不把缓存算新 Agent 执行。 |

上线后的 Product 工件 SHA-256：`29f3c588b76abeee855c10334a9e26e6ac5f9bf474a11652f66485b1cecb723b`。健康检查及公开入口通过，原 `enabled=true`、`maxRetries=1`、`timeoutMs=1500` 未变。旧容器 `smart-product-before-knowledge-refusal-guard-20261001` 和旧工件保留，可恢复至发布前 Product；没有同步更新其他生产服务。

上线后对应请求：`qa-knowledge-guard-9c64cf2179bd4223b731ea11b6575229`、`qa-knowledge-guard-ebf39cec714149bbbe9fd3a78ff8974e`、`qa-knowledge-guard-0805fda0e4d148619c82c4d0e66ea8f0`。Chrome 的原失败问题对应新会话 `d3a10518-5209-4070-9083-deb2052c472b`，实际保留完整资料不足说明，未再出现通用阻塞替换。已知价格与未知重量混合提问也分别给出已核实价格和“重量资料尚未核实”。

首次二进制探针在候选包传输尚未结束时启动，候选库读取失败；生产未变更。增加候选摘要前置核对、完成传输后重新运行上述 16 项固定对照，通过后才进入金丝雀和发布。

本轮未创建订单、入账或修改商品，QA 账号与 8 条测试会话保留；没有删除真实会话。缺资料的安全答复通过不等于补齐知识库，也不代表 Ragas 四项指标提升或已观察到生产候选 `ACCEPTED`。真实权限/凭据阻塞与写操作确认以固定 Agent 回归验证，不在生产注入故障或触发订单写操作。

脱敏验证回执位于本机 `.codex-artifacts/knowledge-refusal-guard-20261001/`（不入库）：`binary-verified.json`、`canary-verified.json`、`production-verified.json`、`browser-report.json`、页面截图及 Maven 日志。测试凭证仅在验收进程内生成和使用，报告不保存密码。

## 流程图

沿用 Archify classic：[交互图](https://yu-hk.github.io/SmartAssistant/architecture/automatic-retrieval.workflow.html)。新增“资料不足 ≠ 执行阻塞”与执行失败不可标 PASS 的说明，没有改动检索拓扑。

交付：workflow，9/9 showcase，零错误/警告；四种桌面尺寸自动浏览器证据通过，浅/深色实际截图已检查通过，几何修正 0 次。冻结规格摘要 `d2d6053436bdd8c5dbbedfc712eb3ec79118b566dc3feb4c0b75144ac3a0080d`，HTML 摘要 `b4cfee445343d849e7f4af19fbb257b903f1d57f9cd03237cf050687a4a3f58f`。
