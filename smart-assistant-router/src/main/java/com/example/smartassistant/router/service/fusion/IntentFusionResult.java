package com.example.smartassistant.router.service.fusion;

/**
 * L3 意图融合结果。
 * <p>
 * 三路融合后的最终意图决策，包含来源路径和完整决策链。
 * </p>
 *
 * @param intentTag    最终意图标签
 * @param confidence   融合置信度
 * @param category     意图分类
 * @param source       决策来源：RULE / CLASSIFIER / LLM / FUSION / FALLBACK
 * @param ruleIntent   规则路径的意图（未命中=null）
 * @param ruleConf     规则路径的置信度
 * @param classifierIntent 小模型路径的意图（未命中=null）
 * @param classifierConf   小模型路径的置信度
 * @param llmIntent    LLM 路径的意图（未命中=null）
 * @param llmConf       LLM 路径的置信度
 * @param elapsedMs    融合总耗时
 */
public record IntentFusionResult(
        String intentTag,
        double confidence,
        String category,
        String source,
        // 三路原始结果
        String ruleIntent,
        double ruleConf,
        String classifierIntent,
        double classifierConf,
        String llmIntent,
        double llmConf,
        long elapsedMs
) {
    /** 决策是否有效（置信度足够） */
    public boolean isValid() {
        return intentTag != null && confidence >= 0.3;
    }

    /** 是否需要 LLM 兜底（全部路径低于阈值） */
    public boolean needsLLMFallback() {
        return intentTag == null || confidence < 0.3;
    }

    public static IntentFusionResult ruleHit(String intent, double conf, String category, long elapsed) {
        return new IntentFusionResult(intent, conf, category, "RULE",
                intent, conf, null, 0, null, 0, elapsed);
    }

    public static IntentFusionResult classifierHit(String intent, double conf, String category, long elapsed) {
        return new IntentFusionResult(intent, conf, category, "CLASSIFIER",
                null, 0, intent, conf, null, 0, elapsed);
    }

    public static IntentFusionResult llmHit(String intent, double conf, String category, long elapsed) {
        return new IntentFusionResult(intent, conf, category, "LLM",
                null, 0, null, 0, intent, conf, elapsed);
    }

    public static IntentFusionResult fallback(long elapsed) {
        return new IntentFusionResult(null, 0, null, "FALLBACK",
                null, 0, null, 0, null, 0, elapsed);
    }
}
