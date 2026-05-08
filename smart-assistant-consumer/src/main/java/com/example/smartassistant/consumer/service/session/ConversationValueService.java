package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 对话价值评估服务
 * 判断一段对话是否值得沉淀为用户个人文档
 */
@Service
public class ConversationValueService {

    private static final Logger log = LoggerFactory.getLogger(ConversationValueService.class);

    private static final Set<String> HIGH_VALUE_INTENTS = Set.of("PREFERENCE", "PLAN", "DECISION", "RECOMMEND");

    private static final Set<String> NO_VALUE_AGENTS = Set.of("builtin_fallback", "none");

    /**
     * 评估对话价值
     * @return true = 值得沉淀为个人文档
     */
    public boolean isValuable(ConversationValueContext ctx) {
        // 排除条件（一票否决）
        if (ctx.fromCache) {
            log.debug("[ConvValue] 缓存命中，跳过: sessionId={}", ctx.sessionId);
            return false;
        }
        if (ctx.agentName != null && NO_VALUE_AGENTS.contains(ctx.agentName)) {
            log.debug("[ConvValue] 终极兜底或无匹配，跳过: sessionId={}, agent={}", ctx.sessionId, ctx.agentName);
            return false;
        }

        // 正向评分：agent 不再参与评分（travel/food/general 都不加分），
        // 避免 general 因无工具调用而被排除
        int score = 0;
        if (ctx.intentTag != null && HIGH_VALUE_INTENTS.contains(ctx.intentTag.toUpperCase())) score += 2;
        if (ctx.hasToolCall) score += 1;
        if (ctx.turnCount >= 3) score += 1;

        boolean valuable = score >= 3;
        if (valuable) {
            log.info("[ConvValue] ✅ 有价值对话: sessionId={}, score={}, agent={}, intent={}",
                    ctx.sessionId, score, ctx.agentName, ctx.intentTag);
        } else {
            log.debug("[ConvValue] 评分不足: sessionId={}, score={}", ctx.sessionId, score);
        }
        return valuable;
    }

    /**
     * 对话价值评估上下文
     */
    public record ConversationValueContext(
            Long userId,
            String sessionId,
            String content,
            String agentName,
            String intentTag,
            int turnCount,
            boolean fromCache,
            boolean hasToolCall
    ) {}
}
