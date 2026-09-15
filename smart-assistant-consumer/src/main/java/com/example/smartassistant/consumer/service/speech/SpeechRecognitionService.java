package com.example.smartassistant.consumer.service.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Dedicated ASR boundary: no agent execution, audio persistence, or transcript logging. */
@Service
public class SpeechRecognitionService {
    public static final int MAX_SECONDS = 60;
    public static final int MAX_BYTES = 44 + 16000 * 2 * MAX_SECONDS;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final Semaphore capacity = new Semaphore(4);
    private final java.util.Set<Long> active = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastRequest = new LinkedHashMap<>();

    @Autowired
    public SpeechRecognitionService(@Value("${speech.enabled:false}") boolean enabled,
            @Value("${speech.api-key:}") String apiKey,
            @Value("${speech.model:qwen3-asr-flash}") String model,
            @Value("${speech.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl) {
        this(enabled, apiKey, model, createClient(baseUrl));
    }

    SpeechRecognitionService(boolean enabled, String apiKey, String model, RestClient client) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.client = client;
    }

    private static RestClient createClient(String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("speech.base-url must be an HTTPS API base URL");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(45000);
        return RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory).build();
    }

    public boolean available() { return enabled && apiKey != null && !apiKey.isBlank(); }

    public record Usage(Long promptTokens, Long completionTokens, Long totalTokens) {}
    public record Transcript(String text, String model, double durationSeconds, Usage usage) {}

    public Transcript transcribe(long userId, byte[] wav) {
        if (!available()) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "语音识别暂未启用，请使用文字输入");
        double duration = validateWav(wav);
        if (!capacity.tryAcquire()) throw failure(HttpStatus.TOO_MANY_REQUESTS, "语音识别繁忙，请稍后再试");
        if (!active.add(userId)) {
            capacity.release();
            throw failure(HttpStatus.TOO_MANY_REQUESTS, "已有语音正在识别，请等待完成");
        }
        try {
            checkRate(userId);
            String request = JSON.writeValueAsString(Map.of("model", model, "stream", false,
                    "messages", List.of(Map.of("role", "user", "content", List.of(Map.of(
                            "type", "input_audio", "input_audio", Map.of("data",
                                    "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)))))),
                    "asr_options", Map.of("enable_itn", true)));
            return client.post().uri("/chat/completions").header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(request).exchange((req, res) -> {
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            throw failure(res.getStatusCode().value() == 429 ? HttpStatus.TOO_MANY_REQUESTS
                                    : HttpStatus.BAD_GATEWAY, "语音模型暂不可用，请稍后重试或使用文字输入");
                        }
                        byte[] bytes = res.getBody().readNBytes(65537);
                        if (bytes.length > 65536) throw failure(HttpStatus.BAD_GATEWAY, "语音识别结果异常，请重新录音");
                        JsonNode root;
                        try { root = JSON.readTree(bytes); }
                        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw failure(HttpStatus.BAD_GATEWAY, "语音识别结果异常，请重新录音");
                        }
                        JsonNode choice = root == null ? null : root.path("choices").path(0);
                        if (choice == null || !"stop".equals(choice.path("finish_reason").asText())) {
                            throw failure(HttpStatus.BAD_GATEWAY, "语音识别未完整完成，请重新录音");
                        }
                        JsonNode content = choice.path("message").path("content");
                        if (!content.isTextual()) throw failure(HttpStatus.BAD_GATEWAY, "语音识别结果异常，请重新录音");
                        String text = content.asText().strip();
                        if (text.isEmpty()) throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "未识别到有效语音，请靠近麦克风重试");
                        if (text.length() > 4000) throw failure(HttpStatus.BAD_GATEWAY, "识别文字过长，请分段录音");
                        JsonNode usage = root.path("usage");
                        return new Transcript(text, model, duration, new Usage(token(usage, "prompt_tokens"),
                                token(usage, "completion_tokens"), token(usage, "total_tokens")));
                    });
        } catch (ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw failure(HttpStatus.GATEWAY_TIMEOUT, "语音识别连接失败或超时，请重试或使用文字输入");
        } catch (Exception e) {
            // Never return/log provider bodies, authorization headers, or base64 audio.
            throw failure(HttpStatus.BAD_GATEWAY, "语音识别失败，请重新录音或使用文字输入");
        } finally {
            active.remove(userId);
            capacity.release();
        }
    }

    private synchronized void checkRate(long userId) {
        long now = System.currentTimeMillis();
        lastRequest.entrySet().removeIf(entry -> now - entry.getValue() >= 10000);
        if (lastRequest.containsKey(userId) || lastRequest.size() >= 10000) {
            throw failure(HttpStatus.TOO_MANY_REQUESTS, "语音请求过于频繁，请在 10 秒后重试");
        }
        lastRequest.put(userId, now);
    }

    private static Long token(JsonNode usage, String name) {
        JsonNode value = usage.path(name);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }

    /** Only accept the canonical 16 kHz mono PCM16 WAV emitted by our recorder. */
    public static double validateWav(byte[] wav) {
        if (wav.length > MAX_BYTES) throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "录音不能超过 60 秒");
        if (wav.length < 44) throw failure(HttpStatus.BAD_REQUEST, "录音文件无效");
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157 || b.getInt(12) != 0x20746d66
                || b.getInt(16) != 16 || b.getShort(20) != 1 || b.getShort(22) != 1
                || b.getInt(24) != 16000 || b.getInt(28) != 32000 || b.getShort(32) != 2
                || b.getShort(34) != 16 || b.getInt(36) != 0x61746164
                || b.getInt(4) != wav.length - 8 || b.getInt(40) != wav.length - 44
                || (wav.length - 44) % 2 != 0) {
            throw failure(HttpStatus.BAD_REQUEST, "仅支持 16kHz 单声道 PCM16 WAV 录音");
        }
        double duration = (wav.length - 44) / 32000.0;
        if (duration < .3) throw failure(HttpStatus.BAD_REQUEST, "录音太短，请至少说一句话");
        boolean audible = false;
        for (int i = 44; i < wav.length; i += 2) {
            if (Math.abs((int) b.getShort(i)) > 32) { audible = true; break; }
        }
        if (!audible) throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "录音中未检测到声音，请检查麦克风");
        return duration;
    }

    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
