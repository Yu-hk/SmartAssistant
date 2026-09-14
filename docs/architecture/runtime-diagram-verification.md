# 架构图与部署验证记录

日期：2026-09-14。当前代码依据：`773401019a0cbd81f5ec2ff6135d1062923bc295`。

本次更新补齐 Consumer 商品录入、结构化参数/原文审计持久化、Product 按事实筛选与推荐理由的职责，保留原有情绪/画像并行、MQ 优先级与可选画像等待上限。主图只绘制主要调用关系；录入不经聊天队列的独立 API 路径详见 [商品录入与推荐读取架构](product-intake.md)。

后续复核更正：本记录初次检测脚本提取了第一条执行中的 `token_usage`，据此判断“部分采集”不成立。修正为读取最终 `response → token_usage → done` 后，线上商品咨询最终用量为 37,999，`tokenUsageComplete=true`。详见 [商品证据与 Token 检测修复](../product-evidence-telemetry-fix.md)。此更正不影响架构图的字节摘要或图形验收。

## 生成方式

使用用户指定的 [Archify](https://github.com/tt-a1i/archify)，安装来源固定为 `a07fa1d5b2a10cbea110c5a2be2817397a301cdc`，技能元数据版本 2.17。未运行自动更新或引入项目运行时依赖。

- 类型：`architecture`；静态默认、中文、经典主题。
- [JSON 规范](smartassistant-runtime.architecture.json) 是图的唯一拓扑来源。
- [交互式 HTML](smartassistant-runtime.architecture.html) 通过 Archify `deliver` 生成；[README SVG](smartassistant-runtime.svg) 来自该 HTML 的标准 SVG 导出。
- 本地验证 24 条源码引用，固定到上述代码提交；主图只展示主要调用边，不代表完整部署或所有共享依赖。

复现（`ARCHIFY_HOME` 为安装的技能目录，仓库根目录执行；PowerShell 使用相应变量语法）：

```sh
node "$ARCHIFY_HOME/bin/archify.mjs" validate architecture docs/architecture/smartassistant-runtime.architecture.json --quality showcase --repo-root . --json
node "$ARCHIFY_HOME/bin/archify.mjs" deliver architecture docs/architecture/smartassistant-runtime.architecture.json docs/architecture/smartassistant-runtime.architecture.html --quality showcase --repo-root . --json
node "$ARCHIFY_HOME/bin/archify.mjs" visual-check docs/architecture/smartassistant-runtime.architecture.html --json
```

需要本地 Chrome/Chromium；未自动发现时通过 `ARCHIFY_CHROME` 指定浏览器路径。打开最终 HTML，选择“导出 → SVG”，更新静态图。不要直接编辑生成的 HTML/SVG。

## 图形验收

```text
diagram_type: architecture
specification_sha256: 040b11fdbb6a2072b410022fcad567ec6a6327c3180c92689a0c03af748cca1e
artifact_sha256: cdd946acd83b67ecbc5d8ac9991f3692413e6644d5cbc149aa455fa59be0630d
validation: 9/9 showcase, 0 errors, 0 warnings
browser_evidence: passed
visual_review: passed
correction_rounds: 0
```

规范为 11,367 字节，HTML 为 825,309 字节（上述摘要对应生成时的 LF 文件字节）。交互式图交付后检查了 1440×900、1600×1000、1920×1080、2048×1320：均无横向或纵向页面溢出。另实际检查两种端点尺寸的浅色/深色截图，节点与标签没有遮挡，主图和说明卡片完整可读。本次复用既有主图布局，没有缩小字体、隐藏溢出或裁切内容。

确定性校验、浏览器尺寸检查、图片视觉审查是三项独立证据，不能相互替代。截图和原始运行记录为本地验收产物，不向仓库提交运行环境信息。

补充交互检查：Consumer 节点聚焦与 Escape 关闭、节点搜索与关闭、标准 SVG 下载均通过，没有浏览器脚本错误。导出的 SVG 仅清理行尾空白，已按图片模式渲染核对，没有夹带搜索框、聚焦面板等交互浮层；SVG SHA-256 为 `6fccc3ce064f9d5f51b5c62f59a5825df47f5e65ea9ab2202672cd6602ebe40d`。

曾尝试额外制作录入细分子图，但经过两轮聚焦修正仍有节点上下文可读性诊断。依据 Archify 的停止条件，没有将该候选当成验收成品或提交到仓库；改用已核对源码的录入架构说明。该候选失败不改变上述系统总图的验收结果。

## 商品录入生产验收

最新商品录入版本已部署，39 项真实接口/数据库/对话检查通过；临时商品删除后原有 318 条商品未改变，管理员内网 API 与公网普通用户权限验证范围明确，详见 [部署验收](../product-intake-deployment-verification.md)。图形验收与生产功能验收是独立结果，本轮只更新文档并推送，不重复部署应用。

## 先前画像/MQ 版本的生产验证范围（历史记录）

Consumer 与 Router 已部署至 [演示站](https://xiaoyuai.cloud)，保留切换前 JAR 备份。部署时暂停入口并确认队列排空后替换，不删除持久化业务数据。

| 检查 | 结果 |
| --- | --- |
| 本地相关回归 | 162 项，158 通过；4 项需要显式中间件配置的集成用例跳过，无失败 |
| Consumer / Router 健康及公网入口 | 服务健康，`/healthz` 返回 200 |
| 服务端真实 Redis：PENDING / 等待预算 | 无画像降级；实测约 533 / 540 ms（含测量开销），不把 500 ms 描述为端到端 SLA |
| 迟到结果、已有快照、FAILED | 本轮选择冻结不重复等待；复用可靠画像；失败无画像继续 |
| 存储故障、线程中断 | 隔离测试中模拟存储异常后降级；中断仍向上传播，没有对生产 Redis 注入故障 |
| 商品价格库存咨询 | 经 MQ 完成 Product 工作流，工具与 Token SSE 事件到达 |
| 预算内平板推荐 | 无候选商品时正常说明原因，工作流完成，无画像导致的 FATAL_FAILED |
| 队列观察 | 检查时普通队列 0 ready / 0 unacked / 4 consumers；死信 0 |

验证使用两个独立普通测试账号，没有下单。首次复用账号时遇到已有“待评价会话暂停”规则，随后改用独立账号验证第二场景，不绕过业务门禁。

初次验证边界：商品回答出现既有的证据不足提示；Token 初次脚本误取中间事件，不能据此判定最终采集状态，后续更正见本文开头。因此本次画像验证不代表所有回答事实、分布式故障恢复或压力测试都已验收。
