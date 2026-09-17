# PG + pgvector 严格集成验证

2026-09-17 更新：原先连接本地业务库并自动探测/跳过的流程已停用。
不要再用 `docker-compose-infra.yml` 或 `a2a_system` 执行这些会清理表的测试。

## 隔离与失败规则

- 默认未设置 `-Dpg.integration=true` 时整类禁用，不自动连接任何数据库。
- 显式开启后必须提供 `PG_TEST_URL`、`PG_TEST_USER`、`PG_TEST_PASSWORD`。
- URL 仅接受回环地址、明确端口和 `smartassistant_integration` 库，禁止外部主机、业务库及额外 URL 参数。
- 连接、迁移资源、DDL 或清理失败都必须使测试失败；不再 catch 后 skip。
- 该测试会清理专用库的四张知识库测试表，并测试删除复核表后自愈。只能对临时测试实例运行。
- 不在生产主库验证破坏性测试，即使库名碰巧相同也不允许。

## 本地运行

需 Java 21、Maven、Python 3、Docker Compose v2；Git Bash 下：

```bash
MAVEN_EXE=mvn PYTHON_EXE=python3 bash scripts/verify-pg-rag-integration.sh
```

脚本使用独立 Compose 项目，来自 `deploy/docker-compose.integration.yml`：
PG 监听 `127.0.0.1:15433`，临时数据在容器 tmpfs。
端口冲突时启动失败，不能改去连接现有业务容器。脚本退出仅移除自身 Compose 项目。
测试凭据是临时隔离环境专用值，不能作为生产配置。

同时测试 MQ 可使用独立项目启动该文件内 RabbitMQ（`127.0.0.1:15673`），
设置 `CHAT_DISPATCH_TEST_RABBIT_PORT/USERNAME/PASSWORD` 后执行
`ChatDispatchBrokerIntegrationTest`。不要指向生产 broker。

## 覆盖与 CI

`PgVectorKnowledgeBaseIntegrationTest` 共 7 项：

1. V1 SQL 建表、vector 类型和 1024 维。
2. 相同 ID 增量 upsert，不全量 reindex。
3. active index_version 过滤。
4. 两个知识库对象共享 PG 读写。
5. pgvector 距离与排序。
6. ReviewQueueService 缺表自愈。
7. Parent/Child 回链与去重。

使用确定性 embedding stub，不验证真实 BGE 模型质量；
使用 ResourceDatabasePopulator 执行 V1 SQL，不宣称覆盖 Flyway 完整版本升级生命周期；
两个对象共享数据库不等于多进程故障演练。

CI 的 `Real PostgreSQL and RabbitMQ contracts` 使用 service containers 强制执行；
`verify_integration_reports.py` 检查报告存在、PG 至少 7 项执行、MQ 至少 1 项执行、
零跳过/错误/失败，防止只看到 BUILD SUCCESS 就误判。
Redis 在独立 `Session deletion and Redis concurrency gate` 中验证。
