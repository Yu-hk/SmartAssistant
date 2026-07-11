package com.example.smartassistant.common.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 用户实体画像服务。
 * <p>
 * 将对话中提取的用户事实（"我怕狗"→ fear=狗）存储为结构化 KV，
 * 支持精准查询，不依赖向量检索。
 * </p>
 * <p>
 * <b>改进</b>：支持注入可选的 LLM 提取器（{@link BiFunction}），
 * 当提取器可用时优先使用 LLM 提取，否则降级到关键词匹配。
 * </p>
 */
public class EntityProfileService {

    private static final Logger log = LoggerFactory.getLogger(EntityProfileService.class);
    private static final String PROFILE_KEY_PREFIX = "user:profile:";
    private static final long PROFILE_TTL_DAYS = 90;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    // ⭐ 可选的 LLM 提取器（输入：message+reply，输出：category→value 事实）
    private BiFunction<String, String, Map<String, String>> llmExtractor;

    public EntityProfileService(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = new ObjectMapper();
    }

    /**
     * 设置 LLM 提取器（可选），用于从对话中提取用户事实。
     * <p>
     * 由注入方（如 Consumer 模块）传入，实现方式可以是 ChatClient、HTTP 调用等。
     * 不设置时自动降级到关键词匹配。
     * </p>
     *
     * @param llmExtractor 接收 (message, reply)，返回 Map&lt;category, value&gt;
     */
    public void setLlmExtractor(BiFunction<String, String, Map<String, String>> llmExtractor) {
        this.llmExtractor = llmExtractor;
    }

    /**
     * 存储一条用户事实。
     *
     * @param userId    用户 ID
     * @param category  事实类别，如 preference, fear, hobby
     * @param value     事实值，如 "狗", "川菜"
     */
    public void put(Long userId, String category, String value) {
        if (userId == null || category == null || value == null) return;
        String key = PROFILE_KEY_PREFIX + userId;
        redis.opsForHash().put(key, category, value);
        redis.expire(key, PROFILE_TTL_DAYS, TimeUnit.DAYS);
        log.debug("[Profile] 已存储: userId={}, {}={}", userId, category, value);
    }

    /**
     * 批量存储用户事实（来自 LLM 提取结果）。
     */
    public void putAll(Long userId, Map<String, String> facts) {
        if (userId == null || facts == null || facts.isEmpty()) return;
        String key = PROFILE_KEY_PREFIX + userId;
        redis.opsForHash().putAll(key, new HashMap<>(facts));
        redis.expire(key, PROFILE_TTL_DAYS, TimeUnit.DAYS);
        log.info("[Profile] 已批量存储 {} 条事实: userId={}, facts={}", facts.size(), userId, facts);
    }

    /**
     * 查询指定类别的用户事实。
     */
    public String get(Long userId, String category) {
        if (userId == null || category == null) return null;
        Object val = redis.opsForHash().get(PROFILE_KEY_PREFIX + userId, category);
        return val != null ? val.toString() : null;
    }

    /**
     * 查询用户全部画像。
     */
    public Map<String, String> getAll(Long userId) {
        if (userId == null) return Map.of();
        Map<Object, Object> entries = redis.opsForHash().entries(PROFILE_KEY_PREFIX + userId);
        Map<String, String> result = new LinkedHashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v != null ? v.toString() : ""));
        return result;
    }

    /**
     * 使用 LLM 从对话文本中提取用户事实，并自动存储。
     * <p>
     * 优先使用 LLM 提取器提取，降级到关键词匹配。
     *
     * @param userId   用户 ID
     * @param message  用户消息原文
     * @param reply    Agent 回复原文（用于上下文）
     */
    public void extractAndStore(Long userId, String message, String reply) {
        if (userId == null || message == null || message.isBlank()) return;

        try {
            Map<String, String> facts;

            // ⭐ 优先使用 LLM 提取器（更准确）
            if (llmExtractor != null) {
                facts = extractFactsWithLLM(message, reply);
            } else {
                facts = extractFactsWithKeywords(message);
            }

            if (!facts.isEmpty()) {
                putAll(userId, facts);
            }
        } catch (Exception e) {
            log.warn("[Profile] 提取失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 使用 LLM 提取器提取用户事实。
     */
    private Map<String, String> extractFactsWithLLM(String message, String reply) {
        if (llmExtractor == null) return Map.of();

        try {
            Map<String, String> facts = llmExtractor.apply(message, reply);
            return facts != null ? facts : Map.of();
        } catch (Exception e) {
            log.warn("[Profile] LLM 提取失败，降级到关键词: {}", e.getMessage());
            return extractFactsWithKeywords(message);
        }
    }

    /**
     * 关键词匹配提取用户事实（降级方案）。
     * 当 LLM 提取器不可用时使用。
     */
    public Map<String, String> extractFactsWithKeywords(String message) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (message == null) return facts;

        String msg = message.toLowerCase();

        if (msg.contains("怕") || msg.contains("害怕") || msg.contains("恐惧")) {
            extractFear(facts, message);
        }
        if (msg.contains("喜欢") || msg.contains("爱吃") || msg.contains("最爱")) {
            extractLike(facts, message);
        }
        if (msg.contains("家住") || msg.contains("住在") || msg.contains("来自")) {
            extractLocation(facts, message);
        }
        if (msg.contains("叫") && !msg.contains("不要") && !msg.contains("别叫")) {
            extractName(facts, message);
        }

        return facts;
    }

    private void extractFear(Map<String, String> facts, String msg) {
        for (String word : new String[]{"怕", "害怕", "恐惧"}) {
            int idx = msg.indexOf(word);
            if (idx >= 0) {
                String after = msg.substring(idx + word.length()).trim();
                if (!after.isEmpty()) {
                    // 取到句尾或逗号
                    int end = after.indexOf("，");
                    if (end < 0) end = after.indexOf("。");
                    if (end < 0) end = after.indexOf("！");
                    if (end < 0) end = after.indexOf("？");
                    if (end > 0) after = after.substring(0, end);
                    if (after.length() > 10) after = after.substring(0, 10);
                    facts.put("fear", after.trim());
                    return;
                }
            }
        }
    }

    private void extractLike(Map<String, String> facts, String msg) {
        for (String prefix : new String[]{"爱吃", "喜欢", "最爱"}) {
            int idx = msg.indexOf(prefix);
            if (idx >= 0) {
                String after = msg.substring(idx + prefix.length()).trim();
                if (!after.isEmpty()) {
                    int end = after.indexOf("，");
                    if (end < 0) end = after.indexOf("。");
                    if (end < 0) end = after.indexOf("和");
                    if (end > 0) after = after.substring(0, end);
                    if (after.length() > 10) after = after.substring(0, 10);
                    facts.put("preference", after.trim());
                    return;
                }
            }
        }
    }

    private void extractLocation(Map<String, String> facts, String msg) {
        for (String prefix : new String[]{"家住", "住在", "来自"}) {
            int idx = msg.indexOf(prefix);
            if (idx >= 0) {
                String after = msg.substring(idx + prefix.length()).trim();
                if (!after.isEmpty()) {
                    int end = after.indexOf("，");
                    if (end < 0) end = after.indexOf("。");
                    if (end < 0) end = after.indexOf("的");
                    if (end > 0) after = after.substring(0, end);
                    if (after.length() > 15) after = after.substring(0, 15);
                    facts.put("location", after.trim());
                    return;
                }
            }
        }
    }

    private void extractName(Map<String, String> facts, String msg) {
        int idx = msg.indexOf("叫");
        if (idx >= 0) {
            String after = msg.substring(idx + 1).trim();
            if (!after.isEmpty()) {
                int end = after.indexOf("，");
                if (end < 0) end = after.indexOf("。");
                if (end < 0) end = after.indexOf("！");
                if (end < 0) end = after.indexOf("?");
                if (end > 0) after = after.substring(0, end);
                // 名字一般 2-4 个字
                String[] parts = after.split("\\s+");
                if (parts.length > 0 && parts[0].length() >= 2 && parts[0].length() <= 8) {
                    facts.put("name", parts[0].trim());
                }
            }
        }
    }

    /**
     * 格式化用户画像为提示文本。
     */
    public String formatProfile(Long userId) {
        Map<String, String> profile = getAll(userId);
        if (profile.isEmpty()) return "";

        return profile.entrySet().stream()
                .map(e -> String.format("- %s: %s", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }
}
