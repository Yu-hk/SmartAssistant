package com.example.smartassistant.router.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

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
    private static final String DECISION_AUDIT_KEY_PREFIX = "a2a:route:decision:";
    private static final String FULL_DECISION_KEY_PREFIX = "a2a:route:full-decision:";  // ⭐ 供 Consumer 读取的完整决策（独立 key）
    private static final String GLOBAL_INTENT_COUNT_PREFIX = "intent:global:count:";  // ⭐ 全局意图计数（判断高频问题）
    private static final long CACHE_TTL_SECONDS = 86400;
    private static final long DECISION_AUDIT_TTL_SECONDS = 604800; // 7天
    private static final int HIGH_FREQUENCY_THRESHOLD = 10;  // ⭐ 被问到 >= 10次视为高频

    private final StringRedisTemplate redisTemplate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @Value("${router.semantic-cache.enabled:true}")
    private boolean cacheEnabled;

    public SemanticRouteCacheService(
            ChatClient.Builder chatClientBuilder,
            StringRedisTemplate redisTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取缓存决策：先精确匹配，再语义匹配
     */
    public CachedRouteDecision getCachedDecision(String question) {
        if (!cacheEnabled || redisTemplate == null || question == null || question.isBlank()) {
            return null;
        }
        try {
            // 1. 精确匹配快速路径（跳过 LLM）
            CachedRouteDecision exact = getByExactQuestion(question);
            if (exact != null) return exact;

            // 2. 语义匹配（LLM 生成意图标签）
            String intentTag = generateIntentTag(question);
            if (intentTag != null) {
                CachedRouteDecision semantic = getByIntentTag(intentTag);
                if (semantic != null) return semantic;
            }

            // 3. ⭐ 前缀匹配：前半段命中缓存（需验证意图一致）
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
     * 精确匹配：按问题原文 MD5 直接查
     */
    private CachedRouteDecision getByExactQuestion(String question) {
        String exactKey = EXACT_KEY_PREFIX + md5(question);
        String intentTag = redisTemplate.opsForValue().get(exactKey);
        if (intentTag != null) {
            log.debug("[SemanticCache] 精确匹配命中: {}", intentTag);
            CachedRouteDecision decision = getByIntentTag(intentTag);
            if (decision != null) {
                log.info("[SemanticCache] ✅ 精确缓存命中: intent={}, agent={}", intentTag, decision.agentName);
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
                long ttl = getTtlForReply(decision.agentName);

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

    /**
     * 保存路由决策（不含回复内容）+ 写入决策审计日志
     * @param requestId  请求ID（用于审计日志，可为 null）
     * @param question   用户问题
     * @param agentName  路由目标 Agent
     * @param confidence 置信度
     * @param userId     用户ID（为 null 时不记录用户意图）
     */
    public void saveDecision(String requestId, String question, String agentName,
                           double confidence, Long userId) {
        if (!cacheEnabled || redisTemplate == null || question == null || agentName == null) return;

        try {
            String intentTag = generateIntentTag(question);
            if (intentTag == null) return;

            // 保存路由决策
            CachedRouteDecision decision = new CachedRouteDecision(intentTag, agentName, confidence, question);
            String json = objectMapper.writeValueAsString(decision);
            String key = CACHE_KEY_PREFIX + md5(intentTag);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // 保存精确映射
            String exactKey = EXACT_KEY_PREFIX + md5(question);
            redisTemplate.opsForValue().set(exactKey, intentTag, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

            // 保存前缀映射（前 8 个字符 → intentTag）
            String prefix = question.substring(0, Math.min(8, question.length()));
            String prefixKey = PREFIX_KEY_PREFIX + md5(prefix);
            redisTemplate.opsForValue().set(prefixKey, intentTag, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

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

    public void saveReply(String question, String reply, String agentName) {
        if (!cacheEnabled || redisTemplate == null || reply == null || reply.isBlank()) return;

        try {
            String intentTag = generateIntentTag(question);
            if (intentTag == null) return;

            // ⭐ 只缓存高频问题的回复
            if (!isHighFrequencyQuestion(intentTag)) {
                log.debug("[SemanticCache] 跳过低频问题回复缓存: intent={}, agent={}", intentTag, agentName);
                return;
            }

            CachedReply cachedReply = new CachedReply(reply, agentName, question);
            String replyJson = objectMapper.writeValueAsString(cachedReply);
            String replyKey = REPLY_KEY_PREFIX + md5(intentTag);
            long ttl = getTtlForReply(agentName);
            redisTemplate.opsForValue().set(replyKey, replyJson, ttl, TimeUnit.SECONDS);

            log.info("[SemanticCache] 💾 已缓存回复(高频问题): intent={}, agent={}", intentTag, agentName);
        } catch (Exception e) {
            log.warn("[SemanticCache] 写入回复缓存失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 包装缓存回复：前缀变化 + Phase 2 LLM 改写 + 前缀匹配针对性改写
     */
    public String wrapCachedReply(String reply, CachedRouteDecision cached, String userQuestion) {
        if (reply == null || reply.isBlank()) return reply;

        // ⭐ 清理缓存回复中可能残留的旧前缀（避免嵌套）
        reply = stripPrefixes(reply);

        long elapsed = System.currentTimeMillis() - (cached.firstCachedAt > 0 ? cached.firstCachedAt : cached.cachedAt);
        long elapsedHours = elapsed / 3600000;

        // ⭐ 前缀匹配：先回复已命中部分，再用过渡语引导用户到实际意图
        if (cached._isPrefixMatch && userQuestion != null && !userQuestion.equals(cached.originalQuestion)) {
            if (cached._intentMismatch) {
                // 意图不匹配：给出缓存回复 + 过渡建议（不强行改写）
                String extra = cached.originalQuestion + "方面的信息如上所述。"
                        + "关于你提到的其他内容，你可以试试这样问我：";
                log.info("[SemanticCache] 🔀 前缀命中意图不匹配，追加过渡建议");
                return stripPrefixes(reply) + "\n\n---\n💡 " + extra;
            }
            // 意图一致：用 LLM 针对性改写
            String adapted = rewriteForPrefixMatch(reply, cached.originalQuestion, userQuestion);
            if (adapted != null && !adapted.isBlank()) {
                log.info("[SemanticCache] 🎯 前缀匹配针对性改写: question={}", userQuestion);
                return adapted;
            }
        }

        // Phase 2: 高命中次数（≥3）→ 针对用户问题改写
        if (cached.hitCount >= 3) {
            if (userQuestion != null && !userQuestion.equals(cached.originalQuestion)) {
                String adapted = rewriteForPrefixMatch(reply, cached.originalQuestion, userQuestion);
                if (adapted != null && !adapted.isBlank()) {
                    log.info("[SemanticCache] ✏️ 语义命中针对性改写: question={}", userQuestion);
                    return adapted;
                }
            } else if (cached.hitCount <= 10) {
                String rewritten = rewriteReply(reply);
                if (rewritten != null && !rewritten.isBlank()) {
                    log.info("[SemanticCache] ✏️ LLM 改写缓存回复: hit={}", cached.hitCount);
                    return rewritten;
                }
            }
        }

        // 前缀变化（仅当 Phase 2 未命中或改写失败时）
        String prefix;
        if (cached.hitCount >= 3) {
            List<String> tips = Arrays.asList(
                    "（以下是我之前查到的信息，可能已有变化哦）",
                    "（这条信息是之前查询的，建议重新问一下获取最新数据）",
                    "（按上次查询的结果来看，可能会有些出入～）"
            );
            prefix = tips.get(random.nextInt(tips.size())) + "\n\n";
        } else if (elapsedHours > 6) {
            prefix = "📅 根据" + (elapsedHours > 24 ? "之前" : elapsedHours + "小时前") + "查询的信息：\n\n";
        } else if (cached.hitCount == 2) {
            List<String> greetings = Arrays.asList(
                    "再帮你查一次，结果和之前一样～\n\n",
                    "跟上次查询的结果一致：\n\n",
                    "还是同样的结果：\n\n"
            );
            prefix = greetings.get(random.nextInt(greetings.size()));
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
                "（按上次查询的结果来看，可能会有些出入～）\n\n"
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

    /**
     * Phase 3: 动态 TTL（天气类短，美食/旅行类长）
     */
    private long getTtlForReply(String agentName) {
        if (agentName == null) return 3600;
        String lower = agentName.toLowerCase();
        if (lower.contains("weather") || lower.contains("location") || lower.contains("天气")) {
            return 1800;
        }
        if (lower.contains("food") || lower.contains("美食")) {
            return 86400;
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
        public transient boolean _isPrefixMatch;
        public transient boolean _intentMismatch;

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

        public CachedReply() {}

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