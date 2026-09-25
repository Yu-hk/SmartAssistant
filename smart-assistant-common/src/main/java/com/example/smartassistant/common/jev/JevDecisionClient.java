package com.example.smartassistant.common.jev;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Optional, bounded TypeSafe/Jev observation. A decision is never business authorization. */
@Component
public class JevDecisionClient {
    private static final Logger log = LoggerFactory.getLogger(JevDecisionClient.class);
    private static final URI ENDPOINT = URI.create("https://api.typesafe.ai/v1/systemone");
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ORDER_ID = Pattern.compile("(?i)\\bORD-[A-Z0-9-]+\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{8,20}(?!\\d)");
    private static final Pattern UUID_PATTERN = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private final boolean enabled;

    @Autowired
    public JevDecisionClient(ObjectMapper mapper,
            @Value("${jev.enabled:false}") boolean enabled,
            @Value("${jev.api-key:${TYPESAFE_API_KEY:}}") String apiKey,
            @Value("${jev.model:jev-latest}") String model,
            @Value("${jev.timeout-ms:600}") int timeoutMs,
            @Value("${jev.proxy-url:${JEV_PROXY_URL:}}") String proxyUrl) {
        this(mapper, enabled, apiKey, model, timeoutMs, ENDPOINT, proxyUrl);
    }

    /** Explicit endpoint is for isolated tests; production always uses the official endpoint. */
    public JevDecisionClient(ObjectMapper mapper, boolean enabled, String apiKey,
                             String model, int timeoutMs, URI endpoint) {
        this(mapper, enabled, apiKey, model, timeoutMs, endpoint, "");
    }

    JevDecisionClient(ObjectMapper mapper, boolean enabled, String apiKey,
                      String model, int timeoutMs, URI endpoint, String proxyUrl) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? "jev-latest" : model.strip();
        this.timeoutMs = Math.max(100, Math.min(2500, timeoutMs));
        this.endpoint = endpoint;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(this.timeoutMs, 500)));
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            URI proxy = URI.create(proxyUrl);
            if (proxy.getHost() == null || proxy.getPort() < 1)
                throw new IllegalArgumentException("Invalid Jev proxy URL");
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        this.http = builder.build();
    }

    public boolean available() {
        return enabled && !apiKey.isBlank();
    }

    public Optional<Decision> evaluate(String state, Map<String, Object> questions, String requestId) {
        if (!available() || questions == null || questions.isEmpty()) return Optional.empty();
        String safeState = minimize(state);
        if (safeState.isBlank()) return Optional.empty();
        boolean attempted = false;
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "state", safeState, "model", model, "questions", questions));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            attempted = true;
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                TokenUsageCache.markIncomplete(requestId);
                log.warn("[Jev] Decision unavailable: HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode result = mapper.readTree(response.body());
            if (!result.path("answers").isObject()) {
                TokenUsageCache.markIncomplete(requestId);
                return Optional.empty();
            }
            recordUsage(requestId, result.path("usage"));
            return Optional.of(new Decision(result.path("answers")));
        } catch (InterruptedException interrupted) {
            if (attempted) TokenUsageCache.markIncomplete(requestId);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception unavailable) {
            if (attempted) TokenUsageCache.markIncomplete(requestId);
            // Never log the request body, credentials, response body or provider exception message.
            log.warn("[Jev] Decision unavailable: {}", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String minimize(String state) {
        if (state == null || state.isBlank() || state.length() > 1500) return "";
        String redacted = EMAIL.matcher(state).replaceAll("[email]");
        redacted = PHONE.matcher(redacted).replaceAll("[phone]");
        redacted = ORDER_ID.matcher(redacted).replaceAll("[order]");
        redacted = UUID_PATTERN.matcher(redacted).replaceAll("[id]");
        return LONG_NUMBER.matcher(redacted).replaceAll("[id]").strip();
    }

    private static void recordUsage(String requestId, JsonNode usage) {
        if (requestId == null || requestId.isBlank()) return;
        JsonNode input = usage.path("input_tokens");
        JsonNode output = usage.path("output_tokens");
        if (!input.canConvertToLong() || !output.canConvertToLong()
                || input.longValue() < 0 || output.longValue() < 0) {
            TokenUsageCache.markIncomplete(requestId);
            return;
        }
        long total;
        try { total = Math.addExact(input.longValue(), output.longValue()); }
        catch (ArithmeticException overflow) { total = Long.MAX_VALUE; }
        TokenUsageCache.recordPartial(requestId, "jev:" + UUID.randomUUID(),
                input.longValue(), output.longValue(), total);
    }

    public record Decision(JsonNode answers) {
        public String choice(String question) {
            return answers.path(question).path("choice").asText("");
        }
        public double confidence(String question) {
            return number(question, "confidence");
        }
        public double score(String question) {
            return number(question, "score");
        }
        public double noul(String question) {
            return number(question, "noul");
        }
        private double number(String question, String field) {
            JsonNode value = answers.path(question).path(field);
            return value.isNumber() ? value.doubleValue() : Double.NaN;
        }
    }
}
