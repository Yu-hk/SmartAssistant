package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Produces and incrementally updates an evidence-bound e-commerce user profile. */
@Service
public class LLMPreferenceExtractor {

    private static final Logger log = LoggerFactory.getLogger(LLMPreferenceExtractor.class);

    private final AiChatService aiChatService;
    private final ChatModel lightModel;
    private final PromptManager promptManager;
    private final ExecutorService extractionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${preference.extraction.timeout-ms:8000}")
    private long extractionTimeoutMs = 8000;

    public LLMPreferenceExtractor(AiChatService aiChatService,
                                  @Qualifier("lightChatModel") ChatModel lightModel,
                                  com.example.smartassistant.common.tokenizer.ChineseTokenizer ignoredTokenizer,
                                  PromptManager promptManager) {
        this.aiChatService = aiChatService;
        this.lightModel = lightModel;
        this.promptManager = promptManager;
    }

    public UserInsightReport extract(String latestUserMessage) {
        return extract("当前没有已保存画像。", latestUserMessage, latestUserMessage);
    }

    public UserInsightReport extract(String conversationHistory, String latestUserMessage) {
        return extract("当前没有已保存画像。", conversationHistory, latestUserMessage);
    }

    public UserInsightReport extract(String currentProfile, String conversationHistory,
                                     String latestUserMessage) {
        if (latestUserMessage == null || latestUserMessage.isBlank()) {
            return UserInsightReport.empty("当前没有可分析的用户消息");
        }
        String analysisInput = conversationHistory == null || conversationHistory.isBlank()
                ? latestUserMessage : conversationHistory;
        Future<UserInsightReport> extraction = extractionExecutor.submit(
                () -> extractWithLlm(currentProfile, analysisInput));
        try {
            UserInsightReport result = extraction.get(extractionTimeoutMs, TimeUnit.MILLISECONDS);
            return result != null ? result.normalized()
                    : UserInsightReport.empty("模型未返回用户画像");
        } catch (TimeoutException error) {
            extraction.cancel(true);
            log.warn("[UserInsight] model timeout after {}ms", extractionTimeoutMs);
            return UserInsightReport.empty("用户画像分析超时");
        } catch (InterruptedException error) {
            extraction.cancel(true);
            Thread.currentThread().interrupt();
            return UserInsightReport.empty("用户画像分析被中断");
        } catch (ExecutionException error) {
            log.warn("[UserInsight] model failed: {}",
                    error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
            return UserInsightReport.empty("用户画像模型调用失败");
        }
    }

    private UserInsightReport extractWithLlm(String currentProfile, String conversationHistory) {
        return aiChatService.buildChatClient(lightModel).prompt()
                .user(buildExtractionPrompt(currentProfile, conversationHistory)).call()
                .entity(UserInsightReport.class);
    }

    private String buildExtractionPrompt(String conversationHistory) {
        return buildExtractionPrompt("当前没有已保存画像。", conversationHistory);
    }

    private String buildExtractionPrompt(String currentProfile, String conversationHistory) {
        return promptManager.renderUserProfileAnalysis(currentProfile, conversationHistory);
    }

    @PreDestroy
    void shutdownExecutor() {
        extractionExecutor.shutdownNow();
    }

    public record UserInsightReport(
            ProfileUpdate profileUpdate,
            Map<String, InsightDimension> insightDimensions,
            List<RiskFinding> riskFindings,
            Map<String, Integer> radarScores,
            List<String> topDrivers,
            List<String> topBarriers,
            CommerceAssessment commerceAssessment,
            List<ConversionStrategy> conversionStrategies) {

        public UserInsightReport normalized() {
            Map<String, InsightDimension> dimensions = new LinkedHashMap<>();
            if (insightDimensions != null) {
                insightDimensions.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        dimensions.put(key.trim(), value.normalized());
                    }
                });
            }
            List<RiskFinding> risks = riskFindings == null ? List.of()
                    : riskFindings.stream().filter(java.util.Objects::nonNull)
                            .map(RiskFinding::normalized)
                            .filter(risk -> !risk.description().isBlank() && !risk.evidence().isEmpty())
                            .toList();
            Map<String, Integer> scores = normalizeScores(radarScores);
            List<ConversionStrategy> strategies = conversionStrategies == null ? List.of()
                    : conversionStrategies.stream().filter(java.util.Objects::nonNull)
                            .map(ConversionStrategy::normalized)
                            .filter(strategy -> !strategy.action().isBlank())
                            .limit(8).toList();
            return new UserInsightReport(
                    profileUpdate == null ? ProfileUpdate.keep("模型未说明画像更新动作")
                            : profileUpdate.normalized(),
                    Map.copyOf(dimensions), risks, scores,
                    cleanStrings(topDrivers, 3), cleanStrings(topBarriers, 3),
                    commerceAssessment == null ? CommerceAssessment.empty("画像评估信息不足")
                            : commerceAssessment.normalized(),
                    strategies);
        }

        public static UserInsightReport empty(String limitation) {
            return new UserInsightReport(ProfileUpdate.keep(limitation), Map.of(), List.of(),
                    Map.of(), List.of(), List.of(), CommerceAssessment.empty(limitation), List.of());
        }
    }

    public record ProfileUpdate(String action, List<String> changedFields,
                                List<String> removedFields, String reason,
                                List<String> evidence) {
        ProfileUpdate normalized() {
            String safeAction = switch (action == null ? "" : action.trim().toUpperCase()) {
                case "CREATE" -> "CREATE";
                case "UPDATE" -> "UPDATE";
                default -> "KEEP";
            };
            List<String> changed = cleanStrings(changedFields, 32);
            List<String> removed = cleanStrings(removedFields, 32);
            if ("UPDATE".equals(safeAction) && changed.isEmpty() && removed.isEmpty()) {
                safeAction = "KEEP";
            }
            if ("KEEP".equals(safeAction)) {
                changed = List.of();
                removed = List.of();
            }
            return new ProfileUpdate(safeAction, changed, removed,
                    reason == null ? "" : reason.trim(), cleanStrings(evidence, 12));
        }

        static ProfileUpdate keep(String reason) {
            return new ProfileUpdate("KEEP", List.of(), List.of(), reason, List.of());
        }
    }

    public record InsightDimension(String summary, List<String> evidence, String confidence) {
        InsightDimension normalized() {
            List<String> safeEvidence = cleanStrings(evidence, 12);
            return new InsightDimension(summary == null ? "" : summary.trim(), safeEvidence,
                    normalizeConfidence(confidence, safeEvidence.size()));
        }
    }

    public record RiskFinding(String type, String description,
                              List<String> evidence, String confidence) {
        RiskFinding normalized() {
            List<String> safeEvidence = cleanStrings(evidence, 12);
            return new RiskFinding(type == null ? "潜在误判" : type.trim(),
                    description == null ? "" : description.trim(), safeEvidence,
                    normalizeConfidence(confidence, safeEvidence.size()));
        }
    }

    public record CommerceAssessment(
            boolean reliable,
            String purchaseStage,
            String decisionStyle,
            String priceSensitivity,
            Integer purchaseIntentScore,
            String churnRisk,
            List<String> primaryConcerns,
            String bestInterventionTiming,
            boolean informationOverloadRisk,
            List<String> fatigueSignals,
            List<String> limitations) {

        CommerceAssessment normalized() {
            Integer score = purchaseIntentScore != null
                    && purchaseIntentScore >= 0 && purchaseIntentScore <= 100
                    ? purchaseIntentScore : null;
            return new CommerceAssessment(reliable,
                    safeText(purchaseStage, "信息不足"), safeText(decisionStyle, ""),
                    safeText(priceSensitivity, ""), score, safeText(churnRisk, "未知"),
                    cleanStrings(primaryConcerns, 8), safeText(bestInterventionTiming, ""),
                    informationOverloadRisk, cleanStrings(fatigueSignals, 8),
                    cleanStrings(limitations, 8));
        }

        static CommerceAssessment empty(String limitation) {
            return new CommerceAssessment(false, "信息不足", "", "", null, "未知",
                    List.of(), "", false, List.of(), List.of(limitation));
        }
    }

    public record ConversionStrategy(String painPoint, String direction, String action,
                                     String expectedEffect, String priority) {
        ConversionStrategy normalized() {
            String safePriority = "高".equals(priority) || "中".equals(priority) ? priority : "低";
            return new ConversionStrategy(safeText(painPoint, ""), safeText(direction, ""),
                    safeText(action, ""), safeText(expectedEffect, ""), safePriority);
        }
    }

    private static Map<String, Integer> normalizeScores(Map<String, Integer> rawScores) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (rawScores != null) {
            rawScores.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && value >= 1 && value <= 10) {
                    scores.put(key.trim(), value);
                }
            });
        }
        if (scores.size() > 1
                && (scores.values().stream().allMatch(score -> score > 7)
                    || scores.values().stream().allMatch(score -> score < 4))) {
            scores.clear();
        }
        return Map.copyOf(scores);
    }

    private static List<String> cleanStrings(List<String> values, int limit) {
        if (values == null) return List.of();
        return values.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(value -> !value.isBlank())
                .distinct().limit(limit).toList();
    }

    private static String normalizeConfidence(String confidence, int evidenceCount) {
        if ("高".equals(confidence)) {
            return evidenceCount >= 2 ? "高" : evidenceCount > 0 ? "中" : "低";
        }
        return "中".equals(confidence) && evidenceCount > 0 ? "中" : "低";
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
