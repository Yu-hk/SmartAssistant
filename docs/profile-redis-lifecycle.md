# 用户画像 Redis 生命周期屏障

本批补齐请求投影的发布与读取隔离；不是全量物理删除功能，不开放删除/恢复入口。

## 发布

Consumer 在 `ProfileGenerationFence.write` 的 PostgreSQL 生命周期行锁内执行 Redis 发布脚本。
暂停事务与发布形成明确先后顺序，不再是无锁的“先查 PG、后写 Redis”。
脚本同时核对请求所有者、当前代次和用户级 `routing:user-profile-lifecycle:<userId>`。
控制标记不设 TTL，代次采用十进制字符串比较，避免超过 Lua 数字精度时误判。
同代次 PAUSED 或更高代次拒绝旧发布；只有持有 PG 有效代次锁的生产写入者可以推进 ACTIVE。
缺失 Redis 控制标记时，只有生产写入者经过 PG 校验才能重建；Redis 本身不是授权源。

生产 `UserProfileService` 必须注入发布屏障；包内无 PG 构造仅用于 Redis 传输层隔离测试。
数据库/Redis 失败时跳过可选增强，不将业务回答判为失败。

## 读取

Router 每次读取都联查用户生命周期与请求准入哈希，并在 PG `FOR SHARE` 生命周期锁内，
通过 Lua 原子核对 Redis 请求所有者、有效代次与 ACTIVE 标记后读取正文。
没有登录用户、没有准入、暂停、旧代次、缺失控制标记或错误 Redis 类型均不提供画像。
因此 PG 已暂停而 Redis 尚未清理时也不可读取旧投影；恢复 Redis 的旧副本不能替代 PG 准入。

`UserProfileContextAwaiter` 不再将正文存入 1 小时 Caffeine 缓存，仅保存选择指纹和完成状态。
后续图节点重新验证存储；若正文变化、暂停或到期则省略画像，不在同一请求中偷偷更换版本。
首轮等待仍受原有最大预算约束，后续重读最多 100ms；线程池满、锁等待/存储超时则跳过。
取消是协作式的，不能保证驱动立即退出，也无法撤回已经交给下游或供应商的上下文。

## 验证与边界

- 本地 299 项 Java 测试、22 项 Python 检查通过，无失败/跳过。
- 真实 PG/Redis 用例：12 项原子发布与代次比较、4 项跨存储发布锁、10 项受控读取。
- CI 的 Redis 并发门禁增加独立 PostgreSQL 服务及执行数量断言，禁止以跳过代替通过。
- 发布只升级 Consumer 和 Router，不修改前端、数据库结构、账号、订单、聊天和死信消息。
- 持久控制标记不是清理回执。本批没有对任意用户执行暂停、删除、恢复或重放业务请求。

仍需补齐：旧提取入口的请求准入、旧 Agent 文件逐实例清理、派生提示词/执行图/缓存副本、
可恢复任务及逐目标回执、可信身份授权入口和备份恢复演练。它们完成前保持全量删除入口关闭。

## 线上记录

- 发布目录 `/opt/smart-assistant/releases/profile-lifecycle-20260919`；回滚备份为
  `/opt/smart-assistant/backups/before-profile-lifecycle-20260919`。健康与宿主/容器包校验通过。
- 服务器隔离测试 26 项通过，测试容器及网络已清理；本机测试 Redis/PG 已停止。
- 公网 7 项价格、规格、颜色和订单澄清场景通过，7 条准入均为有效代次 0。
  新发布的投影同时验证用户索引、所有者和 ACTIVE 控制标记；没有实际提交订单操作。
- 业务/画像/恢复队列均无待处理或未确认消息；旧业务死信 4 条保持不变。
- Consumer SHA-256：`9411db72309531c13fc213be3bed06126333c804cb1a81a6c97e1abd667a5bf6`。
- Router SHA-256：`82045dfaf96e39123cc19cd98ec6cf1db5bb48fa5d5ac1a90766663e2a684ebe`。
- 证据文件：`isolated-lifecycle-tests.log`、`deployment.json`、`after-artifacts.json`、
  `verification.json`、`admission-verification.json`，均在发布目录内。
