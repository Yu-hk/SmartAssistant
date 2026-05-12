# SmartAssistant — 多智能体对话系统

[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.8-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-brightgreen)](https://docs.spring.io/spring-ai/reference/)
[![React](https://img.shields.io/badge/React-18-61DAFB)](https://react.dev/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

> 基于 Spring AI Alibaba + A2A 协议的多智能体对话平台，集成 DeepSeek V4-Flash 大模型 + DashScope 多模态能力。
> 支持多 Agent 协同、三层路由兜底、**图片解析/文生图**、LLM 叙事摘要、语义缓存、全链路监控。

---

## 目录

- [项目简介](#项目简介)
- [核心亮点](#核心亮点)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [服务说明](#服务说明)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
  - [1. 启动基础设施](#1-启动基础设施)
  - [2. 配置环境变量](#2-配置环境变量)
  - [3. 构建项目](#3-构建项目)
  - [4. 启动服务](#4-启动服务)
- [详细部署流程](#详细部署流程)
- [配置说明](#配置说明)
- [监控体系](#监控体系)
- [API 文档](#api-文档)
- [常见问题](#常见问题)
- [开发指南](#开发指南)

---

## 项目简介

SmartAssistant 是一个多智能体对话系统，基于 **Spring AI Alibaba** 框架和 **A2A (Agent-to-Agent)** 协议实现。系统通过 **智能路由器** 将用户请求分发到不同的专业 Agent，支持：

- **Travel Agent**：出行规划、地点查询、天气预报
- **Food Agent**：美食推荐、菜系查询、附近餐厅
- **General Agent**：闲聊陪伴、问答、新闻热点
- **Consumer 聚合**：统一对话入口，上下文管理，用户画像

采用 **Agentic RAG** 架构，支持多轮推理、语义缓存、向量检索，并通过 **Prometheus + Grafana + Jaeger** 实现全链路可观测性。

---

## 核心亮点

| 特性 | 说明 |
|------|------|
| 🧠 **A2A 协议** | 基于阿里 Spring AI Alibaba 的 Agent-to-Agent 通信协议，支持 Agent 自动发现和注册 |
| 🚦 **三层兜底路由** | 关键词匹配 → Fallback Agent(priority) → 内联 ChatClient，逐级降级，避免随机路由 |
| 🖼️ **多模态 AI** | 集成图片解析(analyzeImage) + 文生图(generateImage)，基于 DashScope 通义万相 |
| 🗂️ **文件级用户记忆** | 偏好存 `data/users/{userId}/preferences.json`；有价值对话经 LLM 叙事摘要后存 `memories/*.md` |
| 📝 **叙事摘要沉淀** | 轮数≥3 且内容≥1000 字符时自动触发 LLM 第三人称摘要，提取事实信息，去除对话填充语 |
| 💬 **回复风格切换** | General Agent 支持用户指定幽默/文言文/段子手等多种回复风格 |
| 🛡️ **AST 级 SQL 防护** | 基于 jsqlparser 的表名白名单校验，精确到 SQL AST 节点，杜绝注入 |
| 🔄 **自纠错与实时核验** | `queryCorrections` 工具查询历史修正记录避免重复错误；Travel Agent 在检索游记后自动调用高德 API 核验门票、开放时间等时敏数据 |
| 📊 **全栈可观测** | Prometheus 指标 + Grafana 仪表盘 + Jaeger 链路追踪 + Loki 日志聚合 |
| 🗂️ **多样性 RAG** | Agentic RAG + Text-to-SQL RAG + Corrective RAG + pgvector 语义检索 + 多路召回 |
| 🌐 **前端** | React + TypeScript + TDesign 管理界面，WebSocket 实时流式对话 |

| 🧩 **Service 层分类** | Router/Food/Travel 的 service 类按功能子包组织（core/agent/cache/rag/data 等） |
| 📝 **提示词外部化** | Travel/Food/General Agent 系统提示词独立为 `.txt` 文件，修改无需重新编译 |
| 🏷️ **工具调用信号** | Agent 真实检测工具调用（扫描 ToolResponseMessage），替代意图标签猜测 |
| 🔐 **密码默认值清零** | 所有服务 PostgreSQL/Redis/Nacos 密码默认值移除，未配置时启动即报错 |
| 🚦 **搜索级联降级** | DuckDuckGo → tenapi → Bing 三级搜索降级，无需 API Key |
| 🧠 **四层语义缓存** | 精确匹配 → 关键词哈希匹配(分词→MD5) → LLM 语义标签 → 前缀匹配；回复缓存按 Agent 类型+问题内容动态 TTL（天气20min/景点12h/美食12h） |
| 🐳 **Docker 容器化部署** | Dockerfile + docker-compose.deploy.yml，7 个服务一键容器化构建部署 |
| 🔧 **Maven Wrapper** | `mvnw` 自动分发 Maven 3.9.6，无需本地安装，构建环境统一 |
| 🚦 **优雅关闭** | 所有服务配置 `server.shutdown: graceful`，确保处理中请求完成再退出 |
| 🛡️ **Actuator 安全加固** | Actuator 端点白名单收紧，仅暴露 `/health`、`/info`、`/prometheus` |
| 🔐 **JWT 密钥强制配置** | JWT 签名密钥移除硬编码默认值，必须通过环境变量设置，未配置启动即报错 |
| ✅ **全量验证脚本** | `verify-all.ps1` 一键运行编译 + 测试，提交前快速自检 |
| ⏳ **请求排队 + SSE 通知** | Semaphore 限流 LLM 并发（默认 5），超出自动排队，SSE 实时推送排队位置 |

---

## 系统架构

```text
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│   Frontend  │────▶│   Gateway    │────▶│     Router      │
│  React:3001 │     │  :8081 (JWT) │     │  :8083 (意图识别) │
└─────────────┘     └──────────────┘     └────────┬────────┘
                                                   │
                         ┌─────────────────────────┼──────────┐
                         │                         │          │
                   ┌─────▼──────┐          ┌───────▼──────┐   │
                   │  Consumer  │          │   General    │   │
                   │  :8082     │          │   :8087      │   │
                   │ (会话管理)   │          │  (闲聊+多模态)  │   │
                   └─────┬──────┘          │  🖼️图解析/生图 │   │
                         │                 └──────────────┘   │
              ┌──────────┼──────────┐                          │
         ┌────▼────┐ ┌──▼────┐ ┌───▼────┐                     │
         │  Travel │ │ Food  │ │  User  │                     │
         │  :8085  │ │:8084  │ │  :8086 │                     │
         │(出行规划) │ │(美食)  │ │(认证)   │                     │
         └─────────┘ └───────┘ └────────┘                     │
                                                              │
                   ┌─────────────────────────────────────┐    │
                   │         Infrastructure               │    │
                   │  Redis ─ Nacos ─ PostgreSQL ─ Zipkin │    │
                   │  Prometheus ─ Grafana ─ Loki ─ Jaeger│    │
                   └─────────────────────────────────────┘────┘
```

### 请求流程

1. **前端请求** → Gateway (JWT 认证 + 限流)
2. **Gateway 转发** → Router (关键词匹配 + 意图识别)
3. **Router 路由** → Consumer (会话管理 + 价值评估)
4. **Consumer 调度** → 对应 Agent (Travel / Food / General)
5. **Agent 响应** → 通过 SSE 流式返回给前端
6. **价值评估** → 轮数≥3 或触发工具调用时触发 → `data/users/{userId}/memories/`（异步增量追加）
   - 同 session 的记忆**追加到同一文件**，不覆盖，保留完整对话历史
   - 轮数≥3 且内容≥1000 字符时触发 LLM 叙事摘要
    - 摘要替代原对话内容（清空冗余），超长内容截断部分原文追加，信息零丢失

---

## 技术栈

### 后端

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17+ |
| 框架 | Spring Boot | 3.4.8 |
| AI 框架 | Spring AI Alibaba | 1.1.2 |
| 模型 | DeepSeek V4-Flash / DashScope text-embedding-v4 | — |
| 注册/配置中心 | Nacos（元数据支持动态管理） | 3.1.0 |
| 缓存 | Redis | 7.2+ |
| 数据库 | PostgreSQL (pgvector) | 16+/18+ |
| ORM | MyBatis-Plus | 3.5+ |
| 分词 | HanLP | 1.8.4 |
| SQL 解析 | jsqlparser | 4.9 |
| 构建 | Maven Wrapper | 3.9.6 |

### 前端

| 分类 | 技术 |
|------|------|
| 框架 | React 18 + TypeScript |
| UI 库 | TDesign |
| 通信 | WebSocket / SSE |
| 构建 | Vite |

### 监控

| 工具 | 用途 | 端口 |
|------|------|------|
| Prometheus | 指标收集 | 9090 |
| Grafana | 可视化仪表盘 | 3000 |
| Jaeger | 链路追踪 | 16686 |
| Loki | 日志聚合 | 3100 |
| Promtail | 日志采集 | — |
| Zipkin | 追踪兼容层 | 9411 |

---

## RAG 召回管道

系统在 Travel 模块实现了完整的 RAG 召回管道，用于从用户游记中检索相关内容增强 LLM 回答。

### 处理流程

```text
用户查询 (location + query)
    │
    ├── Multi-Query 查询改写 (LLM)
    │     ├── 原始查询："北京 有什么好玩的"
    │     ├── 变体 1：  "北京热门景点 游玩攻略"
    │     ├── 变体 2：  "北京旅游 必去地点 推荐"
    │     └── 变体 3：  "北京 周末去哪儿 好玩的地方"
    │
    ├── 每个变体独立执行多路检索 (候选数 = topK × 3)
    │   ├── 向量检索  ─── pgvector HNSW 索引 ── 语义相似度
    │   └── 全文检索  ─── tsvector GIN 索引  ── 关键词精确匹配
    │
    ├── RRF 融合 (所有变体 × 所有检索路径的结果合并)
    │     └── score = Σ(1 / (K + rank_i))，K=60
    │
    ├── 重排序
    │     └── 按 RRF 分数降序 → 阈值过滤
    │
    └── Top-K 返回
```

### 数据库索引

| 表 | 索引 | 类型 | 作用 |
|----|------|------|------|
| `travel_note_chunks` | `idx_chunks_embedding_hnsw` | HNSW (cosine) | 向量检索加速 |
| `travel_note_chunks` | `idx_chunks_tsvector_gin` | GIN | 全文检索 |
| `travel_note_chunks` | `idx_chunks_note_id` | B-tree | JOIN 加速 |
| `restaurant_reviews_vector` | `idx_reviews_embedding_hnsw` | HNSW (cosine) | Food 向量加速 |
| `restaurant_reviews_vector` | `idx_reviews_tsvector_gin` | GIN | Food 全文检索 |
| `restaurant_reviews_vector` | `idx_reviews_city_cuisine` | B-tree | 多维过滤 |

### 索引部署

```powershell
$env:PGPASSWORD='postgres123'; & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -U postgres -d a2a_system -f "smart-assistant-travel/src/main/resources/sql/v20260508__add_rag_indexes.sql"
```

---

## 语义缓存

系统在 Router 模块实现了四层语义缓存，显著降低 Agent 调用压力和 LLM 成本。

### 缓存架构

```
getCachedDecision(question)
├── Tier 1: 精确匹配 MD5(question)          → ~1ms    Redis 查询
│   └── 命中后如果 reply 不存在，兜底检查 keyword reply
│
├── Tier 2: 关键词哈希匹配(分词→排序→MD5)   → ~5ms    无需 LLM
│   └── 使用 ChineseTokenizer(IKAnalyzer+HanLP) 提取关键词
│   └── "上海天气怎么样" 和 "上海天气如何" → 相同 hash
│   └── 回复缓存首次执行后立即保存（无阈值限制）
│
├── Tier 3: LLM 语义标签生成 + MD5(tag) 匹配 → 1-3s   LLM 兜底
│   └── 仅同用户 + 不同表述时调用 LLM 改写回复
│
└── Tier 4: 前缀匹配(前8字符)               → ~1ms    Redis 查询
```

### 缓存写入时机

| 缓存类型 | Key | 写入时机 | TTL |
|---------|-----|---------|:---:|
| 路由决策 | `a2a:route:semantic:{md5(intentTag)}` | 每次路由后 | 24h |
| 精确映射 | `a2a:route:exact:{md5(question)}` | 每次路由后 | 24h |
| 关键词路由 | `a2a:route:keyword:{md5(keywords)}` | 每次路由后 | 24h |
| **关键词回复** | `a2a:route:keyword:reply:{md5(keywords)}` | **每次 Agent 执行后立即** | 动态 |
| 意图回复 | `a2a:route:reply:{md5(intentTag)}` | 被问到 ≥2 次后 | 动态 |

### 动态 TTL（按 Agent 类型 + 问题内容）

| Agent | 场景 | TTL | 说明 |
|:-----|------|:---:|------|
| location_weather | 纯天气查询 | **20min** | 气温/降水实时更新 |
| location_weather | 景点/推荐/去哪 | **12h** | 景区信息几乎不变 |
| food_recommendation | 今日推荐 | **2h** | 每日特价更新 |
| food_recommendation | 一般美食查询 | **12h** | 餐厅信息稳定 |
| general_chat / builtin | 闲聊/问答 | **2h** | 事实性回答稳定 |

### 回复前缀个性化

根据提问者是否为同一用户，自动选择不同前缀：

| hitCount | 同用户 | 不同用户 |
|:--------:|--------|----------|
| == 2 | `再帮你查一次，结果和之前一样～` | `查询结果如下：` |
| ≥ 3 | `（以下是我之前查到的信息）` | `（以下是根据历史查询获取的信息）` |
| > 6h | `📅 根据6小时前查询的信息` | `📅 以下信息来源于6小时前的数据` |

### 性能效果

| 场景 | 典型耗时 | Agent 调用 |
|:----|:-------:|:---------:|
| 冷启动（无缓存） | 5-18s | ✅ 执行 |
| 路由缓存命中（Tier 1/2 无回复） | 3-15s | ✅ 执行 |
| **全缓存命中（Tier 2 + 回复）** | **1-5ms** | **❌ 跳过** |
| LLM 语义匹配命中（同用户改写） | 1-3s | ❌ 跳过 |

每意图仅需 **1-2 次 Agent 执行**（改前需 5 次），缓存命中时延迟从 5-18s 降至 **1-5ms**。

---

## 服务说明

| 服务 | 端口 | 职责 |
|------|------|------|
| **Gateway** | 8081 | API 统一入口，JWT 认证，Redis 限流，负载均衡 |
| **Consumer** | 8082 | 对话聚合，价值评估，用户画像（文件存储），记忆沉淀；提供 `/api/data/query` 数据查询独立端点 |
| **Router** | 8083 | 关键词路由，Agent 调度，**四层语义缓存**（精确→关键词→LLM→前缀，动态 TTL），Nacos 服务发现；`service/` 按 core/agent/cache/infrastructure/extraction/rag 子包组织 |
| **Travel** | 8085 | 出行规划，地点查询，天气预报，景点信息（RAG）；`service/` 按 rag/data/infrastructure 子包组织 |
| **Food** | 8084 | 美食推荐，菜系查询，附近餐厅搜索；`service/` 按 core/search/infrastructure 子包组织 |
| **User** | 8086 | 用户注册登录，JWT Token 签发，角色管理 |
| **General** | 8087 | 闲聊问答，新闻热点，单位转换，**图片解析/文生图**，支持风格切换 |

---

## 环境要求

### 开发环境

- **操作系统**：Windows 10/11 或 macOS / Linux
- **Java**：JDK 17+（推荐 Eclipse Temurin）
- **Maven**：3.9+（可选，Maven Wrapper 可自动下载）
- **Node.js**：18+（前端构建）
- **Docker**：24+（基础设施服务）
- **Git**：2.x（版本管理）

### 一键安装脚本

```powershell
# 检查依赖
java -version
.\mvnw.cmd --version   # Maven Wrapper 自动处理
node -v
docker --version
```

---

## 快速开始

### 1. 启动基础设施

```powershell
# 启动 Redis + Nacos + Zipkin
cd D:\workspace\SmartAssistant
docker-compose up -d

# 验证
docker ps --format "table {{.Names}}\t{{.Status}}"
```

### 2. 配置环境变量

```powershell
# 从模板创建配置文件
cd D:\workspace\SmartAssistant
copy .env.example .env

# 编辑 .env 填入真实 API Key（DeepSeek、DashScope 等）
# ⚠️ .env 已加入 .gitignore，不会提交到版本控制
```

关键变量说明：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek 大模型 API Key | 必填 |
| `DASHSCOPE_API_KEY` | DashScope API Key（Embedding） | 必填 |
| `AMAP_API_KEY` | 高德地图 API Key | 可选 |
| `POSTGRES_PASSWORD` | PostgreSQL 密码 | `postgres123` |
| `app.data.dir` | 数据存储根目录 | `data` |
| `jwt.secret` | JWT 签名密钥 | 建议自行生成 |

### 3. 构建项目

```powershell
cd D:\workspace\SmartAssistant

# Maven Wrapper 自动下载指定版本 Maven，无需本地安装
.\mvnw.cmd install -pl smart-assistant-common -DskipTests

# 全量编译（跳过测试）
.\mvnw.cmd compile -DskipTests

# 全量打包
.\mvnw.cmd package -DskipTests -Dmaven.test.skip=true

# 前端（React + TypeScript + TDesign）
cd frontend && npm install && cd ..
```

### 4. 启动服务

#### 方式一：一键启动（推荐）

```powershell
powershell -ExecutionPolicy Bypass -File start-all.ps1
```

提交前验证（编译 + 测试）：

```powershell
powershell -ExecutionPolicy Bypass -File verify-all.ps1
```

#### 方式二：手动逐个启动

```powershell
# ⚠️ 清除 SERVER__PORT 环境变量（双下划线会覆盖 server.port）
Remove-Item Env:SERVER__PORT

$env:DEEPSEEK_API_KEY = "sk-xxx"
$env:DASHSCOPE_API_KEY = "sk-xxx"

# 先启动基础服务
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar", "--server.port=8081" -WindowStyle Hidden
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar", "--server.port=8086" -WindowStyle Hidden

# 等待 10-15 秒后启动其他服务（等 General 注册到 Nacos）
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar", "--server.port=8082" -WindowStyle Hidden
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar", "--server.port=8083" -WindowStyle Hidden
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "smart-assistant-travel\target\smart-assistant-travel-1.0.0-SNAPSHOT.jar", "--server.port=8085" -WindowStyle Hidden
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "smart-assistant-food\target\smart-assistant-food-1.0.0-SNAPSHOT.jar", "--server.port=8084" -WindowStyle Hidden
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "smart-assistant-general\target\smart-assistant-general-1.0.0-SNAPSHOT.jar", "--server.port=8087" -WindowStyle Hidden

# 前端
cd frontend && npm run dev
```

#### 方式三：IDE 开发（IntelliJ IDEA）

1. 打开项目根目录
2. 设置运行配置的环境变量（指向 `.env` 中的值）
3. 按依赖顺序启动：
   - 先启动 Nacos、Redis（docker-compose）
   - 再启动 Gateway → User → Consumer → Router → Travel → Food → General（无严格顺序要求，但 Router 依赖 Nacos）

---

## 详细部署流程

### 数据库初始化

```powershell
# 创建数据库
psql -h 127.0.0.1 -U postgres -c "CREATE DATABASE a2a_system;"

# 安装 pgvector 扩展
psql -h 127.0.0.1 -U postgres -d a2a_system -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 执行初始化脚本
$env:PGPASSWORD='postgres123'; & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -U postgres -d a2a_system -f "docs/database/schema.sql"
$env:PGPASSWORD='postgres123'; & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -U postgres -d a2a_system -f "docs/database/seed_data.sql"

# ⚠️ chat_messages 相关表已废弃，由 data/users/{userId}/memories/ 替代
# 如需删除旧表，执行：docs/database/cleanup_v20260508.sql
```

### 启用 pg_stat_statements（可选，SQL 性能监控）

```sql
-- 1. 修改 postgresql.conf
-- shared_preload_libraries = 'pg_stat_statements'

-- 2. 重启 PostgreSQL 后执行
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

### 监控栈部署

```powershell
cd monitoring
docker-compose -f docker-compose.yml up -d
```

监控组件：

| 组件 | 访问地址 | 说明 |
|------|---------|------|
| Grafana | http://localhost:3000 | 仪表盘（默认 admin/admin） |
| Prometheus | http://localhost:9090 | 指标查询 |
| Jaeger | http://localhost:16686 | 链路追踪 |
| Loki | http://localhost:3100 | 日志查询 |
| Zipkin | http://localhost:9411 | 追踪兼容层 |

### Docker 容器化部署

项目提供完整的 Docker 容器化方案，支持一键构建部署全部 7 个服务：

```powershell
# 1. 从项目根目录构建
cd D:\workspace\SmartAssistant

# 2. 一键构建并启动所有服务
docker compose -f docker-compose.deploy.yml build
docker compose -f docker-compose.deploy.yml up -d

# 3. 验证
docker compose -f docker-compose.deploy.yml ps
```

各服务独立构建（适用于单独更新某个服务）：

```powershell
docker compose -f docker-compose.deploy.yml build gateway
docker compose -f docker-compose.deploy.yml up -d gateway
```

部署文件说明：

| 文件 | 用途 |
|------|------|
| `Dockerfile` | 多阶段构建（打包 → JRE 运行） |
| `docker-compose.deploy.yml` | 编排全部 7 个服务 + 基础设施 |
| `.dockerignore` | 排除本地配置和构建缓存 |

---

## 配置说明

### 配置层级（优先级从高到低）

1. **命令行参数**：`--server.port=8082`
2. **OS 环境变量**：`DEEPSEEK_API_KEY=xxx`
3. **`.env` 文件**：项目根目录（自动加载）
4. **`application.yml`**：各服务的配置文件
5. **默认值**：`${VAR:defaultValue}` 语法

### 优雅关闭

所有服务已配置优雅关闭（2026-05-11），确保服务停止时处理中的请求完成后再退出：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: "30s"
```

无需额外操作，`kill` 或 `Ctrl+C` 时自动生效。

### 关键配置目录

```
smart-assistant-{service}/src/main/resources/
├── application.yml           # 服务配置
├── logback-spring.xml        # 日志配置
├── prompts/                  # 系统提示词（外部化，改提示词无需重新编译）
│   ├── travel-system-prompt.txt
│   ├── food-system-prompt.txt
│   └── general-system-prompt.txt
└── config/
    └── mcp-table-whitelist.yml  # MCP 表访问白名单
```

### 系统提示词外部化

各 Agent 服务的系统提示词已从 Java 代码中提取到独立的资源文件，修改无需重新编译：

```
smart-assistant-{service}/src/main/resources/prompts/
├── travel-system-prompt.txt    # 出行规划助手
├── food-system-prompt.txt      # 美食推荐助手
└── general-system-prompt.txt   # 通用对话助手
```

加载方式：`@Value("classpath:prompts/{service}-system-prompt.txt")`，IO 异常时自动降级为默认提示词，不影响服务启动。

### Service 包结构

```
smart-assistant-router/.../service/
├── core/          RouterService, SmartRoutingService
├── agent/         AgentCallerService, AgentDiscoveryService, AgentHealthChecker, AgentVersionNegotiator
├── cache/         SemanticRouteCacheService, RoutingDecisionStorageService
├── infrastructure/ DistributedTracingService
├── extraction/    KeywordExtractionService
└── rag/           RouterRagService

smart-assistant-food/.../service/
├── core/          ABTestService, HybridRecommendationService, ReviewEmbeddingInitializer
├── search/        RestaurantReviewSearchService
├── infrastructure/ DistributedTracingService
├── agent/         StreamingFoodAgentService
└── monitoring/    FoodMetricsCollector

smart-assistant-travel/.../service/
├── rag/           TravelRagService, TravelNoteService, TravelNoteMatchService, TravelNoteRankingService,
│                  SemanticChunker, EmbeddingService, RecallService, AttractionVectorService
├── data/          DatabaseAttractionService, AttractionDataImportService, AmapPoiSyncService,
│                  DataQualityValidator
├── infrastructure/ DistributedTracingService
├── agent/         McpAgentService, StreamingTravelAgentService
└── monitoring/    TravelMetricsCollector
```

### 查看日志

所有服务的日志统一输出到 `logs/` 目录，格式为 `{spring.application.name}.log`：

```powershell
# 应用日志（纯净 Spring Boot 输出，推荐优先查看）
type logs\router-service.log -Tail 50
type logs\travel-service.log -Tail 50
type logs\food-service.log -Tail 50
type logs\consumer-service.log -Tail 50
type logs\api-gateway.log -Tail 50
type logs\user-service.log -Tail 50

# 标准输出日志（含启动横幅等）
type logs\Router-stdout.log -Tail 30
type logs\Travel-stdout.log -Tail 30
```

### ⚠️ 密码配置强制要求（2026-05-11）

从 v1.0 起，以下密码不再有默认值，**必须通过环境变量设置**，未设置时服务启动即报错：

| 变量 | 默认值（已移除） | 说明 |
|------|:--------------:|------|
| `JWT_SECRET` | `a2a-demo-secret-...` | JWT 签名密钥 |
| `POSTGRES_PASSWORD` | `postgres123` | PostgreSQL 密码 |
| `REDIS_PASSWORD` | `redis123` | Redis 密码 |
| `NACOS_PASSWORD` | `nacos123` | Nacos 密码 |

### 用户数据存储

所有用户数据存储在 `data/users/{userId}/` 目录下，不再依赖数据库：

```
data/users/{userId}/
├── preferences.json            # 用户偏好（权重、意图分布）
└── memories/
    ├── 2026-05-08_session_abc.md   # 增量追加记忆文件
    └── 2026-05-08_session_def.md
```

记忆文件采用 **session 优先的增量追加**模式。同 session 始终追加到同一文件（跨天不换文件），文件名为首次创建的日期：

```yaml
---
created_at: 1746680000000
session: session_abc
turn_range: 3-5
entries: 3
---

> Turn 3 | narrative | intent: 景点查询

用户咨询了景点信息，系统推荐了故宫和天坛...

---

> Turn 4 | raw | intent: 美食推荐

用户：有川菜推荐吗？
助手：眉州东坡、辣婆婆...

---

> Turn 5 | narrative | intent: 旅游规划

用户继续咨询三日游行程安排，系统制定了详细规划...
```

每个条目通过 `> Turn {n} | {format} | intent: {intentTag}` 标记轮次和格式：
- **`narrative`** — LLM 第三人称叙事摘要（轮数≥3 且内容≥1000 字符时触发）
- **`raw`** — 原文保存（内容不足摘要阈值）
- 超 8000 字符时内容被安全截断，截断部分以 `---` 分隔直接原文追加

### 全局纠错记录

Agent 的用户修正记录存储在 `data/corrections/` 目录，所有用户共享，用于避免重复错误：

```
data/
├── users/{userId}/memories/       # 用户私人记忆（增量追加）
└── corrections/
    ├── travel.md                   # 出行修正记录（全局共享）
    ├── food.md                     # 美食修正记录
    └── general.md                  # 通用修正记录
```

修正记录格式：

```markdown
# Travel Agent 用户修正记录

## 2026-05-11

> 主题: 故宫开放时间
> 错误: 故宫8:00开门
> 正确: 故宫冬季8:30开门，夏季8:00开门
> 来源: userId=123
```

各 Agent 的 system prompt 强制要求：回答事实性问题前先调用 `queryCorrections(topic)` 工具，有匹配修正时以修正信息为准。



---

## 请求排队

系统基于压测数据实现了请求排队机制，在 LLM 并发槽位（默认 5）占满时自动排队，通过 SSE 实时通知用户排队状态。

### 工作流程

```
用户发送消息
  │
  ├→ SSE 连接建立
  │
  ├→ [有槽位] event: processing → 正常 SSE 流 → event: done
  │
  └→ [无槽位] event: queued {position: 3, estimatedWaitMs: 15000}
              │
              ├→ Semaphore.tryAcquire(60s 超时) 阻塞等待
              │
              ├→ [获取到槽位] event: processing → 正常 SSE 流 → event: done
              └→ [超时] event: timeout → 连接关闭
```

### SSE 事件类型

| 事件 | 说明 | 数据 |
|------|------|------|
| `queued` | 进入排队 | `{position, estimatedWaitMs}` |
| `processing` | 槽位分配，开始处理 | `{}` |
| `timeout` | 排队超时 | `{content: "排队超时，请稍后重试"}` |
| `waiting` | 等待路由决策 | `{content: "正在分析意图..."}` |
| `thinking` | AI 思考过程 | `{type: "thinking", content: "..."}` |
| `tool_call` | 工具调用 | `{type: "tool_call", name, input}` |
| `tool_result` | 工具执行结果 | `{type: "tool_result", content}` |
| `response` | 最终回复 | `{type: "response", content}` |
| `done` | 完成信号 | `{type: "done"}` |

### 配置项

在 `smart-assistant-consumer/application.yml` 中：

```yaml
chat:
  queue:
    max-concurrent: 5          # 最大并发 LLM 请求数（基于压测数据）
    max-queue-size: 50         # 最大排队长度
    queue-timeout-ms: 60000    # 排队等待超时（毫秒）
```

### 前端表现

排队中时，用户消息气泡下方显示：

```
⏳ 排队中，前面还有 3 人，预计等待 15 秒
```

槽位就绪后自动开始处理，用户无感知。

---

## API 文档

### 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/assistant/api/auth/login` | 用户登录，返回 JWT Token |
| POST | `/assistant/api/auth/register` | 用户注册 |
| GET | `/assistant/api/auth/me` | 获取当前用户信息 |

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/assistant/api/math/chat` | 发送消息（非流式，通过 Router 路由到对应 Agent） |
| POST | `/assistant/api/data/query` | ⭐ 数据查询（仅 ADMIN，独立端点，不混入对话流） |
| WebSocket | `/assistant/ws/conversation` | WebSocket 实时对话 |

### 通用助手工具（General Agent）

| 工具 | 说明 | 依赖 |
|------|------|------|
| `calculate(expression)` | 数学计算 | — |
| `convertTemperature/Length/Weight` | 单位转换 | — |
| `getHotNews()` | 网络热点新闻 | — |
| `searchWeb(query)` | 联网搜索 | — |
| `analyzeImage(imageUrl, question)` 🆕 | 图片解析 | DashScope API Key |
| `generateImage(prompt, size, n)` 🆕 | 文生图 | DashScope API Key |
| `convertCurrency(value, from, to)` 🆕 | 货币汇率转换 | 实时汇率 API |

### 路由接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/assistant/api/router/route` | 智能路由（意图识别 + Agent 调度） |

### 智能纠错工具（所有 Agent）

| 工具 | 适用 Agent | 说明 |
|------|-----------|------|
| `queryCorrections(topic)` | Travel / Food / General | 查询历史修正记录。Agent 在回答事实性问题前自动调用，检查是否有用户反馈过的修正信息并优先采用 |

### 健康检查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/actuator/health` | 服务健康状态 |
| GET | `/actuator/prometheus` | Prometheus 指标 |
| GET | `/actuator/info` | 服务信息 |

---

## 常见问题

### Q: 服务启动绑定到异常端口（如 63807）

检查是否设置了 `SERVER__PORT` 环境变量（注意是双下划线）。该变量会覆盖 `server.port` 配置，需要在 shell 中清除：

```powershell
# PowerShell
Remove-Item Env:SERVER__PORT

# CMD
set SERVER__PORT=
```

检查 `.env` 文件是否存在且包含正确的 `DEEPSEEK_API_KEY`。如果以 IDE 启动，需在运行配置中设置环境变量。

### Q: 服务启动失败 "DashScope API key must be set"

检查 `.env` 中的 `DASHSCOPE_API_KEY`。DashScope 用于 Embedding 和 A2A 服务发现（Router 必须配置）。

### Q: 中文请求返回"一串问号"

Windows 终端（PowerShell/cmd）默认使用 GBK 编码发送 JSON，服务端只接收 UTF-8。PowerShell 中需强制 UTF-8 编码：

```powershell
$body = @{ message='你的中文问题' } | ConvertTo-Json
$utf8Bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri 'http://localhost:8081/assistant/api/math/chat' -Method Post -Body $utf8Bytes -ContentType 'application/json; charset=utf-8' -Headers $headers
```

cmd 中先执行 `chcp 65001` 切换到 UTF-8 代码页。

### Q: 文生图/图片解析功能如何测试？

确保已设置 `DASHSCOPE_API_KEY` 环境变量，服务启动后调用 chat API：

```powershell
$headers = @{ 'X-User-Id' = '3075'; 'X-User-Role' = 'ROLE_USER' }
$body = @{ message='作为通用助手，帮我画一张夕阳下的海滩风景图' } | ConvertTo-Json
$utf8Bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri 'http://localhost:8082/api/math/chat' -Method Post -Body $utf8Bytes -ContentType 'application/json; charset=utf-8' -Headers $headers -TimeoutSec 120
```

也可直接运行测试脚本：
```powershell
powershell -ExecutionPolicy Bypass -File test-image.ps1
```

### Q: 编译报错"编码 UTF-8 的不可映射字符"

这是由于使用 PowerShell 的 `Set-Content` 命令操作含中文的 Java 文件时，默认使用了系统 ANSI 编码（GBK），导致中文字符损坏。

**修复方法**：使用 `git checkout -- <file>` 恢复原始文件，然后使用以下命令之一操作：
- Bash: `sed -i`（正确处理 UTF-8）
- PowerShell: `[System.IO.File]::WriteAllBytes($path, [System.Text.Encoding]::UTF8.GetBytes($content))`

### Q: Consumer 与 Router 边界不清如何处理？

2026-05-11 已重构：Consumer 不再将 JSON Prompt 塞入 `question` 字段，改为通过独立字段传递元数据（`userProfile`、`intentTag`、`requestId`、`sessionId`）。Router 不再需要 `extractRequestId()`/`extractThreadId()` 反解析。数据查询功能拆分为独立 `DataQueryController`。

### Q: 模型生效

当前使用 `deepseek-v4-flash`（非思考模式）。如果 DeepSeek 发布新模型，只需修改各服务 `application.yml` 中
`spring.ai.deepseek.chat.options.model` 的值，无需改代码。

### Q: Nacos 连接失败 "unauthorized"

Nacos 默认开启了认证（`NACOS_AUTH_ENABLE=true`），初始化 Nacos 后需要先通过 Web 控制台（http://localhost:8848/nacos）使用默认账号 `nacos` / `nacos123` 登录。所有服务的 `application.yml` 已配置认证信息。

### Q: Agent 回答明显错误如何修正？

直接在对话中指出错误即可，例如"不对，故宫是8:30开门"或"你说错了"。目前修正需要手动追加到 `data/corrections/{agent}.md` 文件。参考文件头部注释的格式添加条目后，下次 Agent 回答相关问题时将自动参考修正记录。

计划在后续版本中支持用户通过对话自动记录修正。

### Q: Redis 连接被拒绝

Redis 默认启用了密码认证（`redis123`），所有服务已配置 `password: ${REDIS_PASSWORD:redis123}`。如需修改密码，需要同时更新 `docker-compose.yml` 和所有服务的 `application.yml`。

### Q: PostgreSQL 连接失败

确认 PostgreSQL 已启动且 `a2a_system` 数据库已存在。默认连接配置在 `smart-assistant-user` 和 `smart-assistant-consumer` 的 `application.yml` 中。

### Q: 编译报错 "Unable to rename"

这是由于 Windows 文件锁导致的，常见于之前有 Java 进程未完全退出。执行：

```powershell
taskkill /F /IM java.exe
# 然后重新编译
.\mvnw.cmd clean compile -DskipTests
```

### Q: 前端页面空白

确认前端服务已启动（端口 3001），检查 Vite 代理配置是否正确。前端通过 `/api` 前缀代理到 Gateway 的 8081 端口。

### Q: 启用 Nacos 认证后现有服务连接不上

Nacos 认证只在初始启动时生效。如果已存在的 Nacos 实例需要重启才能启用认证：

```powershell
docker-compose restart nacos
```

---

## 开发指南

### 模块依赖关系

```
smart-assistant-common (核心工具：分词器、SQL 校验器、Dotenv、修正记录服务)
    ↑          ↑          ↑          ↑
Gateway   Consumer    Router     Travel / Food / User / General
(无common)   (common)   (common)   (common)
```

### 添加新的 Agent

1. 在 `smart-assistant-{agent}` 模块中实现 `@Tool` 方法
2. 配置 `mcp-table-whitelist.yml` 中的表访问权限
3. 在 `application.yml` 中配置基础 Nacos 注册信息
4. Agent 启动后会自动通过 Nacos 注册到 Router 的服务发现列表
5. 在 Nacos UI 中创建配置 `{serviceName}-metadata` (Group: `AGENT_META`) 设置 keywords/priority
6. 后续修改 Agent 职责只需改 Nacos Config，无需重新部署

### 运行测试

```powershell
# 全量测试
.\mvnw.cmd test -DskipTests=false

# 指定模块
.\mvnw.cmd test -pl smart-assistant-gateway

# 指定测试类
.\mvnw.cmd test -pl smart-assistant-common -Dtest=SqlSecurityValidatorTest
```

当前测试覆盖：

| 模块 | 测试数 | 覆盖内容 |
|------|--------|---------|
| common | 50 | SQL 安全校验器、中文分词器（IKAnalyzer/HanLP）、同义词、词性标注、意图识别、缓存 |
| gateway | 33 | JWT 工具、白名单过滤、Filter 认证、Filter 集成测试 |
| user | 9 | JWT 服务 |
| consumer | 25 | 对话叙事摘要、文档沉淀服务、DataGifTool、Chat 集成测试 |
| router | 29 | Agent 调用消息清理、思考内容过滤、关键词提取、语义缓存（4 层缓存 + 前缀个性化） |
| food | 34 | 美食推荐、菜系知识、餐厅搜索 |
| travel | 43 | 天气/景点工具、智能行程规划、RAG 召回匹配、MCP 权限测试 |
| general | 30 | 数学计算、温度/长度/重量/货币转换、边界条件 |
| **总计** | **238+** | **27 个测试文件，全模块覆盖** |

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2025-2026 SmartAssistant Project
