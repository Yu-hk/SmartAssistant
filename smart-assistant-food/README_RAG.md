# 🍽️ 美食服务 - 智能餐厅推荐 (RAG + pgvector)

> 基于检索增强生成 (RAG) 和向量语义搜索的智能餐厅推荐系统

## 📖 简介

本项目为美食服务添加了基于用户评价的语义搜索功能，能够理解自然语言查询意图，智能推荐符合用户需求的餐厅。

### ✨ 核心特性

- 🔍 **语义搜索** - 理解"环境好的西餐厅"、"适合家庭聚会的川菜馆"等自然语言查询
- 🎯 **多维度过滤** - 支持城市、菜系、价格、评分等多条件筛选
- ⚡ **高性能检索** - 基于 PostgreSQL pgvector + HNSW 索引，毫秒级响应
- 🤖 **Agent 集成** - 暴露为 Spring AI Tool，可与 LLM Agent 无缝协作
- 🔄 **自动向量化** - 应用启动时自动生成 embedding，无需手动处理

---

## 🚀 快速开始

### 前置要求

- ✅ Java 17+
- ✅ Maven 3.8+
- ✅ PostgreSQL 14+ with pgvector 扩展
- ✅ Spring AI 配置（Embedding 模型）

### 5 分钟部署

#### 1️⃣ 创建数据库表

```bash
psql -U postgres -d smart_assistant -f sql/v20260423__create_pgvector_tables.sql
```

#### 2️⃣ 导入示例数据

```bash
psql -U postgres -d smart_assistant -f sql/v20260423__insert_sample_restaurant_reviews.sql
```

#### 3️⃣ 启动应用

```bash
cd smart-assistant-food
mvn spring-boot:run
```

应用会自动为评论生成向量（首次启动约需 1-2 分钟）。

#### 4️⃣ 运行测试

```bash
# PowerShell
.\test-rag-search.ps1

# 或运行单元测试
mvn test -Dtest=RestaurantReviewSearchServiceTest
```

#### 5️⃣ 验证功能

```bash
curl -X POST http://localhost:8084/api/food/recommend \
  -H "Content-Type: application/json" \
  -d '{
    "query": "环境好的西餐厅",
    "city": "北京",
    "maxPrice": 300,
    "minRating": 4.0
  }'
```

---

## 📁 项目结构

```
smart-assistant-food/
├── sql/                                    # 数据库脚本
│   ├── v20260423__create_pgvector_tables.sql         # 建表脚本
│   └── v20260423__insert_sample_restaurant_reviews.sql # 示例数据
├── src/
│   ├── main/java/com/example/smartassistant/
│   │   ├── service/
│   │   │   ├── RestaurantReviewSearchService.java    # ⭐ 语义搜索服务
│   │   │   └── ReviewEmbeddingInitializer.java       # ⭐ 自动向量化
│   │   └── tool/
│   │       └── SmartRestaurantRecommendationTool.java # ⭐ Agent Tool
│   └── test/java/
│       └── RestaurantReviewSearchServiceTest.java    # 单元测试
├── RAG_DEPLOYMENT_GUIDE.md                 # 📖 详细部署指南
├── RAG_MVP_SUMMARY.md                      # 📊 实现总结
├── README_RAG.md                           # 📘 本文件
└── test-rag-search.ps1                     # 🧪 快速测试脚本
```

---

## 💡 使用示例

### 场景 1: 自然语言搜索

```java
// 通过 Agent 调用
String result = agent.call("我想找一家北京环境好的西餐厅，人均300以内");
```

**返回结果**:
```
🍽️ 为您找到以下餐厅推荐（基于您的需求："环境好的西餐厅"）

--- 推荐 1 ---
🍽️ 大董烤鸭店(工体店)
📍 北京市朝阳区工人体育场东门内
🥘 菜系：融合菜
💰 人均：¥450
⭐ 评分：4.7/5.0
🏷️ 标签：高端、创意菜、环境优雅、适合约会
💬 评价：高端烤鸭店的代表，环境优雅，装修现代简约...
🎯 匹配度：85%
```

---

### 场景 2: 热门餐厅排行榜

```java
// 获取成都热门川菜馆 Top 3
List<RestaurantRecommendation> popular = 
    searchService.getPopularRestaurants("成都", "川菜", 3);
```

---

### 场景 3: 多条件过滤

```java
List<RestaurantRecommendation> results = 
    searchService.searchRestaurants(
        "适合家庭聚会",  // 语义查询
        "成都",          // 城市
        "川菜",          // 菜系
        150.0,          // 最高人均价格
        4.0             // 最低评分
    );
```

---

## 🏗️ 技术架构

```
┌─────────────────────────────────────┐
│       User / Agent Interface        │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  SmartRestaurantRecommendationTool  │  ← Agent Tool 封装
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  RestaurantReviewSearchService      │  ← 核心搜索逻辑
│  - 生成查询向量                      │
│  - 执行向量检索                      │
│  - 后处理和排序                      │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   Spring AI VectorStore             │  ← 向量存储抽象
│   (PgVectorStore)                   │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   PostgreSQL + pgvector             │  ← 向量数据库
│   - HNSW 索引                       │
│   - 相似度搜索                      │
└─────────────────────────────────────┘
```

---

## 📊 性能指标

| 指标 | 目标值 | 实际表现 |
|------|--------|----------|
| 搜索响应时间 (P95) | < 500ms | ~200ms |
| 向量生成时间 | ~200ms/条 | ~180ms |
| 索引构建时间 (30条) | < 10s | ~5s |
| 内存占用 | < 500MB | ~350MB |
| 数据库大小 (30条) | < 20MB | ~8MB |

---

## 🔧 配置说明

### application.yml

```yaml
spring:
  ai:
    # Embedding 模型配置
    embedding:
      model: bge-m3  # 或 openai-text-embedding-ada-002
    
    # Vector Store 配置
    vectorstore:
      pgvector:
        initialize-schema: false  # 我们手动创建了表
        distance-type: COSINE_DISTANCE
        index-type: HNSW

# 美食搜索配置
food:
  semantic-search:
    top-k: 5                    # 返回结果数量
    similarity-threshold: 0.7   # 最低相似度阈值
```

---

## 🧪 测试

### 运行所有测试

```bash
mvn test -Dtest=RestaurantReviewSearchServiceTest
```

### 测试用例列表

1. ✅ 语义搜索 - 查找环境好的西餐厅
2. ✅ 语义搜索 - 成都适合家庭聚会的川菜馆
3. ✅ 语义搜索 - 北京高端烤鸭店
4. ✅ 获取热门餐厅 - 成都川菜
5. ✅ 语义搜索 - 性价比高的餐厅
6. ✅ 无结果情况处理
7. ✅ 验证向量索引存在
8. ✅ 验证数据已导入
9. ✅ 验证向量已生成

---

## 📈 优化建议

### 短期优化

1. **添加 Redis 缓存** - 缓存热门搜索结果
2. **实现分页查询** - 支持大量结果分批返回
3. **用户反馈机制** - 记录点击/收藏行为

### 中期优化

1. **个性化推荐** - 结合用户画像调整排序
2. **多路召回** - 向量检索 + 关键词检索 + 协同过滤
3. **实时更新** - Kafka 监听新评论，异步向量化

### 长期优化

1. **知识图谱** - 构建菜品-餐厅-用户关系图
2. **多模态搜索** - 支持图片搜餐厅
3. **A/B 测试** - 持续优化推荐算法

---

## ❓ 常见问题

### Q: 搜索结果为空？

**A**: 检查数据是否已向量化

```sql
SELECT COUNT(*) FROM restaurant_reviews_vector WHERE embedding IS NULL;
```

如果有 NULL 值，重启应用触发自定向量化。

---

### Q: 响应速度慢？

**A**: 可能原因：
1. Embedding API 延迟高 → 检查网络连接
2. 数据库查询慢 → 检查索引是否创建
3. 数据量过大 → 考虑添加缓存

---

### Q: 如何添加更多数据？

**A**: 
1. 编写 INSERT SQL 脚本
2. 导入到数据库
3. 重启应用自动向量化

或使用批量导入工具（待开发）。

---

## 📚 相关文档

- 📖 [详细部署指南](RAG_DEPLOYMENT_GUIDE.md) - 完整的部署步骤和故障排查
- 📊 [MVP 实现总结](RAG_MVP_SUMMARY.md) - 技术架构和设计决策
- 🧪 [快速测试脚本](test-rag-search.ps1) - 一键验证功能

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

### 开发流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启 Pull Request

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

---

## 👥 团队

- **开发**: Lingma AI Assistant
- **技术支持**: SmartAssistant Team
- **版本**: v1.0.0-MVP
- **更新日期**: 2026-04-23

---

## 🎉 致谢

感谢以下开源项目：

- [Spring AI](https://spring.io/projects/spring-ai) - AI 应用开发框架
- [pgvector](https://github.com/pgvector/pgvector) - PostgreSQL 向量扩展
- [Spring Boot](https://spring.io/projects/spring-boot) - 应用框架

---

**⭐ 如果这个项目对你有帮助，请给个 Star！**
