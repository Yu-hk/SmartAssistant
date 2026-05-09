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

    private static final Set<String> NO_VALUE_AGENTS = Set.of("builtin_fallback", "none");

    /**
     * 评估对话价值
     * <p>
     * 能成功路由到 Travel/Food/General 本身就是一个价值信号，
     * 无需额外判断 intentTag。只要满足以下任一条件即沉淀：
     * <ul>
     *   <li>多轮对话(≥3轮)</li>
     *   <li>触发了工具调用</li>
     * </ul>
     * @return true = 值得沉淀为个人文档
     */
    public boolean isValuable(ConversationValueContext ctx) {
        // 一票否决：缓存命中或终极兜底
        if (ctx.fromCache) {
            log.debug("[ConvValue] 缓存命中，跳过: sessionId={}", ctx.sessionId);
            return false;
        }
        if (ctx.agentName != null && NO_VALUE_AGENTS.contains(ctx.agentName)) {
            log.debug("[ConvValue] 终极兜底或无匹配，跳过: sessionId={}, agent={}", ctx.sessionId, ctx.agentName);
            return false;
        }

        // 成功路由到专业 Agent：多轮互动或工具调用即有沉淀价值
        boolean valuable = ctx.turnCount >= 3 || ctx.hasToolCall;
        if (valuable) {
            log.info("[ConvValue] ✅ 有价值对话: sessionId={}, agent={}, turns={}, toolCall={}",
                    ctx.sessionId, ctx.agentName, ctx.turnCount, ctx.hasToolCall);
        } else {
            log.debug("[ConvValue] 评分不足(单轮闲聊): sessionId={}, turns={}", ctx.sessionId, ctx.turnCount);
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
