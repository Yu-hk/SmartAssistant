package com.example.smartassistant.router.service.context;

import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * L5 意图漂移检测服务。
 * <p>
 * 在多轮对话中检测用户意图是否发生漂移（如从"退货"切换到"查物流"）。
 * 核心方法：计算当前问题与上一轮用户问题的 BGE 嵌入余弦相似度，
 * 低于阈值（默认 0.80）时判定为意图漂移。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class IntentDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(IntentDriftDetector.class);

    /** 漂移检测余弦相似度阈值（低于此值判定为漂移） */
    private static final double DRIFT_THRESHOLD = 0.80;

    /** 强漂移阈值（低于此值判定为强漂移，需重置上下文） */
    private static final double STRONG_DRIFT_THRESHOLD = 0.55;

    /** 最多向前追溯的意图轮数 */
    private static final int MAX_HISTORY_TURNS = 3;

    private final BgeOnnxEmbeddingService embeddingService;

    public IntentDriftDetector(BgeOnnxEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * 漂移检测结果。
     */
    public record DriftResult(
            /** 是否检测到漂移 */
            boolean driftDetected,
            /** 是否强漂移（需重置上下文） */
            boolean strongDrift,
            /** 余弦相似度分数 */
            double similarity,
            /** 上一轮用户问题 */
            String previousQuestion,
            /** 当前轮问题 */
            String currentQuestion,
            /** 漂移描述 */
            String description
    ) {}

    /**
     * 执行意图漂移检测。
     *
     * @param currentQuestion     当前用户问题
     * @param conversationHistory 对话历史（"用户：xxx" / "助手：xxx" 格式，正序）
     * @return 漂移检测结果
     */
    public DriftResult detect(String currentQuestion, List<String> conversationHistory) {
        if (currentQuestion == null || currentQuestion.isBlank()) {
            return noDrift(null, currentQuestion);
        }

        // 从对话历史中提取最近 N 轮的用户问题
        String previousQuestion = extractLastUserQuestion(conversationHistory);
        if (previousQuestion == null) {
            return noDrift(null, currentQuestion);
        }

        // 计算两轮问题的语义相似度
        double similarity = computeSimilarity(previousQuestion, currentQuestion);

        boolean driftDetected = similarity < DRIFT_THRESHOLD;
        boolean strongDrift = similarity < STRONG_DRIFT_THRESHOLD;

        if (driftDetected) {
            String driftType = strongDrift ? "强漂移" : "漂移";
            String desc = String.format(
                    "意图%s: 上一轮='%s', 当前轮='%s', 相似度=%.4f",
                    driftType,
                    truncate(previousQuestion, 30),
                    truncate(currentQuestion, 30),
                    similarity);

            log.warn("[DriftDetector] {} detected: similarity={}, prev={}, curr={}",
                    driftType,
                    String.format("%.4f", similarity),
                    truncate(previousQuestion, 50),
                    truncate(currentQuestion, 50));

            return new DriftResult(true, strongDrift, similarity, previousQuestion, currentQuestion, desc);
        }

        log.debug("[DriftDetector] 意图一致: similarity={}, prev={}, curr={}",
                String.format("%.4f", similarity),
                truncate(previousQuestion, 30),
                truncate(currentQuestion, 30));

        return new DriftResult(false, false, similarity, previousQuestion, currentQuestion, "意图一致");
    }

    /**
     * 从对话历史中提取最后一条用户问题。
     * 历史格式："用户：xxx" 或 "助手：xxx"
     */
    private String extractLastUserQuestion(List<String> history) {
        if (history == null || history.isEmpty()) return null;

        // 从后往前找，最多找 MAX_HISTORY_TURNS * 2 条（用户+助手交替）
        int maxLookup = Math.min(history.size(), MAX_HISTORY_TURNS * 2);
        for (int i = history.size() - 1; i >= history.size() - maxLookup; i--) {
            String msg = history.get(i);
            if (msg != null && msg.startsWith("用户：")) {
                return msg.substring(3).trim();
            }
        }
        return null;
    }

    /**
     * 计算两个文本的语义相似度（BGE 嵌入余弦相似度）。
     */
    private double computeSimilarity(String text1, String text2) {
        try {
            float[] vec1 = embeddingService.embed(text1);
            float[] vec2 = embeddingService.embed(text2);

            if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
                return 1.0; // 无法计算时默认无漂移
            }

            return cosineSimilarity(normalize(vec1), normalize(vec2));
        } catch (Exception e) {
            log.warn("[DriftDetector] BGE 嵌入失败: {}", e.getMessage());
            return 1.0; // 降级：不触发漂移
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = (float) (vec[i] / norm);
        return result;
    }

    private DriftResult noDrift(String previous, String current) {
        return new DriftResult(false, false, 1.0, previous, current, "无历史，不检测漂移");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
