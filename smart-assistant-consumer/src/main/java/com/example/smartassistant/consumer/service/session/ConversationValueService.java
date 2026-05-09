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

    private static final Set<String> HIGH_VALUE_INTENTS = Set.of(
            "PREFERENCE", "PLAN", "DECISION", "RECOMMEND",
            // ⭐ 中文意图标签（Router 生成的实际值）
            "旅游规划", "美食推荐", "天气查询", "图片生成", "绘画请求",
            "搜索", "计算", "热门新闻", "出行规划", "景点推荐"
    );

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

        // ⭐ 正向评分
        // - intentTag 匹配高价值意图 +2
        // - 触发了工具调用 +1
        // - 多轮对话(≥3轮) +1
        // 阈值3→2：允许 3轮对话+工具调用(2分) 或 单次高价值意图(2分) 沉淀
        int score = 0;
        if (ctx.intentTag != null && HIGH_VALUE_INTENTS.contains(ctx.intentTag)) score += 2;
        if (ctx.hasToolCall) score += 1;
        if (ctx.turnCount >= 3) score += 1;

        boolean valuable = score >= 2;
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
