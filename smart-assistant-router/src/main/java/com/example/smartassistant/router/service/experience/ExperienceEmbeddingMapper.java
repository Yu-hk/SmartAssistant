package com.example.smartassistant.router.service.experience;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 经验向量 Mapper — pgvector HNSW 索引搜索。
 * <p>
 * pgvector 的 {@code <=>} 算子计算余弦距离，
 * {@code 1 - (embedding <=> query_vec)} = 余弦相似度。
 */
@Mapper
public interface ExperienceEmbeddingMapper {

    /**
     * 插入或更新嵌入向量。
     * pgvector 接受 '{@code [0.1, 0.2, ...]}' 格式的向量字符串。
     */
    @Insert("INSERT INTO experience_embeddings (exp_id, agent_name, intent_tag, embedding, created_at, updated_at) "
            + "VALUES (#{expId}, #{agentName}, #{intentTag}, #{embedding}::vector, now(), now()) "
            + "ON CONFLICT (exp_id) DO UPDATE SET "
            + "agent_name = EXCLUDED.agent_name, "
            + "intent_tag = EXCLUDED.intent_tag, "
            + "embedding = EXCLUDED.embedding, "
            + "updated_at = now()")
    void upsert(@Param("expId") String expId,
                @Param("agentName") String agentName,
                @Param("intentTag") String intentTag,
                @Param("embedding") String embedding);

    /**
     * 删除一条嵌入向量。
     */
    @Delete("DELETE FROM experience_embeddings WHERE exp_id = #{expId}")
    void delete(@Param("expId") String expId);

    /**
     * ⭐ 余弦相似度搜索 — 返回最相似的 N 条经验。
     * <p>
     * {@code embedding <=> queryVec} 是 pgvector 的余弦距离算子，
     * {@code 1 - distance} = 余弦相似度（归一化向量下等同于点积）。
     * HNSW 索引自动加速此查询。
     *
     * @param queryVec pgvector 向量字符串格式 "{@code [0.1, 0.2, ...]}"
     * @param minSimilarity 最低相似度阈值
     * @param limit 返回条数上限
     * @return expId + similarity 的列表，按相似度降序
     */
    @Select("SELECT exp_id, agent_name, 1 - (embedding <=> #{queryVec}::vector) AS similarity "
            + "FROM experience_embeddings "
            + "WHERE embedding <=> #{queryVec}::vector < #{maxDistance} "
            + "ORDER BY embedding <=> #{queryVec}::vector "
            + "LIMIT #{limit}")
    @Results({
        @Result(property = "expId", column = "exp_id"),
        @Result(property = "agentName", column = "agent_name"),
        @Result(property = "similarity", column = "similarity")
    })
    List<EmbeddingSearchResult> findSimilar(@Param("queryVec") String queryVec,
                                            @Param("maxDistance") double maxDistance,
                                            @Param("limit") int limit);

    /**
     * 向量搜索结果行映射。
     */
    class EmbeddingSearchResult {
        private String expId;
        private String agentName;
        private double similarity;

        public String getExpId() { return expId; }
        public void setExpId(String expId) { this.expId = expId; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String agentName) { this.agentName = agentName; }
        public double getSimilarity() { return similarity; }
        public void setSimilarity(double similarity) { this.similarity = similarity; }
    }
}
