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
    private static final String PREFIX_KEY_PREFIX = "a2a:route:prefix:";
    private static final String KEYWORD_KEY_PREFIX = "a2a:route:keyword:";
    private static final String KEYWORD_REPLY_KEY_PREFIX = "a2a:route:keyword:reply:";  // ⭐ 关键词级别回复缓存
    private static final String DECISION_AUDIT_KEY_PREFIX = "a2a:route:decision:";
    private static final String FULL_DECISION_KEY_PREFIX = "a2a:route:full-decision:";  // ⭐ 供 Consumer 读取的完整决策（独立 key）
    private static final String GLOBAL_INTENT_COUNT_PREFIX = "intent:global:count:";  // ⭐ 全局意图计数（判断高频问题）
    private static final long CACHE_TTL_SECONDS = 86400;
    private static final long DECISION_AUDIT_TTL_SECONDS = 604800; // 7天
    private static final int HIGH_FREQUENCY_THRESHOLD = 2;  // ⭐ 被问到 >= 2次视为高频（降至2使第二次起全缓存命中，大幅减少 Agent 压力）

    // ⭐ 关键词缓存专用停用词（补充 ChineseTokenizer 的停用词表）
    private static final Set<String> KEYWORD_STOP_WORDS = Set.of(
            "怎么样", "如何", "怎么", "什么", "为啥", "为何",
            "请问", "能", "可以", "需要", "想要", "想去",
            "吗", "呢", "吧", "啊", "哦", "么",
            "这个", "那个", "哪些", "这些", "那些",
            "有没有", "是不是", "会不会", "能不能", "要不要"
    );

    private final StringRedisTemplate redisTemplate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();
    private final ChineseTokenizer tokenizer;
    private final AgentDiscoveryService agentDiscoveryService;

    @Value("${router.semantic-cache.enabled:true}")
    private boolean cacheEnabled;

    public SemanticRouteCacheService(
            ChatClient.Builder chatClientBuilder,
            StringRedisTemplate redisTemplate,
            ChineseTokenizer tokenizer,
            AgentDiscoveryService agentDiscoveryService) {
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.tokenizer = tokenizer;
        this.agentDiscoveryService = agentDiscoveryService;
    }

    /**
     * 获取缓存决策：四层匹配
     * <p>
     * Tier 1: 精确匹配（MD5 快速路径）— 完全相同的字符串
     * Tier 2: 关键词哈希匹配（分词 → 取关键名词 → MD5）— 跳过 LLM
     * Tier 3: 语义匹配（LLM 生成意图标签）— 兜底方案
     * Tier 4: 前缀匹配（前 8 字符）— 部分命中
     */
    public CachedRouteDecision getCachedDecision(String question) {
        if (!cacheEnabled || redisTemplate == null || question == null || question.isBlank()) {
            return null;
        }
        try {
            // Tier 1: 精确匹配快速路径（跳过 LLM）
            CachedRouteDecision exact = getByExactQuestion(question);
            if (exact != null) return exact;

            // Tier 2: ⭐ 关键词哈希匹配（分词 → 排序 → MD5，无需 LLM）
            CachedRouteDecision keyword = getByKeywordHash(question);
            if (keyword != null) return keyword;

            // Tier 3: 语义匹配（LLM 生成意图标签）
            String intentTag = generateIntentTag(question);
            if (intentTag != null) {
                CachedRouteDecision semantic = getByIntentTag(intentTag);
                if (semantic != null) return semantic;
            }

            // Tier 4: ⭐ 前缀匹配：前半段命中缓存（需验证意图一致）
            if (intentTag != null) {
                CachedRouteDecision prefixMatch = getByPrefixWithTag(question, intentTag);
                if (prefixMatch != null) return prefixMatch;
            }

        } catch (Exception e) {
            log.warn("[SemanticCache] 读取缓存失败: {}", e.getMessage());
        }
        return null;
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
                    .filter(t -> t.length() >= 2 && !t.matches("\\d+") && !KEYWORD_STOP_WORDS.contains(t))
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
      ⭐ 前缀匹配：用户提问的前半段是否命中缓存
     */
    /**
     * ⭐ 前缀匹配：前半段命中缓存
     * <p>
     * 即使后半段意图不同，也返回缓存回复 + 过渡建议。
     * 例如用户问"北京天气的美食推荐"，缓存有"北京天气"的回复，
     * 则返回天气信息 + "说到美食，你想了解北京的什么美食呢？"
     */
    private CachedRouteDecision getByPrefixWithTag(String question, String userIntentTag) {
        if (question == null || question.length() < 4) return null;
        try {
            String prefix = question.substring(0, Math.min(8, question.length()));
            String prefixKey = PREFIX_KEY_PREFIX + md5(prefix);
            String cachedTag = redisTemplate.opsForValue().get(prefixKey);
            if (cachedTag == null) return null;

            CachedRouteDecision decision = getByIntentTag(cachedTag);
            if (decision != null && decision.originalQuestion != null
                    && question.startsWith(decision.originalQuestion)) {
                decision._isPrefixMatch = true;
                // 标记意图是否一致
                decision._intentMismatch = userIntentTag != null && !userIntentTag.equals(cachedTag);
                log.info("[SemanticCache] 🔍 前缀匹配命中: prefix={}, cachedQ={}, intentMatch={}",
                        prefix, decision.originalQuestion, !decision._intentMismatch);
                return decision;
            }
        } catch (Exception e) {
            log.debug("[SemanticCache] 前缀匹配异常: {}", e.getMessage());
        }
        return null;
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
        if (!cacheEnabled || redisTemplate == null || question == null || agentName == null || intentTag == null) return;

        try {
            // 保存路由决策
            CachedRouteDecision decision = new CachedRouteDecision(intentTag, agentName, confidence, question);
            decision.firstUserId = userId;  // ⭐ 记录首次提问用户，用于前缀个性化
            String json = objectMapper.writeValueAsString(decision);
            String key = CACHE_KEY_PREFIX + md5(intentTag);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // 保存精确映射（使用原始问题，requestId 参数在此处仅用于审计）
            // 注意：精确映射也会在 saveExactMatch 中重新覆盖为原始问题，此处保留全 Prompt 用于审计追溯
            String exactKey = EXACT_KEY_PREFIX + md5(question);
            redisTemplate.opsForValue().set(exactKey, intentTag, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // 保存前缀映射（前 8 个字符 → intentTag）
            String prefix = question.substring(0, Math.min(8, question.length()));
            String prefixKey = PREFIX_KEY_PREFIX + md5(prefix);
            redisTemplate.opsForValue().set(prefixKey, intentTag, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // ⭐ 保存关键词哈希映射（分词 → 排序 → MD5，使同类问题共享缓存）
            saveKeywordHash(question, intentTag);

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
        String intentTag = generateIntentTag(question);
        if (intentTag != null) {
            saveReply(question, reply, agentName, intentTag);
        }
    }

    /**
     * 保存回复内容缓存（使用外部传入的 intentTag）
     * <p>
     * 高频问题（被问到 ≥HIGH_FREQUENCY_THRESHOLD 次）的回复会被缓存。
     * 下次相同 intent 的请求直接返回缓存回复，不再调用 Agent。
     */
    /**
     * 保存回复内容缓存（自动计算 TTL）
     * <p>
     * TTL 优先级：ttlOverride 参数 > Agent 声明 metadata > Router 默认值
     */
    public void saveReply(String question, String reply, String agentName, String intentTag) {
        saveReply(question, reply, agentName, intentTag, null);
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
        if (!cacheEnabled || redisTemplate == null || reply == null || reply.isBlank() || intentTag == null) return;

        try {
            // ⭐ 无论是否高频，都保存关键词级别回复缓存（使同类问题共享回复）
            saveKeywordReply(question, reply, agentName, ttlOverride);

            // ⭐ 高频问题额外保存意图维度回复缓存（精确匹配命中时用）
            if (!isHighFrequencyQuestion(intentTag)) {
                log.debug("[SemanticCache] 跳过低频问题意图回复缓存: intent={}, agent={}", intentTag, agentName);
                return;
            }

            CachedReply cachedReply = new CachedReply(reply, agentName, question);
            String replyJson = objectMapper.writeValueAsString(cachedReply);
            String replyKey = REPLY_KEY_PREFIX + md5(intentTag);
            long ttl = ttlOverride != null ? ttlOverride : getTtlForReply(agentName, question);
            redisTemplate.opsForValue().set(replyKey, replyJson, ttl, TimeUnit.SECONDS);

            log.info("[SemanticCache] 💾 已缓存回复(高频问题): intent={}, agent={}, ttl={}", intentTag, agentName, ttl);
        } catch (Exception e) {
            log.warn("[SemanticCache] 写入回复缓存失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 包装缓存回复：前缀变化 + Phase 2 LLM 改写 + 前缀匹配针对性改写
     * <p>
     * 根据 userId 判断是否为同一用户，不同用户时使用中性前缀。
     */
    public String wrapCachedReply(String reply, CachedRouteDecision cached, String userQuestion, Long userId) {
        if (reply == null || reply.isBlank()) return reply;

        // ⭐ 清理缓存回复中可能残留的旧前缀（避免嵌套）
        reply = stripPrefixes(reply);

        boolean sameUser = userId != null && userId.equals(cached.firstUserId);

        long elapsed = System.currentTimeMillis() - (cached.firstCachedAt > 0 ? cached.firstCachedAt : cached.cachedAt);
        long elapsedHours = elapsed / 3600000;

        // ⭐ 前缀匹配：先回复已命中部分，再用过渡语引导用户到实际意图
        if (cached._isPrefixMatch && userQuestion != null && !userQuestion.equals(cached.originalQuestion)) {
            if (cached._intentMismatch) {
                String extra = cached.originalQuestion + "方面的信息如上所述。"
                        + "关于你提到的其他内容，你可以试试这样问我：";
                log.info("[SemanticCache] 🔀 前缀命中意图不匹配，追加过渡建议");
                return stripPrefixes(reply) + "\n\n---\n💡 " + extra;
            }
            // 仅同用户调用 LLM 改写，不同用户直接返回缓存内容
            if (sameUser) {
                String adapted = rewriteForPrefixMatch(reply, cached.originalQuestion, userQuestion);
                if (adapted != null && !adapted.isBlank()) {
                    log.info("[SemanticCache] 🎯 前缀匹配针对性改写: question={}", userQuestion);
                    return adapted;
                }
            }
        }

        // Phase 2: 高命中次数（≥3）→ 仅对同用户的不同表述做 LLM 改写
        // 不同用户 → 直接走前缀变化（中性前缀，零 LLM 成本）
        // 相同问题 → 直接走前缀变化（用户极少重复提问，前缀变化已足够）
        if (cached.hitCount >= 3 && sameUser) {
            if (userQuestion != null && !userQuestion.equals(cached.originalQuestion)) {
                String adapted = rewriteForPrefixMatch(reply, cached.originalQuestion, userQuestion);
                if (adapted != null && !adapted.isBlank()) {
                    log.info("[SemanticCache] ✏️ 语义命中针对性改写: question={}", userQuestion);
                    return adapted;
                }
            }
        }

        // 前缀变化（仅当 Phase 2 未命中或改写失败时）
        String prefix;
        if (cached.hitCount >= 3) {
            List<String> tips;
            if (sameUser) {
                tips = Arrays.asList(
                        "（以下是我之前查到的信息，可能已有变化哦）",
                        "（这条信息是之前查询的，建议重新问一下获取最新数据）",
                        "（按上次查询的结果来看，可能会有些出入～）"
                );
            } else {
                tips = Arrays.asList(
                        "（以下是根据历史查询获取的信息）",
                        "（这条信息是此前查询得到的）",
                        "（以上信息来源于此前缓存的数据）"
                );
            }
            prefix = tips.get(random.nextInt(tips.size())) + "\n\n";
        } else if (elapsedHours > 6) {
            if (sameUser) {
                prefix = "📅 根据" + (elapsedHours > 24 ? "之前" : elapsedHours + "小时前") + "查询的信息：\n\n";
            } else {
                prefix = "📅 以下信息来源于" + (elapsedHours > 24 ? "之前的" : elapsedHours + "小时前的") + "数据：\n\n";
            }
        } else if (cached.hitCount == 2) {
            if (sameUser) {
                List<String> greetings = Arrays.asList(
                        "再帮你查一次，结果和之前一样～\n\n",
                        "跟上次查询的结果一致：\n\n",
                        "还是同样的结果：\n\n"
                );
                prefix = greetings.get(random.nextInt(greetings.size()));
            } else {
                List<String> neutral = Arrays.asList(
                        "查询结果如下：\n\n",
                        "以下是相关信息：\n\n",
                        "这是查询到的结果：\n\n"
                );
                prefix = neutral.get(random.nextInt(neutral.size()));
            }
        } else {
            prefix = "";
        }

        return prefix + reply;
    }

    /**
     * ⭐ 清理缓存回复中可能残留的旧前缀
     */
    private String stripPrefixes(String reply) {
        if (reply == null) return null;
        String[] knownPrefixes = {
                "再帮你查一次，结果和之前一样～\n\n",
                "跟上次查询的结果一致：\n\n",
                "还是同样的结果：\n\n",
                "（以下是我之前查到的信息，可能已有变化哦）\n\n",
                "（这条信息是之前查询的，建议重新问一下获取最新数据）\n\n",
                "（按上次查询的结果来看，可能会有些出入～）\n\n",
                "（以下是根据历史查询获取的信息）\n\n",
                "（这条信息是此前查询得到的）\n\n",
                "（以上信息来源于此前缓存的数据）\n\n",
                "查询结果如下：\n\n",
                "以下是相关信息：\n\n",
                "这是查询到的结果：\n\n"
        };
        for (String prefix : knownPrefixes) {
            while (reply.startsWith(prefix)) {
                reply = reply.substring(prefix.length()).trim();
            }
        }
        // 清理 LLM 变体格式的前缀（如 "1. 温馨提示...\n\n" 或 "1. 北京4月29日天气预报...\n\n" 等）
        reply = reply.replaceAll("^\\d+\\.\\s*[^\\n]+\\n\\n", "");
        return reply;
    }

    /**
     * Phase 2: LLM 改写回复（保持事实，改变语气和表达）
     */
    private String rewriteReply(String reply) {
        try {
            String result = chatClient.prompt()
                    .user("用不同语气重写，保持所有事实信息（温度、地点、数字、日期等）完全不变。"
                            + "不添加额外说明，直接输出重写结果：\n" + reply)
                    .call()
                    .content();
            if (result != null) {
                result = result.trim();
                int idx = result.indexOf("\n\n");
                if (idx > 0 && result.length() > idx + 50) {
                    String firstLine = result.substring(0, idx).trim();
                    if (firstLine.length() > 5 && !firstLine.contains(":") && !firstLine.contains("：")) {
                        result = result.substring(idx + 2).trim();
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[SemanticCache] LLM 改写失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * ⭐ 前缀匹配针对性改写：根据用户后半段提问，从缓存回复中提取并组织相关信息
     */
    private String rewriteForPrefixMatch(String cachedReply, String cachedQuestion, String userQuestion) {
        try {
            String result = chatClient.prompt()
                    .user("以下是一条已有的" + cachedQuestion + "回复。用户现在问的是：\"" + userQuestion + "\"。"
                            + "请基于已有回复中的事实数据，回答用户的完整问题。"
                            + "保持所有事实信息（温度、地点、数字）不变，直接输出回答：\n"
                            + cachedReply)
                    .call()
                    .content();
            return result != null ? result.trim() : null;
        } catch (Exception e) {
            log.warn("[SemanticCache] 前缀匹配改写失败: {}", e.getMessage());
            return null;
        }
    }

    /*
      Phase 3: 动态 TTL（天气类短，美食/旅行类长）
     */
    /**
     * Phase 3: 动态 TTL（优先读取 Agent 声明的缓存时间）
     * <p>
     * 各 Agent 在 application.yml 的 metadata 中通过 {@code cache-ttl-seconds} 声明自己的回复缓存 TTL。
     * Router 直接读取，无需硬编码各 Agent 的时效性逻辑。
     * 如 Agent 未声明，则使用以下默认值：
     * - builtin_fallback → 2h
     * - 其他 → 1h
     */
    private long getTtlForReply(String agentName, String originalQuestion) {
        // 优先读取 Agent 声明的 TTL
        if (agentName != null && agentDiscoveryService != null) {
            Long agentTtl = agentDiscoveryService.getAgentTtl(agentName);
            if (agentTtl != null && agentTtl > 0) {
                return agentTtl;
            }
        }

        // 兜底默认值
        if (agentName == null) return 3600;
        String lower = agentName.toLowerCase();
        if (lower.contains("builtin") || lower.contains("fallback")) {
            return 7200;
        }
        return 3600;
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
        public Long firstUserId;  // ⭐ 首次提问的用户ID，用于判断同/不同用户
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