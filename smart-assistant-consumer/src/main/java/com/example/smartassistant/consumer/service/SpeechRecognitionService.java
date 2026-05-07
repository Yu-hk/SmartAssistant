package com.example.smartassistant.consumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 语音识别服务 - 将语音转换为文本
 * 
 * <p>支持两种模式：</p>
 * <ul>
 *     <li>阿里云 DashScope 语音识别（推荐，高精度）</li>
 *     <li>本地语音识别（备用方案）</li>
 * </ul>
 * 
 * <p>使用场景：</p>
 * <ul>
 *     <li>用户通过麦克风输入语音</li>
 *     <li>上传音频文件进行识别</li>
 *     <li>实时语音对话</li>
 * </ul>
 */
@Slf4j
@Service
public class SpeechRecognitionService {
    
    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;
    
    @Value("${speech.recognition.provider:dashscope}")
    private String provider;  // dashscope | local
    
    @Value("${speech.recognition.language:zh-CN}")
    private String defaultLanguage;
    
    /**
     * 识别语音文件（主入口）
     * 
     * @param audioFile 音频文件（支持 wav, mp3, m4a, flac 等格式）
     * @param language 语言代码（可选，默认 zh-CN）
     * @return 识别后的文本
     */
    public String recognizeSpeech(MultipartFile audioFile, String language) {
        long startTime = System.currentTimeMillis();
        log.info("[SpeechRecognition] 开始语音识别: fileName={}, size={} bytes, language={}", 
                audioFile.getOriginalFilename(), audioFile.getSize(), 
                language != null ? language : defaultLanguage);
        
        try {
            // 验证文件
            validateAudioFile(audioFile);
            
            String result;
            if ("dashscope".equalsIgnoreCase(provider)) {
                result = recognizeWithDashScope(audioFile, language != null ? language : defaultLanguage);
            } else {
                result = recognizeWithLocalEngine(audioFile, language != null ? language : defaultLanguage);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[SpeechRecognition] 识别完成: textLength={}, duration={}ms", 
                    result != null ? result.length() : 0, duration);
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[SpeechRecognition] 识别失败: duration={}ms, error={}", 
                    duration, e.getMessage(), e);
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使用阿里云 DashScope 进行语音识别
     * 
     * @param audioFile 音频文件
     * @param language 语言代码
     * @return 识别文本
     */
    private String recognizeWithDashScope(MultipartFile audioFile, String language) {
        if (dashScopeApiKey == null || dashScopeApiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DashScope API Key");
        }
        
        try {
            // 将音频文件转换为 Base64
            String base64Audio = Base64.getEncoder().encodeToString(audioFile.getBytes());
            
            // 构建请求
            String url = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";
            
            Map<String, Object> requestBody = Map.of(
                "model", "paraformer-v2",
                "input", Map.of(
                    "audio", Map.of(
                        "format", getAudioFormat(audioFile),
                        "sample_rate", 16000,
                        "data", base64Audio
                    )
                ),
                "parameters", Map.of(
                    "language_hints", new String[]{getLanguageCode(language)}
                )
            );
            
            String jsonBody = convertToJson(requestBody);
            
            // 发送 HTTP 请求
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + dashScopeApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            
            HttpResponse<String> response = client.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return parseDashScopeResponse(response.body());
            } else {
                throw new RuntimeException("DashScope API 错误: " + response.statusCode() + " - " + response.body());
            }
            
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("调用 DashScope API 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使用本地引擎进行语音识别（备用方案）
     * 
     * @param audioFile 音频文件
     * @param language 语言代码
     * @return 识别文本
     */
    private String recognizeWithLocalEngine(MultipartFile audioFile, String language) {
        log.warn("[SpeechRecognition] 本地语音识别尚未实现，返回模拟文本");
        // TODO: 集成 Vosk 离线语音识别引擎
        // 推荐方案：
        // 1. pom.xml 添加 vosk:vosk-api:0.3.45
        // 2. 下载中文模型 vosk-model-small-cn-0.22 到 resources/vosk-models/
        // 3. 用 VoskRecognizer 实现识别：
        //    InputStream ais = new AudioInputStream(...);
        //    RecognitionResult result = recognizer.recognize(ais);
        // 4. 阿里云 DashScope 作为主方案已可用，本地仅作降级
        return "[本地语音识别功能开发中，请使用 DashScope]";
    }
    
    /**
     * 验证音频文件
     */
    private void validateAudioFile(MultipartFile audioFile) {
        if (audioFile.isEmpty()) {
            throw new IllegalArgumentException("音频文件不能为空");
        }
        
        if (audioFile.getSize() > 10 * 1024 * 1024) {  // 10MB 限制
            throw new IllegalArgumentException("音频文件不能超过 10MB");
        }
        
        String contentType = audioFile.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            throw new IllegalArgumentException("只支持音频文件格式");
        }
    }
    
    /**
     * 获取音频格式
     */
    private String getAudioFormat(MultipartFile audioFile) {
        String filename = audioFile.getOriginalFilename();
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
            return switch (ext) {
                case "wav" -> "wav";
                case "mp3" -> "mp3";
                case "m4a" -> "m4a";
                case "flac" -> "flac";
                case "ogg" -> "ogg";
                default -> "wav";  // 默认 wav
            };
        }
        return "wav";
    }
    
    /**
     * 转换语言代码
     */
    private String getLanguageCode(String language) {
        return switch (language.toLowerCase()) {
            case "zh-cn", "zh", "chinese" -> "zh";
            case "en-us", "en", "english" -> "en";
            case "ja-jp", "ja", "japanese" -> "ja";
            case "ko-kr", "ko", "korean" -> "ko";
            default -> "zh";  // 默认中文
        };
    }
    
    /**
     * 解析 DashScope 响应
     */
    private String parseDashScopeResponse(String responseBody) {
        // 简化解析，实际应使用 JSON 库
        int textIndex = responseBody.indexOf("\"text\":\"");
        if (textIndex == -1) {
            throw new RuntimeException("无法解析识别结果: " + responseBody);
        }
        
        int start = textIndex + 8;
        int end = responseBody.indexOf("\"", start);
        if (end == -1) {
            throw new RuntimeException("无法解析识别结果: " + responseBody);
        }
        
        return responseBody.substring(start, end);
    }
    
    /**
     * 转换为 JSON
     */
    private String convertToJson(Map<String, Object> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[SpeechRecognition] JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}
