package com.example.smartassistant.router.service.fusion;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.router.service.monitoring.NewMetricsCollector;
import com.example.smartassistant.router.service.routing.KeywordFastRouteService;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * L3 三路并行意图融合引擎（含 Intent Prior Bias）。
 * <p>
 * 核心逻辑（文章推荐策略）：
 * <ol>
 *   <li><b>规则命中 ≥ 0.9</b> → 直接输出（~7μs）</li>
 *   <li><b>小模型 ≥ 0.7</b>   → 优先输出（~10ms）</li>
 *   <li><b>LLM ≥ 0.6</b>      → LLM 输出（~200ms）</li>
 *   <li><b>多路冲突</b>      → 规则 > 小模型 > 大模型</li>
 *   <li><b>全部 &lt; 0.3</b>   → 兜底反问</li>
 * </ol>
 * </p>
 *
 * <p>⭐ Intent Prior Bias（2026-07-03）：
 * 给 ORDER/PRODUCT 等业务意图增加先验偏置，抑制 GENERAL 兜底意图的排序偏置。
 * 参考文章《意图识别系统：从分类模型到检索式路由的演进》的 Intent Prior Bias 方案。
 * 偏置值在语义分数差距大时不会翻转结果，仅在边界 case 推动正确意图胜出。
 * </p>
 */
@Service
public class IntentFusionService {

    private static final Logger log = LoggerFactory.getLogger(IntentFusionService.class);

    private final KeywordFastRouteService fastRouteService;
    private final LightweightIntentClassifier classifier;
    private final TaskAnalysisService taskAnalysisService;

    @Autowired(required = false)
    private NewMetricsCollector metricsCollector;

    /**
     * ⭐ Intent Prior Bias：意图先验偏置。
     * <p>
     * 在语义分数中加入轻微偏置，抑制 GENERAL 兜底意图的排序偏置。
     * ORDER/PRODUCT 等业务意图增加 0.15 偏置，COMPLEX 增加 0.05。
     * 当语义分数差距大时（如 GENERAL=0.85 vs ORDER=0.55），偏置 0.15 不会翻转结果。
     * 仅在边界 case（如 ORDER=0.72 vs GENERAL=0.70）时推动正确意图胜出。
     * </p>
     */
    private final Map<String, Double> intentPriorBias = Map.of(
            "ORDER", 0.15,
            "PRODUCT", 0.15,
            "COMPLEX", 0.05
            // GENERAL 和 UNKNOWN 不加偏置（基准）
    );

    public IntentFusionService(KeywordFastRouteService fastRouteService,
                               LightweightIntentClassifier classifier,
                               TaskAnalysisService taskAnalysisService) {
        this.fastRouteService = fastRouteService;
        this.classifier = classifier;
        this.taskAnalysisService = taskAnalysisService;
    }

    /**
     * 执行三路并行意图融合。
     * <p>
     * 规则和小模型并行执行，LLM 仅在需要时触发（前两者都低于阈值）。
     * </p>
     */
    public IntentFusionResult fuse(String question, List<String> conversationHistory) {
        long start = System.currentTimeMillis();

        // ===== 路 1: 规则匹配 =====
        KeywordFastRouteService.MatchResult ruleResult = fastRouteService.match(question);
        if (ruleResult != null && ruleResult.getConfidence() >= 0.9) {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[IntentFusion] ⚡ 规则命中: intent={}, conf={}, elapsed={}ms",
                    ruleResult.getIntentTag(), ruleResult.getConfidence(), elapsed);
            if (metricsCollector != null) metricsCollector.recordFusion("RULE");
            return IntentFusionResult.ruleHit(
                    ruleResult.getIntentTag(), ruleResult.getConfidence(),
                    mapAgentToCategory(ruleResult.getTargetAgent()), elapsed);
        }

        // ===== 路 2: 小模型分类器（与 LLM 并行） =====
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<LightweightIntentClassifier.ClassifyResult> classifierFuture =
                executor.submit(() -> classifier.classify(question));

        LightweightIntentClassifier.ClassifyResult classifyResult = null;
        try {
            classifyResult = classifierFuture.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[IntentFusion] ⏱ 小模型超时(100ms), 跳过");
        } catch (Exception e) {
            log.warn("[IntentFusion] 小模型失败: {}", e.getMessage());
        }

        if (classifyResult != null && classifyResult.confidence() >= 0.7) {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[IntentFusion] 🔬 小模型命中: intent={}, conf={}, elapsed={}ms",
                    classifyResult.intentTag(), classifyResult.confidence(), elapsed);
            if (metricsCollector != null) metricsCollector.recordFusion("CLASSIFIER");
            return IntentFusionResult.classifierHit(
                    classifyResult.intentTag(), classifyResult.confidence(),
                    classifyResult.category(), elapsed);
        }

        // ===== 路 3: LLM 兜底（仅当前两者都不可靠时） =====
        TaskAnalysisResult llmResult = taskAnalysisService.analyze(question, conversationHistory);

        if (llmResult != null && llmResult.isMeaningful()) {
            String llmIntent = llmResult.getIntentCategory();
            double llmConf = llmResult.getConfidence();  // primitive double, default 1.0

            // 融合决策
            IntentFusionResult fusionResult = fuseWithLLM(
                    ruleResult, classifyResult, llmResult, start);
            return fusionResult;
        }

        // ===== 全部失败 =====
        long elapsed = System.currentTimeMillis() - start;
        log.warn("[IntentFusion] ❌ 全部路径未命中: elapsed={}ms", elapsed);
        if (metricsCollector != null) metricsCollector.recordFusion("FALLBACK");
        return IntentFusionResult.fallback(elapsed);
    }

    /**
     * 将三路结果融合。
     * 规则 > 小模型 > 大模型（冲突时高优先级覆盖低）
     * <p>
     * ⭐ Intent Prior Bias：在比较置信度时，为 ORDER/PRODUCT 等业务意图增加先验偏置。
     * </p>
     */
    private IntentFusionResult fuseWithLLM(
            KeywordFastRouteService.MatchResult ruleResult,
            LightweightIntentClassifier.ClassifyResult classifyResult,
            TaskAnalysisResult llmResult,
            long startTime) {

        String llmIntent = llmResult.getIntentCategory();
        double llmConf = applyBias(llmIntent, llmResult.getConfidence());

        String ruleIntent = ruleResult != null ? ruleResult.getIntentTag() : null;
        double ruleConf = applyBias(ruleIntent, ruleResult != null ? ruleResult.getConfidence() : 0);
        String classifierIntent = classifyResult != null ? classifyResult.intentTag() : null;
        double classifierConf = applyBias(classifierIntent, classifyResult != null ? classifyResult.confidence() : 0);

        // 融合逻辑：规则 > 小模型 > 大模型（置信度已含 Intent Prior Bias）
        if (ruleResult != null && ruleConf >= 0.6) {
            // 规则命中且置信度足够，优先采用
            long elapsed = System.currentTimeMillis() - startTime;
            return new IntentFusionResult(ruleIntent, ruleConf,
                    mapAgentToCategory(ruleResult.getTargetAgent()), "FUSION_RULE",
                    ruleIntent, ruleConf, classifierIntent, classifierConf,
                    llmIntent, llmConf, elapsed);
        }

        if (classifyResult != null && classifierConf >= 0.5) {
            // 小模型命中且置信度足够
            long elapsed = System.currentTimeMillis() - startTime;
            return new IntentFusionResult(classifierIntent, classifierConf,
                    classifyResult.category(), "FUSION_CLASSIFIER",
                    ruleIntent, ruleConf, classifierIntent, classifierConf,
                    llmIntent, llmConf, elapsed);
        }

        // LLM 兜底（置信度已含 Intent Prior Bias）
        long elapsed = System.currentTimeMillis() - startTime;
        return new IntentFusionResult(llmIntent, llmConf,
                llmIntent != null ? llmIntent.toLowerCase() : "unknown", "LLM",
                ruleIntent, ruleConf, classifierIntent, classifierConf,
                llmIntent, llmConf, elapsed);
    }

    /**
     * ⭐ 应用 Intent Prior Bias：为业务意图增加先验偏置。
     * <p>
     * 当 intent 为空或不在偏置映射中时，返回原始分数。
     * 偏置仅在语义分数边界 case 时翻转结果，不会压倒强信号。
     * </p>
     *
     * @param intentTag 意图标签（如 "ORDER"、"PRODUCT"、"GENERAL"）
     * @param score     原始语义分数
     * @return 加上先验偏置后的分数
     */
    private double applyBias(String intentTag, double score) {
        if (intentTag == null) return score;
        Double bias = intentPriorBias.get(intentTag);
        if (bias == null) return score;
        return score + bias;
    }

    // ==================== 工具方法 ====================

    /** 将 agent 名称映射到 intent category */
    private String mapAgentToCategory(String agentName) {
        if (agentName == null) return "unknown";
        return switch (agentName) {
            case "order" -> "ORDER";
            case "product" -> "PRODUCT";
            case "general" -> "GENERAL";
            default -> agentName.toUpperCase();
        };
    }
}
