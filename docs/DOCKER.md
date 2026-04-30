# SmartAssistant 项目 - Docker 镜像清单

## 基础设施镜像（docker-compose.yml）

| 镜像 | 版本 | 用途 | 端口 |
|------|------|------|------|
| `redis` | 7.2.4 | 缓存 / 消息队列 | 6379 |
| `nacos/nacos-server` | v3.1.0 | 服务注册与发现 | 8848, 9848 |
| `openzipkin/zipkin` | latest | 分布式链路追踪（兼容 Zipkin 协议） | 9411 |

## 监控栈镜像（monitoring/docker-compose.yml）

| 镜像 | 版本 | 用途 | 端口 |
|------|------|------|------|
| `prom/prometheus` | latest | 指标采集 | 9090 |
| `grafana/grafana` | latest | 可视化监控面板 | 3000 |
| `quay.io/jaegertracing/all-in-one` | 1.58 | 分布式追踪 UI | 16686, 4317-4318 |
| `grafana/loki` | 2.9.0 | 日志聚合 | 3100 |
| `grafana/promtail` | 2.9.0 | 日志收集 | - |
| `prometheuscommunity/postgres-exporter` | v0.15.0 | PostgreSQL 监控指标 | 9187 |
| `oliver006/redis_exporter` | v1.58.0 | Redis 监控指标 | 9121 |

> **注意**：监控栈镜像使用 `docker.m.daocloud.io` 镜像加速，国内环境可直接使用。

## 外部依赖（需手动安装）

| 软件 | 版本要求 | 说明 |
|------|----------|------|
| **PostgreSQL** | 18.x | 主数据库，需安装 pgvector 扩展 |
| **Java** | 17+ | 后端运行时 |
| **Node.js** | 18+ | 前端运行时 |
| **Maven** | 3.9+ | 后端构建工具 |

## 快速启动

```bash
# 1. 启动基础设施
docker-compose up -d

# 2. 启动监控栈（可选）
cd monitoring && docker-compose up -d

# 3. 初始化数据库
psql -h localhost -U postgres -d a2a_system -f docs/database/schema.sql
psql -h localhost -U postgres -d a2a_system -f docs/database/seed_data.sql

# 4. 配置环境变量（复制 .env.example 并填入真实值）
cp .env.example .env

# 5. 启动后端服务
powershell -ExecutionPolicy Bypass -File start-all.ps1
```
