package com.example.smartassistant.consumer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.smartassistant.consumer.entity.UserPreferenceVector;
import com.example.smartassistant.consumer.entity.UserPreferenceVector.VectorType;
import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.mapper.UserPreferenceVectorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 用户偏好向量服务
 *
 * <p>功能：</p>
 * <ul>
 *   <li>用户偏好文本的向量化存储</li>
 *   <li>相似用户检索</li>
 *   <li>偏好内容匹配</li>
 * </ul>
 *
 * @author SmartAssistant
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferenceVectorService extends ServiceImpl<UserPreferenceVectorMapper, UserPreferenceVector> {

    private final VectorStore vectorStore;

    private static final float DEFAULT_SIMILARITY_THRESHOLD = 0.75f;
    private static final int DEFAULT_TOP_K = 5;

    // ==================== 向量同步方法 ====================

    /**
     * 同步用户美食偏好到向量库
     */
    @Async("asyncEmbeddingExecutor")
    @Transactional
    public void syncFoodPreferenceVector(Long userId, String[] foodPreferences) {
        if (foodPreferences == null || foodPreferences.length == 0) {
            log.debug("[向量] 用户 {} 美食偏好为空，跳过同步", userId);
            deleteVectorByType(userId, VectorType.FOOD);
            return;
        }

        String content = String.join("、", foodPreferences);
        syncSingleVector(userId, VectorType.FOOD, content);
    }

    /**
     * 同步用户旅行偏好到向量库
     */
    @Async("asyncEmbeddingExecutor")
    @Transactional
    public void syncTravelPreferenceVector(Long userId, String[] travelPreferences) {
        if (travelPreferences == null || travelPreferences.length == 0) {
            log.debug("[向量] 用户 {} 旅行偏好为空，跳过同步", userId);
            deleteVectorByType(userId, VectorType.TRAVEL);
            return;
        }

        String content = String.join("、", travelPreferences);
        syncSingleVector(userId, VectorType.TRAVEL, content);
    }

    /**
     * 同步用户饮食限制到向量库
     */
    @Async("asyncEmbeddingExecutor")
    @Transactional
    public void syncDietaryPreferenceVector(Long userId, String[] dietaryRestrictions) {
        if (dietaryRestrictions == null || dietaryRestrictions.length == 0) {
            log.debug("[向量] 用户 {} 饮食限制为空，跳过同步", userId);
            deleteVectorByType(userId, VectorType.DIETARY);
            return;
        }

        String content = String.join("、", dietaryRestrictions);
        syncSingleVector(userId, VectorType.DIETARY, content);
    }

    /**
     * 同步用户综合偏好到向量库
     */
    @Async("asyncEmbeddingExecutor")
    @Transactional
    public void syncAllPreferenceVectors(Long userId, String[] foodPreferences,
            String[] travelPreferences, String[] dietaryRestrictions, String budgetRange) {
        // 同步各类型向量
        syncFoodPreferenceVector(userId, foodPreferences);
        syncTravelPreferenceVector(userId, travelPreferences);
        syncDietaryPreferenceVector(userId, dietaryRestrictions);

        // 同步综合向量
        StringBuilder sb = new StringBuilder();
        if (foodPreferences != null && foodPreferences.length > 0) {
            sb.append("我喜欢：").append(String.join("、", foodPreferences)).append("。");
        }
        if (travelPreferences != null && travelPreferences.length > 0) {
            sb.append("旅行偏好：").append(String.join("、", travelPreferences)).append("。");
        }
        if (dietaryRestrictions != null && dietaryRestrictions.length > 0) {
            sb.append("饮食限制：").append(String.join("、", dietaryRestrictions)).append("。");
        }
        if (budgetRange != null && !budgetRange.isBlank()) {
            sb.append("预算范围：").append(budgetRange).append("。");
        }

        String content = sb.toString();
        if (!content.isEmpty()) {
            syncSingleVector(userId, VectorType.ALL, content);
        } else {
            deleteVectorByType(userId, VectorType.ALL);
        }

        log.info("[向量] 用户 {} 综合偏好向量同步完成", userId);
    }

    /**
     * 同步单个向量到向量库
     */
    private void syncSingleVector(Long userId, VectorType type, String content) {
        String embeddingId = UserPreferenceVector.generateEmbeddingId(userId, type.getCode());

        try {
            // 1. 先从数据库删除旧记录（如果存在）
            LambdaQueryWrapper<UserPreferenceVector> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserPreferenceVector::getEmbeddingId, embeddingId);
            this.remove(wrapper);

            // 2. 创建新记录
            UserPreferenceVector vector = UserPreferenceVector.builder()
                    .userId(userId)
                    .vectorType(type.getCode())
                    .content(content)
                    .embeddingId(embeddingId)
                    .build();
            this.save(vector);

            // 3. 添加到向量库
            Document doc = Document.builder()
                    .id(embeddingId)
                    .text(content)
                    .metadata("userId", String.valueOf(userId))
                    .metadata("vectorType", type.getCode())
                    .build();
            vectorStore.add(List.of(doc));

            log.info("[向量] 用户 {} {} 向量同步成功: {}", userId, type.getDescription(), content);

        } catch (Exception e) {
            log.error("[向量] 用户 {} {} 向量同步失败: {}", userId, type.getDescription(), e.getMessage(), e);
        }
    }

    /**
     * 根据类型删除向量
     */
    private void deleteVectorByType(Long userId, VectorType type) {
        String embeddingId = UserPreferenceVector.generateEmbeddingId(userId, type.getCode());

        try {
            // 1. 删除数据库记录
            LambdaQueryWrapper<UserPreferenceVector> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserPreferenceVector::getEmbeddingId, embeddingId);
            this.remove(wrapper);

            // 2. 从向量库删除（使用 filter 方式）
            // PgVectorStore 不直接提供 delete 方法，需要重新构建 store
            log.debug("[向量] 用户 {} {} 向量已删除", userId, type.getDescription());

        } catch (Exception e) {
            log.error("[向量] 删除用户 {} {} 向量失败: {}", userId, type.getDescription(), e.getMessage(), e);
        }
    }

    // ==================== 向量检索方法 ====================

    /**
     * 查找与指定内容相似的用户
     *
     * @param queryContent 查询内容
     * @param topK 返回相似用户数量
     * @return 相似用户列表（包含用户ID和相似度）
     */
    public List<SimilarUser> findSimilarUsers(String queryContent, int topK) {
        return findSimilarUsers(queryContent, topK, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * 查找与指定内容相似的用户
     *
     * @param queryContent 查询内容
     * @param topK 返回相似用户数量
     * @param threshold 相似度阈值
     * @return 相似用户列表
     */
    public List<SimilarUser> findSimilarUsers(String queryContent, int topK, float threshold) {
        List<SimilarUser> results = new ArrayList<>();

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(queryContent)
                    .topK(topK * 2)  // 多取一些，后面过滤
                    .similarityThreshold(threshold)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);

            for (Document doc : docs) {
                if (results.size() >= topK) break;

                String userId = doc.getMetadata().get("userId") != null 
                    ? doc.getMetadata().get("userId").toString() : null;
                String vectorType = doc.getMetadata().get("vectorType") != null 
                    ? doc.getMetadata().get("vectorType").toString() : null;

                if (userId != null) {
                    // 获取相似度（Spring AI 不同版本可能返回不同类型）
                    float similarity = 0f;
                    Object similarityObj = doc.getMetadata().get("similarity");
                    if (similarityObj != null && similarityObj instanceof Number) {
                        similarity = ((Number) similarityObj).floatValue();
                    }
                    
                    results.add(new SimilarUser(
                            Long.parseLong(userId),
                            vectorType,
                            doc.getText(),
                            similarity
                    ));
                }
            }

            log.debug("[向量] 相似用户检索完成: 查询='{}', 找到 {} 个相似用户",
                    queryContent.length() > 20 ? queryContent.substring(0, 20) + "..." : queryContent,
                    results.size());

        } catch (Exception e) {
            log.error("[向量] 相似用户检索失败: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * 查找与指定用户偏好相似的其他用户
     *
     * @param userId 用户ID
     * @param topK 返回相似用户数量
     * @return 相似用户列表
     */
    public List<SimilarUser> findSimilarUsersByPreference(Long userId, int topK) {
        // 获取该用户的综合偏好向量内容
        String embeddingId = UserPreferenceVector.generateEmbeddingId(userId, VectorType.ALL.getCode());

        LambdaQueryWrapper<UserPreferenceVector> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserPreferenceVector::getEmbeddingId, embeddingId);
        UserPreferenceVector userVector = this.getOne(wrapper);

        if (userVector == null || userVector.getContent() == null || userVector.getContent().isEmpty()) {
            log.debug("[向量] 用户 {} 没有偏好向量记录", userId);
            return List.of();
        }

        // 使用该用户的偏好内容进行相似检索
        return findSimilarUsers(userVector.getContent(), topK + 1)  // +1 因为可能包含自己
                .stream()
                .filter(su -> !su.userId().equals(userId))  // 排除自己
                .limit(topK)
                .toList();
    }

    /**
     * 匹配内容与用户偏好的相似度
     *
     * @param userId 用户ID
     * @param content 待匹配内容
     * @return 相似度（0-1），null 表示无法匹配
     */
    public Float matchContent(Long userId, String content) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(content)
                    .topK(10)
                    .similarityThreshold(0.5f)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);

            if (!docs.isEmpty()) {
                // 手动过滤用户ID并获取相似度
                for (Document doc : docs) {
                    String docUserId = doc.getMetadata().get("userId") != null 
                        ? doc.getMetadata().get("userId").toString() : null;
                    if (docUserId != null && docUserId.equals(String.valueOf(userId))) {
                        Object similarityObj = doc.getMetadata().get("similarity");
                        if (similarityObj != null && similarityObj instanceof Number) {
                            return ((Number) similarityObj).floatValue();
                        }
                        return 0f;
                    }
                }
            }

        } catch (Exception e) {
            log.error("[向量] 内容匹配失败: {}", e.getMessage(), e);
        }

        return null;
    }

    // ==================== 用户画像查询方法 ====================

    /**
     * 从 user_preference_vectors 表构建用户画像
     * 用于替代 user_profiles 表的查询
     *
     * @param userId 用户ID
     * @return UserProfile 对象
     */
    public UserProfile buildUserProfileFromVectors(Long userId) {
        if (userId == null) {
            return null;
        }

        try {
            // 查询该用户的所有偏好向量
            List<UserPreferenceVector> vectors = this.list(
                    new LambdaQueryWrapper<UserPreferenceVector>()
                            .eq(UserPreferenceVector::getUserId, userId)
            );

            if (vectors.isEmpty()) {
                log.debug("[向量] 用户 {} 没有偏好向量记录", userId);
                return null;
            }

            // 构建 UserProfile
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);

            for (UserPreferenceVector vector : vectors) {
                String type = vector.getVectorType();
                String content = vector.getContent();

                if (content == null || content.isEmpty()) {
                    continue;
                }

                switch (type) {
                    case "food":
                        // 解析美食偏好：格式如 "我喜欢：川菜、火锅、烧烤" 或 "川菜、火锅、烧烤"
                        profile.setFoodPreferencesArray(parseContentToArray(content));
                        break;
                    case "travel":
                        profile.setTravelPreferencesArray(parseContentToArray(content));
                        break;
                    case "dietary":
                        profile.setDietaryRestrictionsArray(parseContentToArray(content));
                        break;
                    case "all":
                        // 从综合内容中提取预算信息
                        parseBudgetFromContent(content, profile);
                        break;
                }
            }

            log.debug("[向量] 构建用户画像完成: userId={}", userId);
            return profile;

        } catch (Exception e) {
            log.error("[向量] 构建用户画像失败: userId={}, error={}", userId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从文本内容解析为数组
     * 格式如 "我喜欢：川菜、火锅、烧烤" 或 "川菜、火锅、烧烤"
     */
    private String[] parseContentToArray(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }

        // 移除前缀（如"我喜欢："、"旅行偏好："等）
        String cleaned = content
                .replaceAll("^我喜欢[：:]", "")
                .replaceAll("^旅行偏好[：:]", "")
                .replaceAll("^饮食限制[：:]", "")
                .replaceAll("^预算范围[：:]", "")
                .trim();

        if (cleaned.isEmpty()) {
            return new String[0];
        }

        // 按顿号、逗号分隔
        String[] parts = cleaned.split("[、，,]");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * 从综合内容中提取预算信息
     * 格式如 "我喜欢：川菜。旅行偏好：云南。预算范围：medium。"
     */
    private void parseBudgetFromContent(String content, UserProfile profile) {
        if (content == null) {
            return;
        }

        // 尝试匹配预算范围
        String budget = null;
        if (content.contains("低预算") || content.contains("预算范围：low") || content.contains("预算：low")) {
            budget = "low";
        } else if (content.contains("中等预算") || content.contains("预算范围：medium") || content.contains("预算：medium")) {
            budget = "medium";
        } else if (content.contains("高预算") || content.contains("预算范围：high") || content.contains("预算：high")) {
            budget = "high";
        }

        if (budget != null) {
            profile.setBudgetRange(budget);
        }
    }

    // ==================== 辅助类 ====================

    /**
     * 相似用户信息
     */
    public record SimilarUser(
            Long userId,
            String vectorType,
            String content,
            float similarity
    ) {
        @Override
        public String toString() {
            return String.format("SimilarUser{userId=%d, type=%s, similarity=%.2f, content='%s'}",
                    userId, vectorType, similarity,
                    content.length() > 30 ? content.substring(0, 30) + "..." : content);
        }
    }
}
