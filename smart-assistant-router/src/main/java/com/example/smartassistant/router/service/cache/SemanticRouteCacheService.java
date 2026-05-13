/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 语义路由缓存服务（Phase 2 + Phase 3）
 * <p>
 * Phase 2: LLM 改写高命中缓存回复，避免完全重复
 * Phase 3: 动态 TTL（天气类短、美食/旅行类长）
 * <p>
 * 快速路径：精确问题字符串直接查 Redis（跳过 LLM 标签生成）
 */
@Service
public class SemanticRouteCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticRouteCacheService.class);
    private static final String CACHE_KEY_PREFIX = "a2a:route:semantic:";
    private static final String REPLY_KEY_PREFIX = "a2a:route:reply:";
    private static final String EXACT_KEY_PREFIX = "a2a:route:exact:";

    private static final String KEYWORD_KEY_PREFIX = "a2a:route:keyword:";
    private static final String KEYWORD_REPLY_KEY_PREFIX = "a2a:route:keyword:reply:";  // ⭐ 关键词级别回复缓存
    private static final String DECISION_AUDIT_KEY_PREFIX = "a2a:route:decision:";
    private static final String FULL_DECISION_KEY_PREFIX = "a2a:route:full-decision:";  // ⭐ 供 Consumer 读取的完整决策（独立 key）
    private static final String GLOBAL_INTENT_COUNT_PREFIX = "intent:global:count:";  // ⭐ 全局意图计数（判断高频问题）
    private static final long CACHE_TTL_SECONDS = 86400;
    private static final long DECISION_AUDIT_TTL_SECONDS = 604800; // 7天
    private static final int HIGH_FREQUENCY_THRESHOLD = 2;
    /**
     * Agent 声明 cache-ttl-seconds 低于此阈值时，回复内容不缓存。
     * 仅缓存路由决策（T1/T2/T3 仍可快速路由），但回复始终从 Agent 获取最新数据。
     * Agent 管理员可通过 Nacos 调整 cache-ttl-seconds 控制缓存行为，无需修改代码。
     */
    private static final long MIN_CACHE_TTL_SECONDS = 3600;  // 1 小时

    private final StringRedisTemplate redisTemplate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ChineseTokenizer tokenizer;
    private final ReplyFormatter replyFormatter;
    private final BgeOnnxEmbeddingService bgeEmbedding;
    private final TfEmbeddingService tfEmbedding;
    private final VectorCacheStore vectorCache;

    @Value("${router.semantic-cache.enabled:true}")
    private boolean cacheEnabled;

    public SemanticRouteCacheService(
            ChatClient.Builder chatClientBuilder,
            StringRedisTemplate redisTemplate,
            ChineseTokenizer tokenizer,
            AgentDiscoveryService agentDiscoveryService,
            TfEmbeddingService tfEmbedding,
            VectorCacheStore vectorCache,
            BgeOnnxEmbeddingService bgeEmbedding) {
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.tokenizer = tokenizer;
        this.replyFormatter = new ReplyFormatter(chatClient, agentDiscoveryService);
        this.bgeEmbedding = bgeEmbedding;
        this.tfEmbedding = tfEmbedding;
        this.vectorCache = vectorCache;
    }

    /** BGE > TF > null */
    private float[] embed(String text) {
        if (bgeEmbedding.isAvailable()) return bgeEmbedding.embed(text);
        return tfEmbedding.embed(text);
    }

    /**
     * 获取缓存决策：三层匹配
     * <p>
     * Tier 1: 精确匹配（MD5 快速路径）
     * Tier 2: 关键词哈希匹配（分词 → MD5）
     * Tier 3: TF 向量匹配（余弦相似度 ≥0.70，覆盖前缀扩展场景）
     */
    public CachedRouteDecision getCachedDecision(String question) {
        if (!cacheEnabled || redisTemplate == null || question == null || question.isBlank()) {
            return null;
        }
        try {
            // Tier 1: 精确匹配
            CachedRouteDecision exact = getByExactQuestion(question);
            if (exact != null) return exact;

            // Tier 2: 关键词哈希匹配
            CachedRouteDecision keyword = getByKeywordHash(question);
            if (keyword != null) return keyword;

            // Tier 3: ⭐ TF 向量匹配（余弦相似度 ≥0.70，覆盖前缀扩展场景）
            CachedRouteDecision vector = getByVectorMatch(question);
            if (vector != null) return vector;

        } catch (Exception e) {
            log.warn("[SemanticCache] 读取缓存失败: {}", e.getMessage());
        }
        return null;
    }

    private static final double EXTENSION_SIMILARITY_THRESHOLD = 0.15;

    /**
     * Tier 3: TF 向量匹配
     * <p>
     * 余弦相似度 ≥ 0.70 时视为语义匹配。当匹配到的问题是用户当前问题的前缀扩展时：
     * - 扩展部分仅需缓存数据即可回答（如"多穿点衣服"→天气数据可推导）→ 缓存命中
     * - 扩展部分需要新数据源（如"出行规划"→需要旅行数据）→ 缓存未命中,交 Agent
     */
    private CachedRouteDecision getByVectorMatch(String question) {
        try {
            float[] queryVec = embed(question);
            if (queryVec == null) return null;

            var bestMatch = vectorCache.findBestMatch(queryVec);
            if (bestMatch == null) return null;

            String matchedQuestion = bestMatch.getKey();
            double score = bestMatch.getValue();

            // ⭐ 检测前缀扩展：用户问题以缓存问题开头且更长
            // 利用 TF 向量比较"剩余部分"和"缓存回复"的语义相似度
            // "多穿点衣服"→语义接近天气回复 → 缓存命中
            // "有什么好吃的"→语义远离天气回复 → 缓存未命中，交 Agent
            if (matchedQuestion != null && question.startsWith(matchedQuestion)
                    && question.length() > matchedQuestion.length()) {
                String remaining = question.substring(matchedQuestion.length()).trim();
                if (remaining.isEmpty()) return proceedWithPrefixHit(question, matchedQuestion, score);

                // 先获取缓存回复，再比较剩余部分和回复的语义
                String exactKey = EXACT_KEY_PREFIX + md5(matchedQuestion);
                String intentTag = redisTemplate.opsForValue().get(exactKey);
                if (intentTag == null) return null;
                CachedRouteDecision decision = getByIntentTag(intentTag);
                if (decision == null || decision.reply == null) return null;

                // 双方都 TF 向量化后算余弦相似度
                float[] extVec = embed(remaining);
                float[] repVec = embed(decision.reply);
                if (extVec == null || repVec == null) return proceedWithPrefixHit(question, matchedQuestion, score);

                double extSim = cosineSimilarity(extVec, repVec);
                log.info("[SemanticCache] 🔀 前缀扩展语义比对: extension={}, replySim={}",
                        remaining, String.format("%.4f", extSim));

                if (extSim >= EXTENSION_SIMILARITY_THRESHOLD) {
                    // 扩展语义接近缓存回复（如"多穿点衣服"≈天气数据）
                    decision._isPrefixMatch = true;
                    decision._intentMismatch = remaining.length() > 2;
                    return decision;
                }

                // 扩展语义远离缓存回复（如"有什么好吃的"≠天气数据）→ 交 Agent
                log.info("[SemanticCache] 🔀 前缀扩展语义不匹配, 走 Agent: extension={}, sim={}",
                        remaining, String.format("%.4f", extSim));
                return null;
            }

            // 普通向量匹配（非前缀扩展）
            log.info("[SemanticCache] ✅ 向量缓存命中: question={}, matched={}, score={}",
                    question, matchedQuestion, String.format("%.4f", score));
            String exactKey = EXACT_KEY_PREFIX + md5(matchedQuestion);
            String intentTag = redisTemplate.opsForValue().get(exactKey);
            if (intentTag == null) return null;
            return getByIntentTag(intentTag);
        } catch (Exception e) {
            log.warn("[SemanticCache] 向量匹配异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 前缀扩展命中：设置 prefix match 标记后返回缓存决策
     */
    private CachedRouteDecision proceedWithPrefixHit(String question, String matchedQuestion, double score) {
        log.info("[SemanticCache] ✅ 向量缓存命中(前缀扩展): question={}, matched={}, score={}",
                question, matchedQuestion, String.format("%.4f", score));
        try {
            String exactKey = EXACT_KEY_PREFIX + md5(matchedQuestion);
            String intentTag = redisTemplate.opsForValue().get(exactKey);
            if (intentTag == null) return null;

            CachedRouteDecision decision = getByIntentTag(intentTag);
            if (decision != null) {
                decision._isPrefixMatch = true;
                String remaining = question.substring(matchedQuestion.length()).trim();
                decision._intentMismatch = !remaining.isEmpty();
            }
            return decision;
        } catch (Exception e) {
            log.warn("[SemanticCache] 前缀扩展命中异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * ⭐ 关键词哈希匹配：分词 → 提取关键词 → 排序去重 → MD5
     * <p>
     * 无需 LLM 调用，使用 ChineseTokenizer 进行中文分词，
     * 提取名词性关键词后排序拼接，计算 MD5 作为缓存 key。
     * 语义相近的问题如"上海天气怎么样"和"上海今天气温多少度"
     * 会提取出 {"上海", "天气"} 这样相同的关键词集合 → 命中同一缓存。
     */
    private CachedRouteDecision getByKeywordHash(String question) {
        try {
            List<String> keywords = extractKeywords(question);
            if (keywords.isEmpty()) return null;

            String keywordHash = md5(String.join(",", keywords));

            // 第一步：查关键词回复缓存（直接命中回复，无需再调 Agent）
            String keywordReplyKey = KEYWORD_REPLY_KEY_PREFIX + keywordHash;
            String replyJson = redisTemplate.opsForValue().get(keywordReplyKey);
            if (replyJson != null) {
                try {
                    CachedReply cachedReply = objectMapper.readValue(replyJson, CachedReply.class);
                    String intentTagKey = KEYWORD_KEY_PREFIX + keywordHash;
                    String intentTag = redisTemplate.opsForValue().get(intentTagKey);
                    if (intentTag != null) {
                        CachedRouteDecision decision = getByIntentTag(intentTag);
                        if (decision != null) {
                            decision.reply = cachedReply.reply;
                            decision.hitCount = cachedReply.hitCount;
                            log.info("[SemanticCache] ✅ 关键词+回复全缓存命中: intent={}, agent={}", intentTag, decision.agentName);
                            return decision;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SemanticCache] 关键词回复缓存解析失败: {}", e.getMessage());
                }
            }

            // 第二步：查关键词路由缓存（仅命中路由决策，仍需 Agent 执行）
            String keywordKey = KEYWORD_KEY_PREFIX + keywordHash;
            String cachedIntentTag = redisTemplate.opsForValue().get(keywordKey);

            if (cachedIntentTag != null) {
                log.debug("[SemanticCache] 关键词哈希命中: keywords={}, intent={}", keywords, cachedIntentTag);
                CachedRouteDecision decision = getByIntentTag(cachedIntentTag);
                if (decision != null) {
                    log.info("[SemanticCache] ✅ 关键词路由缓存命中: intent={}, agent={}", cachedIntentTag, decision.agentName);
                    return decision;
                }
            } else {
                log.debug("[SemanticCache] 关键词哈希未命中: keywords={}", keywords);
            }
        } catch (Exception e) {
            log.warn("[SemanticCache] 关键词匹配异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 提取问题中的关键词（统一逻辑，供查询和保存共用）
     */
    private List<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) return Collections.emptyList();
        try {
            Set<String> tokens = tokenizer.tokenize(question);
            if (tokens.isEmpty()) return Collections.emptyList();

            return tokens.stream()
                    .filter(t -> t.length() >= 2 && !t.matches("\\d+") && !ReplyFormatter.KEYWORD_STOP_WORDS.contains(t))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[SemanticCache] 关键词提取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 精确匹配：按问题原文 MD5 直接查
     * <p>
     * 如果路由决策命中但回复未缓存，兜底检查关键词回复缓存，
     * 使精确匹配也能享受 keyword reply cache 的收益。
     */
    private CachedRouteDecision getByExactQuestion(String question) {
        String exactKey = EXACT_KEY_PREFIX + md5(question);
        String intentTag = redisTemplate.opsForValue().get(exactKey);
        if (intentTag != null) {
            log.debug("[SemanticCache] 精确匹配命中: {}", intentTag);
            CachedRouteDecision decision = getByIntentTag(intentTag);
            if (decision != null) {
                // 路由决策命中但回复未缓存时，兜底关键词回复缓存
                if (decision.reply == null || decision.reply.isBlank()) {
                    CachedRouteDecision keywordFallback = getByKeywordHash(question);
                    if (keywordFallback != null && keywordFallback.reply != null && !keywordFallback.reply.isBlank()) {
                        decision.reply = keywordFallback.reply;
                        log.info("[SemanticCache] ✅ 精确匹配+关键词回复兜底: intent={}, agent={}", intentTag, decision.agentName);
                    }
                } else {
                    log.info("[SemanticCache] ✅ 精确缓存命中: intent={}, agent={}", intentTag, decision.agentName);
                }
            }
            return decision;
        }
        return null;
    }

    /**
     * 按意图标签查缓存
     */
    private CachedRouteDecision getByIntentTag(String intentTag) {
        try {
            String key = CACHE_KEY_PREFIX + md5(intentTag);
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("[SemanticCache] 语义缓存未命中: intent={}", intentTag);
                return null;
            }

            CachedRouteDecision decision = objectMapper.readValue(json, CachedRouteDecision.class);
            String replyKey = REPLY_KEY_PREFIX + md5(intentTag);
            String replyJson = redisTemplate.opsForValue().get(replyKey);

            if (replyJson != null) {
                CachedReply cachedReply = objectMapper.readValue(replyJson, CachedReply.class);
                decision.reply = cachedReply.reply;
                decision.hitCount = cachedReply.hitCount + 1;
                decision.firstCachedAt = cachedReply.firstCachedAt;

                cachedReply.hitCount = decision.hitCount;
                long ttl = getTtlForReply(decision.agentName, decision.originalQuestion);

                if (decision.hitCount >= 3) {
                    ttl = Math.max(ttl, 7200);
                }
                redisTemplate.opsForValue().set(replyKey, objectMapper.writeValueAsString(cachedReply), ttl, TimeUnit.SECONDS);
            }

            log.info("[SemanticCache] ✅ 语义缓存命中: intent={}, agent={}, hit={}, hasReply={}",
                    intentTag, decision.agentName, decision.hitCount, decision.reply != null);
            return decision;
        } catch (Exception e) {
            log.warn("[SemanticCache] 读取缓存异常: {}", e.getMessage());
            return null;
        }
    }

    /*
      保存路由决策（不含回复内容）+ 写入决策审计日志
      @param requestId  请求ID（用于审计日志，可为 null）
     * @param question   用户问题
     * @param agentName  路由目标 Agent
     * @param confidence 置信度
     * @param userId     用户ID（为 null 时不记录用户意图）
     */
    /**
     * 保存路由决策（自动生成 intentTag）
     * @deprecated 推荐使用 {@link #saveDecision(String, String, String, double, Long, String)} 传入已生成的 intentTag
     */
    @Deprecated
    public void saveDecision(String requestId, String question, String agentName,
                           double confidence, Long userId) {
        String intentTag = generateIntentTag(question);
        if (intentTag != null) {
            saveDecision(requestId, question, agentName, confidence, userId, intentTag);
        }
    }

    /**
     * 保存路由决策（使用外部传入的 intentTag，避免 LLM 非确定性导致标签不一致）
     * <p>
     * 调用方应先生成 intentTag 并复用，确保 saveDecision 和 saveReply 使用相同的标签
     */
    public void saveDecision(String requestId, String question, String agentName,
                           double confidence, Long userId, String intentTag) {
        saveDecision(requestId, question, agentName, confidence, userId, intentTag, null);
    }

    /**
     * 保存路由决策（含会话 ID，用于同会话内 LLM 改写适配）
     */
    public void saveDecision(String requestId, String question, String agentName,
                           double confidence, Long userId, String intentTag, String sessionId) {
        if (!cacheEnabled || redisTemplate == null || question == null || agentName == null || intentTag == null) return;

        try {
            // 保存路由决策
            CachedRouteDecision decision = new CachedRouteDecision(intentTag, agentName, confidence, question);
            decision.firstUserId = userId;  // ⭐ 记录首次提问用户
            decision.firstSessionId = sessionId;  // ⭐ 记录首次提问会话（同会话内做 LLM 改写）
            String json = objectMapper.writeValueAsString(decision);
            String key = CACHE_KEY_PREFIX + md5(intentTag);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // 保存精确映射（使用原始问题，requestId 参数在此处仅用于审计）
            // 注意：精确映射也会在 saveExactMatch 中重新覆盖为原始问题，此处保留全 Prompt 用于审计追溯
            String exactKey = EXACT_KEY_PREFIX + md5(question);
            redisTemplate.opsForValue().set(exactKey, intentTag, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // ⭐ 保存关键词哈希映射（分词 → 排序 → MD5，使同类问题共享缓存）
            saveKeywordHash(question, intentTag);

            // ⭐ 保存向量缓存（本地 BGE 嵌入，用于 T3 语义匹配）
            saveVectorCache(question);

            // ⭐ 记录用户意图分布（写入 Redis Hash: user:intent:{userId}）
            if (userId != null) {
                saveUserIntent(userId, intentTag);
            }

            // ⭐ 递增全局意图计数（用于判断高频问题）
            incrementGlobalIntentCount(intentTag);

            // ⭐ 写入决策审计日志
            if (requestId != null && !requestId.isBlank()) {
                DecisionAuditRecord audit = new DecisionAuditRecord(requestId, question, intentTag,
                        agentName, confidence, userId);
                saveDecisionAudit(audit);
            }

            log.info("[SemanticCache] 💾 已缓存决策: intent={}, agent={}, requestId={}",
                    intentTag, agentName, requestId);
        } catch (Exception e) {
            log.warn("[SemanticCache] 写入决策缓存失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 保存精确匹配映射（使用原始问题，覆盖带 Prompt 模板的完整文本）
     * <p>
     * Router 接收到的 question 包含【用户历史信息】【当前问题】等模板标记 + 动态历史计数，
     * 导致每次请求的 MD5 都不同。调用方应在外部提取原始问题后调用此方法，
     * 确保精确匹配 key 基于稳定的原始问题文本。
     */
    public void saveExactMatch(String rawQuestion, String intentTag) {
        if (!cacheEnabled || redisTemplate == null || rawQuestion == null || rawQuestion.isBlank() || intentTag == null) return;
        try {
            String exactKey = EXACT_KEY_PREFIX + md5(rawQuestion);
            redisTemplate.opsForValue().set(exactKey, intentTag, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // 同时也保存关键词哈希，新问题即使不精确匹配也能命中
            saveKeywordHash(rawQuestion, intentTag);

            log.debug("[SemanticCache] 精确匹配覆盖: rawQuestion={}, intent={}", rawQuestion, intentTag);
        } catch (Exception e) {
            log.warn("[SemanticCache] 保存精确匹配失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 保存向量缓存（本地 BGE 嵌入）
     */
    private void saveVectorCache(String question) {
        if (question == null || question.isBlank()) return;
        try {
            float[] vec = embed(question);
            if (vec != null) vectorCache.put(question, vec);
        } catch (Exception e) {
            log.debug("[SemanticCache] 保存向量缓存失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 保存关键词哈希映射（分词 → 排序去重 → MD5）
     * <p>
     * 使语义相近的问题共享同一缓存 key。
     * 例如："上海天气怎么样" 和 "上海今天气温多少度"
     * → tokenize → {"上海", "天气"} → sorted → "上海,天气" → md5
     * 两问题共享同一 intentTag，后续同类问题无需 LLM 即可命中。
     */
    private void saveKeywordHash(String question, String intentTag) {
        if (!cacheEnabled || redisTemplate == null || question == null || question.isBlank() || intentTag == null) return;
        try {
            List<String> keywords = extractKeywords(question);
            if (keywords.isEmpty()) return;

            String keywordHash = md5(String.join(",", keywords));
            String keywordKey = KEYWORD_KEY_PREFIX + keywordHash;
            redisTemplate.opsForValue().set(keywordKey, intentTag, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("[SemanticCache] 关键词哈希已保存: keywords={}, key={}", keywords, keywordKey);
        } catch (Exception e) {
            log.warn("[SemanticCache] 保存关键词哈希失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 保存关键词级别回复缓存
     * <p>
     * 使共享相同关键词的问题（如"上海天气怎么样"和"上海天气如何"）
     * 在第一次 Agent 执行后，后续同类问题直接命中回复缓存，无需再调 Agent。
     */
    private void saveKeywordReply(String question, String reply, String agentName) {
        saveKeywordReply(question, reply, agentName, null);
    }

    private void saveKeywordReply(String question, String reply, String agentName, Long ttlOverride) {
        if (!cacheEnabled || redisTemplate == null || reply == null || reply.isBlank()) return;
        try {
            List<String> keywords = extractKeywords(question);
            if (keywords.isEmpty()) return;

            String keywordHash = md5(String.join(",", keywords));
            String replyKey = KEYWORD_REPLY_KEY_PREFIX + keywordHash;

            CachedReply cachedReply = new CachedReply(reply, agentName, question);
            String replyJson = objectMapper.writeValueAsString(cachedReply);
            long ttl = ttlOverride != null ? ttlOverride : getTtlForReply(agentName, question);
            redisTemplate.opsForValue().set(replyKey, replyJson, ttl, TimeUnit.SECONDS);
            log.info("[SemanticCache] 💾 关键词级别回复已缓存: keywords={}, agent={}, ttl={}", keywords, agentName, ttl);
        } catch (Exception e) {
            log.warn("[SemanticCache] 保存关键词回复缓存失败: {}", e.getMessage());
        }
    }

    /*
      保存回复内容缓存（意图维度，供后续相同意图命中时直接返回）
      应在 Agent 返回回复后调用，建议异步执行，不阻塞主流程
     */

    /**
     * ⭐ 判断是否为高频问题（被问到 >= 阈值次）
     */
    private boolean isHighFrequencyQuestion(String intentTag) {
        if (intentTag == null || redisTemplate == null) return false;
        try {
            String key = GLOBAL_INTENT_COUNT_PREFIX + intentTag;
            String countStr = redisTemplate.opsForValue().get(key);
            if (countStr != null) {
                int count = Integer.parseInt(countStr);
                return count >= HIGH_FREQUENCY_THRESHOLD;
            }
        } catch (Exception e) {
            log.debug("[SemanticCache] 读取全局意图计数失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * ⭐ 递增全局意图计数
     */
    private void incrementGlobalIntentCount(String intentTag) {
        if (intentTag == null || redisTemplate == null) return;
        try {
            String key = GLOBAL_INTENT_COUNT_PREFIX + intentTag;
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, 30, TimeUnit.DAYS);  // 保留 30 天
        } catch (Exception e) {
            log.warn("[SemanticCache] 递增全局意图计数失败: {}", e.getMessage());
        }
    }

    /**
     * 保存回复内容缓存（自动生成 intentTag）
     * @deprecated 推荐使用 {@link #saveReply(String, String, String, String)} 传入已生成的 intentTag
     */
    @Deprecated
    public void saveReply(String question, String reply, String agentName) {
        saveReply(question, reply, agentName, false);
    }

    public void saveReply(String question, String reply, String agentName, boolean adminOperation) {
        String intentTag = generateIntentTag(question);
        if (intentTag != null) {
            saveReply(question, reply, agentName, intentTag, null, adminOperation);
        }
    }

    /*
      保存回复内容缓存（使用外部传入的 intentTag）
      <p>
      高频问题（被问到 ≥HIGH_FREQUENCY_THRESHOLD 次）的回复会被缓存。
      下次相同 intent 的请求直接返回缓存回复，不再调用 Agent。
     */
    /**
     * 保存回复内容缓存（自动计算 TTL）
     * <p>
     * TTL 优先级：ttlOverride 参数 > Agent 声明 metadata > Router 默认值
     */
    public void saveReply(String question, String reply, String agentName, String intentTag) {
        saveReply(question, reply, agentName, intentTag, null, false);
    }

    public void saveReply(String question, String reply, String agentName, String intentTag, boolean adminOperation) {
        saveReply(question, reply, agentName, intentTag, null, adminOperation);
    }

    /**
     * 保存回复内容缓存（指定 TTL 覆写）
     *
     * @param ttlOverride 显式 TTL（秒），为 null 时自动计算。
     *                    由调用方（如 AgentCallerService）根据实际调用的工具集
     *                    计算有效 TTL（取所有工具 TTL 的最小值），
     *                    遵循"木板效应"：整个回答的时效性取决于所用数据中最短的那个。
     */
    public void saveReply(String question, String reply, String agentName, String intentTag, Long ttlOverride) {
        saveReply(question, reply, agentName, intentTag, ttlOverride, false);
    }

    public void saveReply(String question, String reply, String agentName, String intentTag, Long ttlOverride, boolean adminOperation) {
        if (!cacheEnabled || redisTemplate == null || reply == null || reply.isBlank() || intentTag == null) return;

        try {
            // ⭐ 管理员工具操作（如知识库同步）的回复不缓存
            if (adminOperation) {
                log.debug("[SemanticCache] 管理员工具操作跳过回复缓存: agent={}", agentName);
                return;
            }

            long ttl = ttlOverride != null ? ttlOverride : getTtlForReply(agentName, question);
            // ⭐ Agent 声明的 TTL 低于阈值时不缓存回复内容，仅缓存路由决策
            // 例如天气类 cache-ttl-seconds=1200(<3600) → 每次从 Agent 拿最新数据
            if (ttl < MIN_CACHE_TTL_SECONDS) {
                log.debug("[SemanticCache] 短时效 Agent 跳过回复缓存: agent={}, ttl={}", agentName, ttl);
                return;
            }

            // ⭐ 无论是否高频，都保存关键词级别回复缓存（使同类问题共享回复）
            saveKeywordReply(question, reply, agentName, ttl);

            // ⭐ 高频问题额外保存意图维度回复缓存（精确匹配命中时用）
            if (!isHighFrequencyQuestion(intentTag)) {
                log.debug("[SemanticCache] 跳过低频问题意图回复缓存: intent={}, agent={}", intentTag, agentName);
                return;
            }

            CachedReply cachedReply = new CachedReply(reply, agentName, question);
            String replyJson = objectMapper.writeValueAsString(cachedReply);
            String replyKey = REPLY_KEY_PREFIX + md5(intentTag);
            redisTemplate.opsForValue().set(replyKey, replyJson, ttl, TimeUnit.SECONDS);

            log.info("[SemanticCache] 💾 已缓存回复(高频问题): intent={}, agent={}, ttl={}", intentTag, agentName, ttl);
        } catch (Exception e) {
            log.warn("[SemanticCache] 写入回复缓存失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 包装缓存回复：前缀变化 + LLM 改写 + 前缀匹配
     * <p>
     * 缓存回复是对原问题的回答。当新表述与原问题不同时（关键词/语义匹配命中），
     * 无论是否同一会话，都需要 LLM 改写将回复适配到新表述。
     * 仅当新表述已通过精确匹配出现过（精确 key 已存在），才跳过 LLM 走前缀变化。
     */
    public String wrapCachedReply(String reply, CachedRouteDecision cached, String userQuestion, Long userId) {
        boolean isNewPhrasing = false;
        if (userQuestion != null && cached != null && !userQuestion.equals(cached.originalQuestion)) {
            try {
                var valueOps = redisTemplate != null ? redisTemplate.opsForValue() : null;
                if (valueOps != null) {
                    String exactKey = EXACT_KEY_PREFIX + md5(userQuestion);
                    isNewPhrasing = valueOps.get(exactKey) == null;
                }
            } catch (Exception e) {
                log.debug("[SemanticCache] 检查新表述时异常: {}", e.getMessage());
            }
        }
        return replyFormatter.wrapCachedReply(reply, cached, userQuestion, userId, isNewPhrasing);
    }

    long getTtlForReply(String agentName, String originalQuestion) {
        return replyFormatter.getTtlForReply(agentName, originalQuestion);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * 用 LLM 生成意图标签（3-5个字），语义相近的问题归一化到同一标签
     */
    public String generateIntentTag(String question) {
        try {
            String tag = chatClient.prompt()
                    .user("用3-5个字概括这个问题的意图，只输出标签，不要解释：\n" + question)
                    .call()
                    .content();
            if (tag != null) {
                tag = tag.trim().replaceAll("[\"'\\n\\r]", "");
                if (tag.length() > 10) tag = tag.substring(0, 10);
                return tag;
            }
        } catch (Exception e) {
            log.warn("[SemanticCache] LLM 意图标签生成失败: {}", e.getMessage());
        }
        return null;
    }

    private String md5(String str) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(str.hashCode());
        }
    }

    public static class CachedRouteDecision {
        public String intentTag;
        public String agentName;
        public double confidence;
        public String originalQuestion;
        public long cachedAt;
        public int hitCount;
        public long firstCachedAt;
        public String reply;
        public Long firstUserId;  // ⭐ 首次提问的用户ID
        public String firstSessionId;  // ⭐ 首次提问的会话ID，仅同会话内做 LLM 改写
        public transient boolean _isPrefixMatch;
        public transient boolean _intentMismatch;

        public CachedRouteDecision() {}  // ⭐ Jackson 反序列化需要无参构造

        public CachedRouteDecision(String intentTag, String agentName, double confidence, String originalQuestion) {
            this.intentTag = intentTag;
            this.agentName = agentName;
            this.confidence = confidence;
            this.originalQuestion = originalQuestion;
            this.cachedAt = System.currentTimeMillis();
            this.firstCachedAt = this.cachedAt;
            this.hitCount = 1;
        }
    }

    public static class CachedReply {
        public String reply;
        public String agentName;
        public String originalQuestion;
        public long firstCachedAt;
        public int hitCount;

        public CachedReply() {}  // ⭐ Jackson 反序列化需要无参构造

        public CachedReply(String reply, String agentName, String originalQuestion) {
            this.reply = reply;
            this.agentName = agentName;
            this.originalQuestion = originalQuestion;
            this.firstCachedAt = System.currentTimeMillis();
            this.hitCount = 1;
        }
    }

    // ==================== 决策审计日志（按 requestId 维度写入，供调试追溯）====================

    /**
     * 决策审计记录（写入 Redis，TTL 7天）
     */
    public static class DecisionAuditRecord {
        public String requestId;
        public String question;
        public String intentTag;
        public String agentName;
        public double confidence;
        public Long userId;
        public long decisionAt;

        public DecisionAuditRecord(String requestId, String question, String intentTag,
                                  String agentName, double confidence, Long userId) {
            this.requestId = requestId;
            this.question = question;
            this.intentTag = intentTag;
            this.agentName = agentName;
            this.confidence = confidence;
            this.userId = userId;
            this.decisionAt = System.currentTimeMillis();
        }
    }

    /**
     * 保存决策审计日志（按 requestId，TTL 7天）
     */
    public void saveDecisionAudit(DecisionAuditRecord record) {
        if (record == null || record.requestId == null) return;
        if (redisTemplate == null) return;
        try {
            String key = DECISION_AUDIT_KEY_PREFIX + record.requestId;
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(key, json, DECISION_AUDIT_TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("[SemanticCache] 决策审计已记录: requestId={}, intent={}, agent={}",
                    record.requestId, record.intentTag, record.agentName);
        } catch (Exception e) {
            log.warn("[SemanticCache] 保存决策审计失败: {}", e.getMessage());
        }
    }

    /**
     * 读取决策审计日志（供调试使用）
     */
    public DecisionAuditRecord getDecisionAudit(String requestId) {
        if (requestId == null || redisTemplate == null) return null;
        try {
            String key = DECISION_AUDIT_KEY_PREFIX + requestId;
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, DecisionAuditRecord.class);
            }
        } catch (Exception e) {
            log.warn("[SemanticCache] 读取决策审计失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * ⭐ 保存完整决策到 Redis（供 Consumer 读取）
     * <p>
     * 与审计日志不同，这个 key 包含 Agent 的完整回复（result）和意图标签（intentTag），
     * Consumer 通过 requestId 阻塞读取这个 key 来获取路由结果和意图。
     * TTL 设置为 10 秒，足够 Consumer 读取即可。
     */
    public void saveFullDecisionForConsumer(String requestId, String agentName,
                                           double confidence, String result, String intentTag) {
        if (requestId == null || requestId.isBlank() || redisTemplate == null) return;
        try {
            Map<String, Object> decision = new HashMap<>();
            decision.put("requestId", requestId);
            decision.put("agentName", agentName);
            decision.put("confidence", confidence);
            decision.put("result", result);
            decision.put("intentTag", intentTag);  // ⭐ 新增：意图标签
            decision.put("timestamp", System.currentTimeMillis());

            String key = FULL_DECISION_KEY_PREFIX + requestId;  // 使用独立 key，不覆盖审计日志
            String json = objectMapper.writeValueAsString(decision);
            redisTemplate.opsForValue().set(key, json, 120, TimeUnit.SECONDS);
            log.debug("[SemanticCache] 完整决策已写入 Redis(供Consumer读取): requestId={}, agent={}, intent={}",
                    requestId, agentName, intentTag);
        } catch (Exception e) {
            log.warn("[SemanticCache] 写入完整决策失败: {}", e.getMessage());
        }
    }

    // ==================== 用户意图分布（按用户维度写入 Redis）====================

    private static final String USER_INTENT_KEY_PREFIX = "user:intent:";

    /**
     * 保存用户意图到 Redis（Hash 结构：field=intentTag, value=count）
     * Key: user:intent:{userId}
     *  TTL: 30天（用户意图分布是长期有用的）
     */
    public void saveUserIntent(Long userId, String intentTag) {
        if (userId == null || intentTag == null || intentTag.isBlank()) return;
        if (redisTemplate == null) return;
        try {
            String key = USER_INTENT_KEY_PREFIX + userId;
            redisTemplate.opsForHash().increment(key, intentTag, 1);
            redisTemplate.expire(key, 30, TimeUnit.DAYS);
            log.debug("[SemanticCache] 用户意图已记录: userId={}, intent={}", userId, intentTag);
        } catch (Exception e) {
            log.warn("[SemanticCache] 保存用户意图失败: userId={}, error={}", userId, e.getMessage());
        }
    }

}