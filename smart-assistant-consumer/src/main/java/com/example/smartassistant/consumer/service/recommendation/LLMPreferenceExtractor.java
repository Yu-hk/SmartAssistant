package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts extensible user preferences without compiling domain types into the Consumer. */
@Service
public class LLMPreferenceExtractor {

    private static final Logger log = LoggerFactory.getLogger(LLMPreferenceExtractor.class);
    private static final Pattern POSITIVE_EXPRESSION = Pattern.compile(
            "(?:喜欢|偏好|倾向于?|经常|习惯于?)([^，。！？,;；]{1,32})");
    private static final Pattern NEGATIVE_EXPRESSION = Pattern.compile(
            "(?:不喜欢|讨厌|厌烦|厌恶|不要|拒绝|排斥)([^，。！？,;；]{1,32})");

    private final AiChatService aiChatService;
    private final ChatModel lightModel;
    private final ExecutorService extractionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${preference.extraction.timeout-ms:8000}")
    private long extractionTimeoutMs = 8000;

    public LLMPreferenceExtractor(AiChatService aiChatService,
                                  @Qualifier("lightChatModel") ChatModel lightModel,
                                  com.example.smartassistant.common.tokenizer.ChineseTokenizer ignoredTokenizer) {
        this.aiChatService = aiChatService;
        this.lightModel = lightModel;
    }

    public ExtractedPreferences extract(String question) {
        if (question == null || question.isBlank()) return ExtractedPreferences.empty();
        Future<ExtractedPreferences> extraction = extractionExecutor.submit(
                () -> extractWithLlm(question));
        try {
            ExtractedPreferences result = extraction.get(extractionTimeoutMs, TimeUnit.MILLISECONDS);
            return result != null ? result.normalized() : fallbackExtraction(question);
        } catch (TimeoutException error) {
            extraction.cancel(true);
            log.warn("[PreferenceExtraction] model timeout after {}ms; using generic fallback",
                    extractionTimeoutMs);
            return fallbackExtraction(question);
        } catch (InterruptedException error) {
            extraction.cancel(true);
            Thread.currentThread().interrupt();
            return fallbackExtraction(question);
        } catch (ExecutionException error) {
            log.warn("[PreferenceExtraction] model failed; using generic fallback: {}",
                    error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
            return fallbackExtraction(question);
        }
    }

    private ExtractedPreferences extractWithLlm(String question) {
        return aiChatService.buildChatClient(lightModel).prompt()
                .user(buildExtractionPrompt(question)).call()
                .entity(ExtractedPreferences.class);
    }

    private String buildExtractionPrompt(String question) {
        return """
                从用户文本中提取可扩展的用户偏好，只输出合法 JSON。
                不使用预设业务分类；preferenceGroups 的 key 应从文本语义概括为简短维度名，
                value 是该维度下明确表达的正向偏好。只有“喜欢、偏好、经常、习惯”等
                明确偏好表达才记录；普通查询条件不能当作长期偏好。
                negativePreferences 仅记录“不喜欢、讨厌、不要、排斥”等明确负向表达。
                purpose 使用简短场景名称，不得从固定枚举中猜测；无法判断则为 null。
                location、budget、time 未提及均为 null。

                输出结构：
                {"location":null,"purpose":null,
                 "preferenceGroups":{"动态维度名":["偏好值"]},
                 "negativePreferences":[],"budget":null,"time":null}

                用户文本：%s
                """.formatted(question);
    }

    /** Fallback extracts only explicit positive/negative spans and invents no domain type. */
    private ExtractedPreferences fallbackExtraction(String question) {
        List<String> positives = matches(question, POSITIVE_EXPRESSION);
        List<String> negatives = matches(question, NEGATIVE_EXPRESSION);
        Map<String, List<String>> groups = positives.isEmpty()
                ? Map.of() : Map.of("explicit", positives);
        return new ExtractedPreferences(null, null, groups, negatives, null, null);
    }

    private static List<String> matches(String text, Pattern pattern) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isBlank() && !values.contains(value)) values.add(value);
        }
        return List.copyOf(values);
    }

    @PreDestroy
    void shutdownExecutor() {
        extractionExecutor.shutdownNow();
    }

    public record ExtractedPreferences(
            String location,
            String purpose,
            Map<String, List<String>> preferenceGroups,
            List<String> negativePreferences,
            String budget,
            String time) {

        public ExtractedPreferences normalized() {
            Map<String, List<String>> safeGroups = new LinkedHashMap<>();
            if (preferenceGroups != null) {
                preferenceGroups.forEach((key, values) -> {
                    if (key != null && !key.isBlank() && values != null) {
                        List<String> safeValues = values.stream()
                                .filter(value -> value != null && !value.isBlank()).distinct().toList();
                        if (!safeValues.isEmpty()) safeGroups.put(key.trim(), safeValues);
                    }
                });
            }
            List<String> negatives = negativePreferences == null ? List.of()
                    : negativePreferences.stream()
                            .filter(value -> value != null && !value.isBlank()).distinct().toList();
            return new ExtractedPreferences(location, purpose, safeGroups, negatives, budget, time);
        }

        public static ExtractedPreferences empty() {
            return new ExtractedPreferences(null, null, Map.of(), List.of(), null, null);
        }
    }
}
