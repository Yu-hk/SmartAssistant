/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import com.example.smartassistant.router.model.SubTask;
import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
import com.example.smartassistant.router.service.experience.ExperienceEmbeddingMapper.EmbeddingSearchResult;
import com.example.smartassistant.router.service.experience.ExperienceModel.CommonExperience;
import com.example.smartassistant.router.service.experience.ExperienceModel.ReactExperience;
import com.example.smartassistant.router.service.experience.ExperienceModel.ReactExperience.ReactStep;
import com.example.smartassistant.router.service.experience.ExperienceModel.ToolExperience;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 经验服务 —— 管理 COMMON / REACT / TOOL 三类经验的提取、存储、匹配和淘汰。
 * <p>
 * 借鉴 AssistantAgent 的"统一经验体系"设计思想，适配 SmartAssistant 的微服务架构：
 * <ul>
 *   <li>COMMON：路由决策经验 — intentTag → agentName 映射</li>
 *   <li>REACT：推理步骤经验 — 多步 Agent 调用链路</li>
 *   <li>TOOL：工具调用经验 — 特定工具+参数模式</li>
 * </ul>
 * <p>
 * 经验存储在 Redis 中，前缀为 "a2a:experience:"，TTL 30 天。
 * 经验匹配优先于语义缓存，执行于 RouterService.route() 的 Step 0。
 * <p>
 * ⭐ v2: BGE 向量相似度匹配 — 结合 Jaccard 关键词相似度（0.3）+ BGE cosine（0.7），
 * 解决"相同意图不同表述"的匹配问题。支持多经验返回，处理多意图场景。
 *
 * @author SmartAssistant Team
 */
@Service
public class ExperienceService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceService.class);

    private static final String EXP_PREFIX = "a2a:experience:";
    private static final String EXP_INDEX_KEY = EXP_PREFIX + "index:";
    private static final String EXP_KEYWORD_INDEX = EXP_PREFIX + "keyword:";
    private static final String EXP_INTENT_INDEX = EXP_PREFIX + "intent:";
    /** ⭐ 移除 EXP_EMBEDDING_KEY — embedding 改用 pgvector 存储 */

    /** 经验 TTL：30 天 */
    private static final long EXP_TTL_SECONDS = 2592000;
    /** 经验匹配的最低置信度阈值 */
    private static final double MIN_CONFIDENCE = 0.3;
    /** TOOL 经验的置信度加成 */
    private static final double TOOL_CONFIDENCE_BOOST = 0.15;
    /** Jaccard 相似度权重 */
    private static final double JACCARD_WEIGHT = 0.3;
    /** BGE cosine 相似度权重 */
    private static final double COSINE_WEIGHT = 0.7;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SemanticRouteCacheService semanticCache;
    private final BgeOnnxEmbeddingService bgeEmbedding;
    private final ExperienceEmbeddingMapper embeddingMapper;

    public ExperienceService(StringRedisTemplate redisTemplate,
                             SemanticRouteCacheService semanticCache,
                             BgeOnnxEmbeddingService bgeEmbedding,
                             ExperienceEmbeddingMapper embeddingMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.semanticCache = semanticCache;
        this.bgeEmbedding = bgeEmbedding;
        this.embeddingMapper = embeddingMapper;
    }

    // ==================== 经验匹配（路由主流程调用）====================

    /**
     * 匹配最合适的经验（BGE 向量相似度 + Jaccard 融合）。
     * <p>
     * 匹配流程：
     * <ol>
     *   <li>关键词索引快速预筛选（Redis Set lookup）</li>
     *   <li>BGE cosine 相似度计算（权重 0.7）</li>
     *   <li>Jaccard 关键词相似度（权重 0.3）</li>
     *   <li>融合分数 = cosine×0.7 + jaccard×0.3 + typeBonus + hitBonus</li>
     *   <li>返回最佳匹配 + 多意图副匹配列表</li>
     * </ol>
     *
     * @param question 用户原始问题
     * @return 匹配结果，包含最佳经验 + 多意图副匹配；null 表示无匹配
     */
    public ExperienceMatchResult match(String question) {
        if (question == null || question.isBlank()) return null;

        try {
            // 1. 提取问题关键词（用于快速索引预筛选）
            List<String> keywords = extractKeywords(question);
            if (keywords.isEmpty()) return null;

            // 2. BGE 向量化查询 + pgvector 搜索
            float[] queryVec = embed(question);
            boolean useBge = queryVec != null;
            List<EmbeddingSearchResult> bgeResults = null;
            if (useBge) {
                String vectorStr = floatsToPgVector(queryVec);
                if (vectorStr != null) {
                    bgeResults = embeddingMapper.findSimilar(vectorStr, 0.7, 20);
                    log.debug("[Experience] pgvector 搜索: {} 条候选", bgeResults != null ? bgeResults.size() : 0);
                }
            }

            // 3. 关键词索引快速预筛选
            Set<String> matchedExpIds = findMatchingExperienceIds(keywords);

            // 4. 合并 pgvector 结果 + 关键词结果（取并集）
            Map<String, Double> bgeScoreMap = new HashMap<>();
            if (bgeResults != null) {
                for (EmbeddingSearchResult sr : bgeResults) {
                    matchedExpIds.add(sr.getExpId());
                    bgeScoreMap.put(sr.getExpId(), sr.getSimilarity());
                }
            }

            if (matchedExpIds.isEmpty()) {
                if (!useBge) return null;
                // 关键词未命中且 BGE 也无结果
                matchedExpIds = listExperienceIds();
                if (matchedExpIds.isEmpty()) return null;
                log.info("[Experience] 关键词和 BGE 均未命中，降级全量匹配 ({} 条)", matchedExpIds.size());
            }

            // 5. 对候选经验计算综合分数（优先用 pgvector 的 BGE 分数）
            List<ScoredExperience> allMatches = new ArrayList<>();
            for (String expId : matchedExpIds) {
                ExperienceModel exp = loadExperience(expId);
                if (exp == null) continue;

                Double bgeScore = bgeScoreMap.get(expId);
                double score = calculateBlendedScore(exp, keywords, bgeScore, useBge);
                if (score >= MIN_CONFIDENCE) {
                    allMatches.add(new ScoredExperience(exp, score));
                }
            }

            if (allMatches.isEmpty()) return null;

            // 5. 按类型优先级 + 分数排序
            allMatches.sort((a, b) -> {
                int typeCmp = typePriority(a.exp.getType()) - typePriority(b.exp.getType());
                if (typeCmp != 0) return typeCmp;
                return Double.compare(b.score, a.score);
            });

            ScoredExperience best = allMatches.get(0);

            log.info("[Experience] 🧠 经验匹配成功: type={}, intent={}, agent={}, score={}, bge={}",
                    best.exp.getType(), best.exp.getIntentTag(), best.exp.getAgentName(),
                    String.format("%.2f", best.score), useBge ? "✅" : "❌");

            // 6. 更新命中计数
            incrementHitCount(best.exp);

            // 7. 构造匹配结果（含多意图副匹配）
            ExperienceMatchResult result = buildMatchResult(best.exp, best.score, question);

            // 8. ⭐ 多意图：提取分数 ≥ 0.5 的其他经验作为副匹配
            List<ExperienceMatchResult.SecondaryIntent> secondaries = new ArrayList<>();
            for (int i = 1; i < allMatches.size() && secondaries.size() < 3; i++) {
                ScoredExperience se = allMatches.get(i);
                if (se.score >= 0.5 && !se.exp.getAgentName().equals(best.exp.getAgentName())) {
                    secondaries.add(new ExperienceMatchResult.SecondaryIntent(
                            se.exp.getAgentName(), se.exp.getIntentTag(), se.score));
                }
            }
            if (!secondaries.isEmpty()) {
                result.secondaryIntents = secondaries;
                log.info("[Experience] 🔀 多意图检测: {} 个副意图", secondaries.size());
            }

            return result;

        } catch (Exception e) {
            log.warn("[Experience] 经验匹配异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建匹配结果（根据经验类型生成不同的重写指令）。
     */
    private ExperienceMatchResult buildMatchResult(ExperienceModel exp, double score, String question) {
        ExperienceMatchResult result = new ExperienceMatchResult();
        result.experience = exp;
        result.matchScore = score;
        result.agentName = exp.getAgentName();

        if (exp instanceof ToolExperience toolExp) {
            result.reroutedQuestion = String.format(
                    "请直接调用工具 %s，参数：%s。%s",
                    toolExp.getToolName(),
                    toolExp.getRecommendedParams() != null ? toolExp.getRecommendedParams() : "根据问题提取参数",
                    question);
            result.isToolExperience = true;
            result.toolName = toolExp.getToolName();
            result.toolParams = toolExp.getRecommendedParams();
        } else if (exp instanceof CommonExperience) {
            result.reroutedQuestion = question;
            result.skipTaskPlanning = true;
        } else if (exp instanceof ReactExperience reactExp) {
            result.reactSteps = reactExp.getSteps();
            result.reroutedQuestion = question;
            result.skipTaskPlanning = true;
        }

        return result;
    }

    // ==================== 经验提取（Agent 执行后调用）====================

    /** COMMON 经验去重：关键词 Jaccard 相似度阈值 */
    private static final double DEDUP_JACCARD_THRESHOLD = 0.35;

    /**
     * 从一次成功的 Agent 调用中提取 COMMON 经验。
     * <p>
     * ⭐ 去重策略：先按 agentName + 关键词 Jaccard 相似度查找已存在的同类经验，
     * 命中则合并（递增 hitCount + 合并关键词 + 提升置信度），
     * 未命中才新建。避免"查ORD-001"和"查ORD-002"生成两条独立经验。
     */
    public void extractCommonExperience(String question, String agentName, String intentTag) {
        if (question == null || agentName == null || intentTag == null) return;

        try {
            List<String> keywords = extractKeywords(question);
            if (keywords.isEmpty()) return;

            // ⭐ 先按 agent + 关键词相似度查找已存在的同类经验
            ExperienceModel similar = findSimilarExperience(agentName, keywords, ExperienceModel.Type.COMMON);
            if (similar instanceof CommonExperience commonExp) {
                // 合并到已有经验
                commonExp.setHitCount(commonExp.getHitCount() + 1);
                commonExp.setLastHitAt(System.currentTimeMillis());
                commonExp.setConfidence(Math.min(1.0, commonExp.getConfidence() + 0.05));

                Set<String> merged = new HashSet<>(commonExp.getTriggerKeywords());
                merged.addAll(keywords);
                commonExp.setTriggerKeywords(new ArrayList<>(merged));

                // 合并意图标签（保留更丰富的标签）
                if (!commonExp.getIntentTag().contains(intentTag)) {
                    commonExp.setIntentTag(commonExp.getIntentTag() + "," + intentTag);
                }

                saveExperience(commonExp);
                upsertEmbedding(commonExp.getId(), agentName, commonExp.getIntentTag());
                log.debug("[Experience] COMMON 经验已合并: id={}, agent={}, hits={}, newKeywords={}",
                        commonExp.getId(), agentName, commonExp.getHitCount(), keywords);
                return;
            }

            // 新建
            String expId = "common_" + md5(intentTag);
            CommonExperience experience = new CommonExperience(
                    expId, intentTag, keywords, agentName,
                    findFallbackAgent(agentName), 0.7
            );
            experience.setHitCount(1);
            experience.setLastHitAt(System.currentTimeMillis());
            upsertEmbedding(expId, agentName, intentTag);
            saveExperience(experience);
            log.info("[Experience] 🆕 COMMON 经验已创建: intent={}, agent={}", intentTag, agentName);

        } catch (Exception e) {
            log.warn("[Experience] 提取 COMMON 经验失败: {}", e.getMessage());
        }
    }

    /**
     * 从多步协作的结果中提取 REACT 经验。
     * <p>
     * 当 TaskPlanner 分解为多个子任务且全部成功时调用。
     */
    public void extractReactExperience(String question, List<SubTask> subTasks) {
        if (question == null || subTasks == null || subTasks.size() < 2) return;

        try {
            List<String> keywords = extractKeywords(question);
            if (keywords.isEmpty()) return;

            String intentTag = generateIntentTag(question);
            if (intentTag == null) return;

            String expId = "react_" + md5(intentTag);

            // 构建推理步骤
            List<ReactStep> steps = new ArrayList<>();
            for (int i = 0; i < subTasks.size(); i++) {
                SubTask task = subTasks.get(i);
                steps.add(new ReactStep(i + 1, task.getDescription(), task.getTargetAgent()));
            }

            // 主 Agent 取第一个子任务的 Agent
            String primaryAgent = subTasks.get(0).getTargetAgent();

            ExperienceModel existing = loadExperience(expId);
            if (existing instanceof ReactExperience) {
                log.debug("[Experience] REACT 经验已存在: intent={}", intentTag);
                return;
            }

            ReactExperience experience = new ReactExperience(
                    expId, intentTag, keywords, primaryAgent, steps
            );
            experience.setHitCount(1);
            experience.setLastHitAt(System.currentTimeMillis());
            experience.setConfidence(0.6);

            saveExperience(experience);
            log.info("[Experience] 🆕 REACT 经验已创建: intent={}, steps={}, primaryAgent={}",
                    intentTag, steps.size(), primaryAgent);
        } catch (Exception e) {
            log.warn("[Experience] 提取 REACT 经验失败: {}", e.getMessage());
        }
    }

    /**
     * 从 Agent 的工具调用结果中提取 TOOL 经验。
     * <p>
     * 当 Agent 成功调用了一个或多个 @Tool 方法时调用。
     * 适用于 OrderAgent 的 queryOrder / refundOrder 等高频工具。
     */
    public void extractToolExperience(String question, String agentName, String intentTag,
                                      String toolName, String toolParams, String resultTemplate) {
        if (question == null || agentName == null || intentTag == null || toolName == null) return;

        try {
            List<String> keywords = extractKeywords(question);
            if (keywords.isEmpty()) return;

            // ⭐ 先按 toolName + 关键词相似度查找已存在的同类 TOOL 经验
            ExperienceModel similar = findSimilarExperience(agentName, keywords, ExperienceModel.Type.TOOL);
            if (similar instanceof ToolExperience toolExp && toolName.equals(toolExp.getToolName())) {
                // 合并到已有 TOOL 经验
                toolExp.setHitCount(toolExp.getHitCount() + 1);
                toolExp.setSuccessCount(toolExp.getSuccessCount() + 1);
                toolExp.setLastHitAt(System.currentTimeMillis());
                toolExp.setConfidence(Math.min(1.0, toolExp.getConfidence() + 0.05));

                Set<String> merged = new HashSet<>(toolExp.getTriggerKeywords());
                merged.addAll(keywords);
                toolExp.setTriggerKeywords(new ArrayList<>(merged));

                if (!toolExp.getIntentTag().contains(intentTag)) {
                    toolExp.setIntentTag(toolExp.getIntentTag() + "," + intentTag);
                }

                saveExperience(toolExp);
                upsertEmbedding(toolExp.getId(), agentName, toolExp.getIntentTag());
                log.debug("[Experience] TOOL 经验已合并: tool={}, agent={}, hits={}",
                        toolName, agentName, toolExp.getHitCount());
                return;
            }

            // 新建
            String expId = "tool_" + agentName + "_" + md5(intentTag);
            ToolExperience experience = new ToolExperience(
                    expId, intentTag, keywords, agentName,
                    toolName, toolParams, resultTemplate
            );
            experience.setHitCount(1);
            experience.setSuccessCount(1);
            experience.setLastHitAt(System.currentTimeMillis());
            experience.setConfidence(0.8);
            upsertEmbedding(expId, agentName, intentTag);
            saveExperience(experience);
            log.info("[Experience] 🆕 TOOL 经验已创建: tool={}, agent={}, intent={}",
                    toolName, agentName, intentTag);

        } catch (Exception e) {
            log.warn("[Experience] 提取 TOOL 经验失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 查找与给定 keywords 最相似的已有经验（用于去重）。
     * <p>
     * 遍历所有同 agentName 的经验，计算 Jaccard 相似度，
     * 返回相似度 ≥ {@link #DEDUP_JACCARD_THRESHOLD} 的最佳匹配。
     * 这确保"查ORD-001"和"查ORD-002"合并为同一条经验，而不是各自独立。
     *
     * @param agentName 目标 Agent 名称（必须匹配）
     * @param keywords  新问题的关键词
     * @param type      经验类型（COMMON 或 TOOL）
     * @return 最相似的已有经验，无匹配返回 null
     */
    private ExperienceModel findSimilarExperience(String agentName, List<String> keywords,
                                                   ExperienceModel.Type type) {
        if (keywords == null || keywords.isEmpty()) return null;
        Set<String> queryKwSet = new HashSet<>(keywords);

        // 通过关键词索引找候选
        Set<String> candidateIds = findMatchingExperienceIds(keywords);
        
        // ⭐ 兜底：关键词索引无结果时遍历全量（新 Agent 的前几次请求场景）
        if (candidateIds.isEmpty()) {
            candidateIds = listExperienceIds();
        }

        if (candidateIds.isEmpty()) return null;

        ExperienceModel best = null;
        double bestScore = 0;

        for (String id : candidateIds) {
            ExperienceModel exp = loadExperience(id);
            if (exp == null || !agentName.equals(exp.getAgentName())) continue;
            if (exp.getType() != type) continue;
            if (exp.getTriggerKeywords() == null || exp.getTriggerKeywords().isEmpty()) continue;

            // Jaccard 相似度
            Set<String> expKwSet = new HashSet<>(exp.getTriggerKeywords());
            Set<String> intersection = new HashSet<>(expKwSet);
            intersection.retainAll(queryKwSet);
            Set<String> union = new HashSet<>(expKwSet);
            union.addAll(queryKwSet);
            double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

            if (jaccard > bestScore) {
                bestScore = jaccard;
                best = exp;
            }
        }

        if (best != null && bestScore >= DEDUP_JACCARD_THRESHOLD) {
            log.debug("[Experience] 找到相似经验: id={}, agent={}, score={:.2f}",
                    best.getId(), agentName, bestScore);
            return best;
        }

        return null;
    }

    // ==================== 经验管理 API ====================

    /**
     * 保存经验到 Redis
     */
    public void saveExperience(ExperienceModel experience) {
        if (experience == null || experience.getId() == null) return;
        try {
            String key = EXP_PREFIX + experience.getId();
            String json = objectMapper.writeValueAsString(experience);
            redisTemplate.opsForValue().set(key, json, EXP_TTL_SECONDS, TimeUnit.SECONDS);

            // 更新关键词索引
            if (experience.getTriggerKeywords() != null) {
                for (String kw : experience.getTriggerKeywords()) {
                    redisTemplate.opsForSet().add(EXP_KEYWORD_INDEX + kw, experience.getId());
                    redisTemplate.expire(EXP_KEYWORD_INDEX + kw, EXP_TTL_SECONDS, TimeUnit.SECONDS);
                }
            }

            // 更新意图索引
            if (experience.getIntentTag() != null) {
                redisTemplate.opsForSet().add(EXP_INTENT_INDEX + experience.getIntentTag(), experience.getId());
                redisTemplate.expire(EXP_INTENT_INDEX + experience.getIntentTag(), EXP_TTL_SECONDS, TimeUnit.SECONDS);
            }

            // 更新总索引
            redisTemplate.opsForSet().add(EXP_INDEX_KEY, experience.getId());
            redisTemplate.expire(EXP_INDEX_KEY, EXP_TTL_SECONDS, TimeUnit.SECONDS);

        } catch (JsonProcessingException e) {
            log.warn("[Experience] 序列化经验失败: id={}, error={}", experience.getId(), e.getMessage());
        }
    }

    /**
     * 从 Redis 加载经验
     */
    public ExperienceModel loadExperience(String expId) {
        if (expId == null) return null;
        try {
            String key = EXP_PREFIX + expId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, ExperienceModel.class);
        } catch (Exception e) {
            log.warn("[Experience] 加载经验失败: id={}, error={}", expId, e.getMessage());
            return null;
        }
    }

    /**
     * 删除经验
     */
    public void deleteExperience(String expId) {
        if (expId == null) return;
        try {
            ExperienceModel exp = loadExperience(expId);
            if (exp != null) {
                // 清理关键词索引
                if (exp.getTriggerKeywords() != null) {
                    for (String kw : exp.getTriggerKeywords()) {
                        redisTemplate.opsForSet().remove(EXP_KEYWORD_INDEX + kw, expId);
                    }
                }
                // 清理意图索引
                if (exp.getIntentTag() != null) {
                    redisTemplate.opsForSet().remove(EXP_INTENT_INDEX + exp.getIntentTag(), expId);
                }
            }
            redisTemplate.delete(EXP_PREFIX + expId);
            redisTemplate.opsForSet().remove(EXP_INDEX_KEY, expId);
            // ⭐ 清理 pgvector 嵌入向量
            embeddingMapper.delete(expId);
            log.info("[Experience] 🗑️ 经验已删除: id={}", expId);
        } catch (Exception e) {
            log.warn("[Experience] 删除经验失败: id={}, error={}", expId, e.getMessage());
        }
    }

    /**
     * ⭐ 将经验意图的 BGE embedding 存入 pgvector。
     * 仅在 BGE 可用时执行；已有数据执行 upsert 更新。
     */
    private void upsertEmbedding(String expId, String agentName, String intentTag) {
        if (bgeEmbedding == null || !bgeEmbedding.isAvailable()) return;
        try {
            float[] vec = bgeEmbedding.embed(intentTag);
            if (vec == null) return;
            String vecStr = floatsToPgVector(vec);
            embeddingMapper.upsert(expId, agentName, intentTag, vecStr);
            log.debug("[Experience] pgvector upsert: id={}, dim={}", expId, vec.length);
        } catch (Exception e) {
            log.warn("[Experience] pgvector upsert 失败: id={}, err={}", expId, e.getMessage());
        }
    }

    /**
     * 列出所有经验 ID
     */
    public Set<String> listExperienceIds() {
        try {
            return redisTemplate.opsForSet().members(EXP_INDEX_KEY);
        } catch (Exception e) {
            log.warn("[Experience] 列经验索引失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 按类型列出经验
     */
    public List<ExperienceModel> listByType(ExperienceModel.Type type) {
        Set<String> ids = listExperienceIds();
        if (ids.isEmpty()) return Collections.emptyList();

        List<ExperienceModel> result = new ArrayList<>();
        for (String id : ids) {
            ExperienceModel exp = loadExperience(id);
            if (exp != null && exp.getType() == type) {
                result.add(exp);
            }
        }
        return result;
    }

    /**
     * 获取经验统计
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        Set<String> ids = listExperienceIds();

        stats.put("totalCount", ids.size());

        int commonCount = 0, reactCount = 0, toolCount = 0;
        int totalHits = 0;

        for (String id : ids) {
            ExperienceModel exp = loadExperience(id);
            if (exp != null) {
                switch (exp.getType()) {
                    case COMMON -> commonCount++;
                    case REACT -> reactCount++;
                    case TOOL -> toolCount++;
                }
                totalHits += exp.getHitCount();
            }
        }

        stats.put("commonCount", commonCount);
        stats.put("reactCount", reactCount);
        stats.put("toolCount", toolCount);
        stats.put("totalHits", totalHits);

        return stats;
    }

    // ==================== 内部方法 ====================

    /**
     * 通过关键词索引查找匹配的经验 ID
     */
    private Set<String> findMatchingExperienceIds(List<String> keywords) {
        Set<String> matchedIds = new HashSet<>();

        // 精确关键词匹配
        for (String kw : keywords) {
            Set<String> ids = redisTemplate.opsForSet().members(EXP_KEYWORD_INDEX + kw);
            if (ids != null) matchedIds.addAll(ids);
        }

        // 如果精确匹配太少，尝试意图索引匹配
        if (matchedIds.size() < 3) {
            String intentTag = String.join(",", keywords);
            Set<String> intentIds = redisTemplate.opsForSet().members(EXP_INTENT_INDEX + intentTag);
            if (intentIds != null) matchedIds.addAll(intentIds);
        }

        return matchedIds;
    }

    /**
     * 计算关键词匹配分数（Jaccard 相似度 + 类型加成）
     */
    private double calculateMatchScore(ExperienceModel exp, List<String> queryKeywords) {
        if (exp.getTriggerKeywords() == null || exp.getTriggerKeywords().isEmpty()) return 0;

        Set<String> expKwSet = new HashSet<>(exp.getTriggerKeywords());
        Set<String> queryKwSet = new HashSet<>(queryKeywords);

        // Jaccard 相似度
        Set<String> intersection = new HashSet<>(expKwSet);
        intersection.retainAll(queryKwSet);

        Set<String> union = new HashSet<>(expKwSet);
        union.addAll(queryKwSet);

        double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

        // 基础分数 = Jaccard 相似度
        double score = jaccard;

        // 类型加成
        switch (exp.getType()) {
            case TOOL -> score += TOOL_CONFIDENCE_BOOST;
        }

        // 历史命中加成（最多 +0.1）
        score += Math.min(0.1, exp.getHitCount() * 0.02);

        // 置信度衰减
        score *= exp.getConfidence();

        return Math.min(1.0, score);
    }

    /**
     * 类型优先级（数值越小优先级越高）
     */
    private int typePriority(ExperienceModel.Type type) {
        return switch (type) {
            case TOOL -> 0;
            case COMMON -> 1;
            case REACT -> 2;
        };
    }

    /**
     * 递增经验命中计数
     */
    private void incrementHitCount(ExperienceModel exp) {
        exp.setHitCount(exp.getHitCount() + 1);
        exp.setLastHitAt(System.currentTimeMillis());
        saveExperience(exp);
    }

    /**
     * 查找兜底 Agent
     */
    private String findFallbackAgent(String primaryAgent) {
        return switch (primaryAgent) {
            case "order_agent" -> "general_agent";
            case "product_agent" -> "general_agent";
            case "general_agent" -> "builtin_fallback";
            default -> "general_agent";
        };
    }

    /**
     * 提取问题关键词（复用语义缓存的提取逻辑）
     */
    protected List<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) return Collections.emptyList();

        // 使用语义缓存的关键词提取
        String intentTag = semanticCache.generateIntentTag(question);
        if (intentTag == null || intentTag.isBlank()) return Collections.emptyList();

        return Arrays.stream(intentTag.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 生成意图标签
     */
    private String generateIntentTag(String question) {
        return semanticCache.generateIntentTag(question);
    }

    private String md5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(str.hashCode());
        }
    }

    /**
     * BGE 文本向量化（降级无 BGE 时返回 null）。
     */
    private float[] embed(String text) {
        if (bgeEmbedding == null || !bgeEmbedding.isAvailable()) return null;
        return bgeEmbedding.embed(text);
    }

    /**
     * ⭐ float[] → pgvector 字符串格式 "{@code [0.1, 0.2, ...]}"
     */
    static String floatsToPgVector(float[] vec) {
        if (vec == null || vec.length == 0) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 计算融合分数：Jaccard 关键词相似度(0.3) + pgvector BGE cosine(0.7)
     * + 类型加成 + 历史命中加成。
     *
     * @param exp       经验对象
     * @param queryKeywords 查询关键词
     * @param bgeScore  pgvector 返回的余弦相似度（可为 null）
     * @param useBge    BGE 是否可用
     */
    private double calculateBlendedScore(ExperienceModel exp, List<String> queryKeywords,
                                         Double bgeScore, boolean useBge) {
        if (exp.getTriggerKeywords() == null || exp.getTriggerKeywords().isEmpty()) return 0;

        // Jaccard 关键词相似度
        Set<String> expKwSet = new HashSet<>(exp.getTriggerKeywords());
        Set<String> queryKwSet = new HashSet<>(queryKeywords);
        Set<String> intersection = new HashSet<>(expKwSet);
        intersection.retainAll(queryKwSet);
        Set<String> union = new HashSet<>(expKwSet);
        union.addAll(queryKwSet);
        double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

        // BGE cosine 相似度（来自 pgvector）
        double cosineSim = (useBge && bgeScore != null) ? Math.max(0, bgeScore) : 0;

        // 融合分数
        double score = JACCARD_WEIGHT * jaccard + COSINE_WEIGHT * cosineSim;

        // 类型加成
        if (exp.getType() == ExperienceModel.Type.TOOL) {
            score += TOOL_CONFIDENCE_BOOST;
        }

        // 历史命中加成（最多 +0.1）
        score += Math.min(0.1, exp.getHitCount() * 0.02);

        // 置信度衰减
        score *= exp.getConfidence();

        return Math.min(1.0, score);
    }

    /**
     * 经验匹配结果
     */
    public static class ExperienceMatchResult {
        public ExperienceModel experience;
        public double matchScore;
        public String agentName;
        public String reroutedQuestion;
        public boolean skipTaskPlanning;
        public boolean isToolExperience;
        public String toolName;
        public String toolParams;
        public List<ReactStep> reactSteps;
        /** ⭐ 多意图副匹配列表 */
        public List<SecondaryIntent> secondaryIntents;

        public static class SecondaryIntent {
            public String agentName;
            public String intentTag;
            public double score;
            public SecondaryIntent(String agentName, String intentTag, double score) {
                this.agentName = agentName; this.intentTag = intentTag; this.score = score;
            }
        }
    }

    /**
     * 带分数的经验包装
     */
    private record ScoredExperience(ExperienceModel exp, double score) {}
}
