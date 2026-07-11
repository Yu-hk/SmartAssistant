package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;

/**
 * 缓存回复格式化器
 * <p>
 * 负责缓存命中后的回复包装：前缀变化、LLM 改写、strip 旧前缀、动态 TTL。
 * 从 {@link SemanticRouteCacheService} 拆分，降低主类复杂度。
 */
public class ReplyFormatter {

    private static final Logger log = LoggerFactory.getLogger(ReplyFormatter.class);

    private final ChatClient chatClient;
    private final Random random = new Random();
    private final AgentDiscoveryService agentDiscoveryService;

    // ⭐ 关键词缓存专用停用词
    static final Set<String> KEYWORD_STOP_WORDS = Set.of(
            "怎么样", "如何", "怎么", "什么", "为啥", "为何",
            "请问", "能", "可以", "需要", "想要", "想去",
            "吗", "呢", "吧", "啊", "哦", "么",
            "这个", "那个", "哪些", "这些", "那些",
            "有没有", "是不是", "会不会", "能不能", "要不要"
    );

    public ReplyFormatter(ChatClient chatClient, AgentDiscoveryService agentDiscoveryService) {
        this.chatClient = chatClient;
        this.agentDiscoveryService = agentDiscoveryService;
    }

    public String wrapCachedReply(String reply, SemanticRouteCacheService.CachedRouteDecision cached,
                                   String userQuestion, Long userId) {
        return wrapCachedReply(reply, cached, userQuestion, userId, false);
    }

    public String wrapCachedReply(String reply, SemanticRouteCacheService.CachedRouteDecision cached,
                                   String userQuestion, Long userId, boolean isNewPhrasing) {
        if (reply == null || reply.isBlank()) return reply;
        reply = stripPrefixes(reply);

        boolean sameUser = userId != null && userId.equals(cached.firstUserId);
        boolean exactPhrasing = userQuestion != null && userQuestion.equals(cached.originalQuestion);
        long elapsed = System.currentTimeMillis() - (cached.firstCachedAt > 0 ? cached.firstCachedAt : cached.cachedAt);
        long elapsedHours = elapsed / 3600000;

        // 前缀匹配
        if (cached._isPrefixMatch && userQuestion != null && !userQuestion.equals(cached.originalQuestion)) {
            if (cached._intentMismatch) {
                String extra = cached.originalQuestion + "方面的信息如上所述。"
                        + "关于你提到的其他内容，你可以试试这样问我：";
                log.info("[SemanticCache] prefix intent mismatch, append suggestion");
                return stripPrefixes(reply) + "\n\n---\n" + extra;
            }
            if (sameUser) {
                String adapted = rewriteForPrefixMatch(reply, cached.originalQuestion, userQuestion);
                if (adapted != null && !adapted.isBlank()) {
                    log.info("[SemanticCache] prefix match rewrite: question={}", userQuestion);
                    return adapted;
                }
            }
        }

        // Phase 2: 语义相同但表述不同的新问题 → LLM 改写适配（不区分用户）
        // 后续相同表述通过精确匹配命中 → 走前缀变化，零 LLM 成本
        if (isNewPhrasing && !exactPhrasing && userQuestion != null) {
            String adapted = rewriteForPrefixMatch(reply, cached.originalQuestion, userQuestion);
            if (adapted != null && !adapted.isBlank()) {
                log.info("[SemanticCache] Phrase-adapt rewrite: question={}", userQuestion);
                return adapted;
            }
        }

        String prefix;
        if (cached.hitCount >= 3) {
            List<String> tips;
            if (sameUser && exactPhrasing) {
                tips = Arrays.asList(
                        "（以下是我之前查到的信息，可能已有变化哦）",
                        "（这条信息是之前查询的，建议重新问一下获取最新数据）",
                        "（按上次查询的结果来看，可能会有些出入～）");
            } else {
                tips = Arrays.asList(
                        "（以下是根据历史查询获取的信息）",
                        "（这条信息是此前查询得到的）",
                        "（以上信息来源于此前缓存的数据）");
            }
            prefix = tips.get(random.nextInt(tips.size())) + "\n\n";
        } else if (elapsedHours > 6) {
            prefix = (sameUser && exactPhrasing)
                    ? "根据" + (elapsedHours > 24 ? "之前" : elapsedHours + "小时前") + "查询的信息：\n\n"
                    : "以下信息来源于" + (elapsedHours > 24 ? "之前的" : elapsedHours + "小时前的") + "数据：\n\n";
        } else if (cached.hitCount == 2) {
            List<String> msgs;
            if (sameUser && exactPhrasing) {
                msgs = Arrays.asList(
                        "再帮你查一次，结果和之前一样～\n\n",
                        "跟上次查询的结果一致：\n\n",
                        "还是同样的结果：\n\n");
            } else {
                msgs = Arrays.asList(
                        "查询结果如下：\n\n",
                        "以下是相关信息：\n\n",
                        "这是查询到的结果：\n\n");
            }
            prefix = msgs.get(random.nextInt(msgs.size()));
        } else {
            prefix = "";
        }
        return prefix + reply;
    }

    String stripPrefixes(String reply) {
        if (reply == null) return null;
        String[] knownPrefixes = {
                "再帮你查一次，结果和之前一样～\n\n", "跟上次查询的结果一致：\n\n",
                "还是同样的结果：\n\n",
                "（以下是我之前查到的信息，可能已有变化哦）\n\n",
                "（这条信息是之前查询的，建议重新问一下获取最新数据）\n\n",
                "（按上次查询的结果来看，可能会有些出入～）\n\n",
                "（以下是根据历史查询获取的信息）\n\n",
                "（这条信息是此前查询得到的）\n\n",
                "（以上信息来源于此前缓存的数据）\n\n",
                "查询结果如下：\n\n", "以下是相关信息：\n\n", "这是查询到的结果：\n\n"
        };
        for (String p : knownPrefixes) {
            while (reply.startsWith(p)) reply = reply.substring(p.length()).trim();
        }
        return reply.replaceAll("^\\d+\\.\\s*[^\\n]+\\n\\n", "");
    }

    private String rewriteForPrefixMatch(String cachedReply, String cachedQuestion, String userQuestion) {
        try {
            String result = chatClient.prompt()
                    .user("以下是一条已有的" + cachedQuestion + "回复。用户现在问的是：\"" + userQuestion + "\"。"
                            + "请基于已有回复中的事实数据，回答用户的完整问题。"
                            + "保持所有事实信息（温度、地点、数字）不变，直接输出回答：\n" + cachedReply)
                    .call().content();
            return result != null ? result.trim() : null;
        } catch (Exception e) {
            log.warn("[SemanticCache] prefix match rewrite failed: {}", e.getMessage());
            return null;
        }
    }

    long getTtlForReply(String agentName, String originalQuestion) {
        if (agentName != null && agentDiscoveryService != null) {
            Long agentTtl = agentDiscoveryService.getAgentTtl(agentName);
            if (agentTtl != null && agentTtl > 0) return agentTtl;
        }
        if (agentName == null) return 3600;
        String lower = agentName.toLowerCase();
        if (lower.contains("builtin") || lower.contains("fallback")) return 7200;
        return 3600;
    }
}
