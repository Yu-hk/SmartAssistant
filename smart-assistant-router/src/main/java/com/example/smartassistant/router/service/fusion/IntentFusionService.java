package com.example.smartassistant.router.service.fusion;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.router.service.routing.KeywordFastRouteService;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;

/**
 * L3 三路并行意图融合引擎。
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
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class IntentFusionService {

    private static final Logger log = LoggerFactory.getLogger(IntentFusionService.class);

    private final KeywordFastRouteService fastRouteService;
    private final LightweightIntentClassifier classifier;
    private final TaskAnalysisService taskAnalysisService;

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
        return IntentFusionResult.fallback(elapsed);
    }

    /**
     * 将三路结果融合。
     * 规则 > 小模型 > 大模型（冲突时高优先级覆盖低）
     */
    private IntentFusionResult fuseWithLLM(
            KeywordFastRouteService.MatchResult ruleResult,
            LightweightIntentClassifier.ClassifyResult classifyResult,
            TaskAnalysisResult llmResult,
            long startTime) {

        String llmIntent = llmResult.getIntentCategory();
        double llmConf = llmResult.getConfidence();  // primitive double

        String ruleIntent = ruleResult != null ? ruleResult.getIntentTag() : null;
        double ruleConf = ruleResult != null ? ruleResult.getConfidence() : 0;
        String classifierIntent = classifyResult != null ? classifyResult.intentTag() : null;
        double classifierConf = classifyResult != null ? classifyResult.confidence() : 0;

        // 融合逻辑：规则 > 小模型 > 大模型
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

        // LLM 兜底
        long elapsed = System.currentTimeMillis() - startTime;
        return new IntentFusionResult(llmIntent, llmConf,
                llmIntent != null ? llmIntent.toLowerCase() : "unknown", "LLM",
                ruleIntent, ruleConf, classifierIntent, classifierConf,
                llmIntent, llmConf, elapsed);
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
