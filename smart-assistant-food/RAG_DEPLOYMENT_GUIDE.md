# 美食服务 RAG 功能 MVP 部署指南

## 📋 概述

本指南帮助你快速部署和测试美食服务的智能餐厅推荐功能（基于 RAG + pgvector）。

### 已实现功能

✅ **pgvector 表结构** - 支持向量存储和相似度搜索  
✅ **示例数据导入** - 30+ 条真实餐厅评论  
✅ **语义搜索服务** - 理解自然语言查询意图  
✅ **智能推荐工具** - 暴露为 Agent Tool  
✅ **自动向量化** - 应用启动时自动生成 embedding  
✅ **单元测试** - 完整的功能测试覆盖  

---

## 🚀 快速开始

### 步骤 1: 准备数据库

#### 1.1 确保 PostgreSQL 已安装 pgvector 扩展

```bash
# 连接到 PostgreSQL
psql -U postgres -d smart_assistant

# 检查 pgvector 是否已安装
SELECT extname FROM pg_extension WHERE extname = 'vector';

# 如果未安装，执行：
CREATE EXTENSION IF NOT EXISTS vector;
```

#### 1.2 执行建表脚本

```bash
# 方式1: 使用 psql 命令行
psql -U postgres -d smart_assistant -f sql/v20260423__create_pgvector_tables.sql

# 方式2: 在 psql 中执行
\i sql/v20260423__create_pgvector_tables.sql
```

#### 1.3 导入示例数据

```bash
psql -U postgres -d smart_assistant -f sql/v20260423__insert_sample_restaurant_reviews.sql
```

#### 1.4 验证数据导入

```sql
-- 检查数据量
SELECT COUNT(*) FROM restaurant_reviews_vector;

-- 查看示例数据
SELECT restaurant_name, city, cuisine_type, rating 
FROM restaurant_reviews_vector 
LIMIT 5;
```

---

### 步骤 2: 配置应用

#### 2.1 确认 application.yml 配置

确保以下配置存在：

```yaml
spring:
  ai:
    # Embedding 模型配置（用于生成向量）
    embedding:
      model: bge-m3  # 或其他支持的模型
    
    # Vector Store 配置
    vectorstore:
      pgvector:
        initialize-schema: false  # 我们手动创建了表
        distance-type: COSINE_DISTANCE
        index-type: HNSW
```

#### 2.2 添加新配置项（可选）

```yaml
food:
  semantic-search:
    top-k: 5                    # 返回结果数量
    similarity-threshold: 0.7   # 最低相似度阈值
```

---

### 步骤 3: 启动应用

```bash
# 进入美食服务目录
cd smart-assistant-food

# 编译项目
mvn clean package -DskipTests

# 启动应用
mvn spring-boot:run
```

**应用启动时会自动：**
1. 检测未向量化的评论
2. 批量生成 embedding
3. 更新到数据库

你会看到类似日志：
```
[ReviewEmbeddingInitializer] 开始检查需要向量化的评论数据...
[ReviewEmbeddingInitializer] 处理第 1-50 条...
[ReviewEmbeddingInitializer] ✅ 完成！共处理 30 条评论
```

---

### 步骤 4: 运行测试

```bash
# 运行所有测试
mvn test -Dtest=RestaurantReviewSearchServiceTest

# 运行单个测试
mvn test -Dtest=RestaurantReviewSearchServiceTest#testSearchRestaurants_ByEnvironment
```

---

## 🧪 功能测试

### 测试 1: 通过 API 测试语义搜索

启动应用后，可以通过以下方式测试：

#### 方式 A: 使用 curl

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

#### 方式 B: 通过 Agent Tool

在 Consumer 服务中调用：

```java
// Agent 会自动识别并调用 SmartRestaurantRecommendationTool
String result = agent.call("我想找一家北京环境好的西餐厅，人均300以内");
```

---

### 测试 2: 常见查询场景

| 查询场景 | 示例查询 | 预期结果 |
|---------|---------|---------|
| 环境导向 | "环境优雅的餐厅" | 返回装修好、氛围佳的餐厅 |
| 价格敏感 | "性价比高的川菜馆" | 返回人均低、评分高的餐厅 |
| 场景匹配 | "适合约会的餐厅" | 返回浪漫、安静的餐厅 |
| 家庭友好 | "有儿童座椅的餐厅" | 返回适合家庭的餐厅 |
| 地域特色 | "成都正宗火锅店" | 返回成都本地人推荐的火锅 |

---

## 📊 监控和调试

### 查看向量索引状态

```sql
-- 检查索引大小
SELECT 
    indexname,
    pg_size_pretty(pg_relation_size(indexname::regclass)) as index_size
FROM pg_indexes 
WHERE tablename = 'restaurant_reviews_vector'
  AND indexname LIKE '%hnsw%';

-- 检查向量化进度
SELECT 
    COUNT(*) as total,
    COUNT(embedding) as vectorized,
    ROUND(COUNT(embedding)::numeric / COUNT(*)::numeric * 100, 2) as percentage
FROM restaurant_reviews_vector;
```

### 性能调优

#### 调整 HNSW 索引参数

```sql
-- 重建索引（更精确但更慢）
DROP INDEX IF EXISTS idx_reviews_embedding_hnsw;
CREATE INDEX idx_reviews_embedding_hnsw 
ON restaurant_reviews_vector 
USING hnsw (embedding vector_cosine_ops)
WITH (m = 32, ef_construction = 128);
```

#### 调整搜索参数

```yaml
food:
  semantic-search:
    top-k: 10                    # 增加召回数量
    similarity-threshold: 0.6    # 降低阈值，召回更多结果
```

---

## 🔧 常见问题

### Q1: 向量生成失败

**错误信息：** `向量生成失败`

**解决方案：**
1. 检查 Embedding 模型配置是否正确
2. 确认 AI API Key 有效
3. 查看日志中的详细错误信息

```yaml
spring:
  ai:
    openai:
      api-key: your-api-key-here
```

---

### Q2: 搜索结果为空

**可能原因：**
1. 数据未向量化
2. 相似度过高
3. 过滤条件太严格

**解决方案：**

```sql
-- 检查是否有向量
SELECT COUNT(*) FROM restaurant_reviews_vector WHERE embedding IS NULL;

-- 如果有 NULL，重启应用触发自定向量化
```

```yaml
# 降低相似度阈值
food:
  semantic-search:
    similarity-threshold: 0.5
```

---

### Q3: pgvector 扩展未找到

**错误信息：** `extension "vector" does not exist`

**解决方案：**

```bash
# Ubuntu/Debian
sudo apt-get install postgresql-15-pgvector

# CentOS/RHEL
sudo yum install pgvector_15

# macOS (Homebrew)
brew install pgvector

# Docker
docker run -d \
  --name postgres-vector \
  -e POSTGRES_PASSWORD=postgres \
  pgvector/pgvector:pg16
```

---

### Q4: 内存不足

**症状：** OOM 错误或应用崩溃

**解决方案：**

```bash
# 增加 JVM 堆内存
export JAVA_OPTS="-Xmx2g -Xms1g"
mvn spring-boot:run
```

```yaml
# 减少批处理大小
review-embedding:
  batch-size: 20  # 默认50，改为20
```

---

## 📈 下一步优化建议

### 短期（1-2周）

1. **增加数据量**
   - 爬取更多餐厅评论（大众点评、美团）
   - 目标：每个城市至少 100+ 条评论

2. **优化搜索体验**
   - 添加搜索结果缓存（Redis）
   - 实现分页查询
   - 支持多条件组合筛选

3. **用户反馈机制**
   - 记录用户对推荐结果的点击/收藏
   - 基于反馈优化排序算法

---

### 中期（1个月）

1. **个性化推荐**
   - 集成用户画像服务
   - 基于历史行为调整推荐权重
   - 实现协同过滤

2. **实时更新**
   - 监听新评论，自动向量化
   - 增量更新索引

3. **A/B 测试**
   - 对比不同相似度阈值的效果
   - 测试多种排序策略

---

### 长期（3个月）

1. **多模态搜索**
   - 支持图片搜索（上传菜品照片找餐厅）
   - 语音搜索优化

2. **知识图谱**
   - 构建菜品-餐厅-用户关系图
   - 实现路径推理推荐

3. **商业化**
   - 餐厅广告投放
   - 会员增值服务
   - 数据分析报告

---

## 📞 技术支持

如遇到问题，请检查：

1. **日志文件**: `logs/food-service.log`
2. **数据库连接**: 确认 PostgreSQL 正常运行
3. **AI API**: 确认 Embedding 服务可用
4. **单元测试**: 运行测试用例定位问题

---

## ✅ 验收标准

部署完成后，请确认以下功能正常：

- [ ] 数据库表创建成功
- [ ] 示例数据导入成功（30+ 条）
- [ ] 应用启动时自动向量化
- [ ] 语义搜索返回相关结果
- [ ] 过滤条件生效（城市、菜系、价格、评分）
- [ ] 单元测试全部通过
- [ ] API 响应时间 < 500ms（P95）

---

**祝部署顺利！🎉**
