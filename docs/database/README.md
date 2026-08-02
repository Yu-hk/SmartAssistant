# 数据准备指南

本目录的推荐入口如下：

| 文件 | 用途 | 是否可重复执行 |
|---|---|---|
| `init-entities.sql` | 创建或补齐核心业务表、索引和 pgvector 扩展 | 是 |
| `init-seed.sql` | 写入最小演示数据 | 是 |
| `bulk_test_data.sql` | 追加压测/看板用批量数据 | 是，建议仅用于开发环境 |
| `concurrency_test_data.sql` | 生成可配置的多并发全流程验证数据 | 是，会恢复同业务键的基准状态 |
| `verify_concurrency_data.sql` | 校验并发数据量、关联完整性和场景覆盖 | 是，只读 |
| `cleanup_concurrency_data.sql` | 仅清理 `LOAD-*` 并发测试数据 | 是，有删除操作 |
| `verify.sql` | 校验扩展、表、关键列并输出数据量 | 是，只读 |
| `schema.sql` / `seed_data.sql` | 历史数据库快照，保留用于追溯 | 不作为新环境入口 |

## 一键准备

本机 PostgreSQL 映射到 `5433` 时：

```powershell
$env:POSTGRES_PASSWORD = '你的本地数据库密码'
.\scripts\prepare-data.ps1
```

数据库只暴露在 Docker 网络内时：

```powershell
.\scripts\prepare-data.ps1 -Mode Docker -ContainerName postgres
```

追加批量数据：

```powershell
.\scripts\prepare-data.ps1 -Mode Docker -ContainerName postgres -Bulk
```

准备多并发全流程数据（默认配置）：

```powershell
.\scripts\prepare-data.ps1 -Mode Docker -ContainerName postgres -LoadTest
```

自定义规模，例如为 500 个并发测试账号准备每人 30 个订单和 50 条路由记录：

```powershell
.\scripts\prepare-data.ps1 -Mode Docker -ContainerName postgres -LoadTest `
  -LoadUsers 500 -SessionsPerUser 5 -OrdersPerUser 30 -RoutesPerUser 50
```

只做校验：

```powershell
.\scripts\prepare-data.ps1 -Mode Docker -ContainerName postgres -VerifyOnly
```

## 最小演示数据

- 账号：`test_user` / `admin`，初始密码均为 `password`；仅限本地演示。
- 商品：5 条，覆盖手机、耳机、电脑、平板和手表。
- 订单：`ORD-1001` 至 `ORD-1005`，覆盖五种主要状态。
- 物流：在途与已签收各 1 条。
- 退款：待处理退款 1 条。
- 优惠券：满减、折扣、现金券各 1 条。
- 反馈：4 条，用于管理端统计和看板验证。

## 多并发验证数据

默认 `-LoadTest` 会准备以下数据，约 7 万条关联记录：

| 数据集 | 默认数量 | 覆盖场景 |
|---|---:|---|
| 用户 | 1,000 | 普通用户、管理员、并发登录 |
| 会话 | 5,000 | 活跃、过期、撤销，多设备 |
| 商品 | 120 | 8 个品类、3 种库存状态、宽价格区间 |
| 订单 | 20,000 | 待付款、待发货、已发货、已签收、已取消、退款中 |
| 物流 | 约 6,000 | 运输中、已签收及轨迹查询 |
| 退款/审批 | 约 7,000+ | 退款处理、取消审批和四种审批状态 |
| 优惠券 | 4,000 | 满减、折扣、现金券、已使用和过期 |
| 路由日志 | 30,000 | 四类 Agent、三种路由、成功/失败/超时 |
| 对话反馈 | 3,000 | 1–5 分评价及管理端关联查询 |

- 登录账号：`load_user_000001` 至 `load_user_001000`，统一测试密码 `password`。
- 固定样例订单：`ORD-LOAD000001003`；其他订单号遵循 `ORD-LOAD{6位用户序号}{3位订单序号}`，兼容订单服务的 `ORD-\w+` 提取规则。
- 数据使用 `LOAD-` / `ORD-LOAD` / `load_user_` 前缀，与最小演示数据和正常业务数据隔离。
- 重复运行会更新有唯一业务键的数据，并补齐缺失关联；适合在每轮全流程验证前恢复基准状态。
- 清理时执行 `cleanup_concurrency_data.sql`。该脚本只匹配上述测试前缀，但仍建议在开发环境使用。

## 注意事项

- Docker 的 `/docker-entrypoint-initdb.d` 只会在数据卷首次创建时自动执行。已有数据卷请运行 `prepare-data.ps1`，不要为了初始化而删除数据卷。
- `init-seed.sql` 不重置业务数据；已有同业务键记录会保留或更新演示字段。
- `bulk_test_data.sql` 仅用于本地测试，不应在生产库运行。
- `concurrency_test_data.sql` 包含统一测试密码和模拟失败日志，只能用于开发、测试或专用压测环境。
- 数据库备份、恢复和生产变更应走独立迁移流程，不应依赖种子脚本。
