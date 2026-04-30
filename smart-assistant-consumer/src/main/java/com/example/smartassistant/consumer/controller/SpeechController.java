package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.client.AgentStreamClient;
import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.SpeechRecognitionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 语音识别控制器
 *
 * <p>提供语音转文本的 REST API</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * # 上传音频文件进行识别
 * curl -X POST <a href="http://localhost:8081/api/speech/recognize">...</a> \
 *   -H "Authorization: Bearer YOUR_TOKEN" \
 *   -F "audio=@recording.wav" \
 *   -F "language=zh-CN"
 * </pre>
 */
@RestController
@RequestMapping("/api/speech")
@Slf4j
public class SpeechController {
    
    private final SpeechRecognitionService speechRecognitionService;
    private final RouterClient routerClient;
    private final AgentStreamClient agentStreamClient;
    
    public SpeechController(
            SpeechRecognitionService speechRecognitionService,
            RouterClient routerClient,
            AgentStreamClient agentStreamClient) {
        this.speechRecognitionService = speechRecognitionService;
        this.routerClient = routerClient;
        this.agentStreamClient = agentStreamClient;
    }
    
    /**
     * 语音识别接口
     * 
     * @param audio 音频文件
     * @param language 语言代码（可选，默认 zh-CN）
     * @return 识别结果
     */
    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SpeechRecognitionResponse> recognizeSpeech(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false, defaultValue = "zh-CN") String language) {
        
        log.info("[SpeechController] 收到语音识别请求: fileName={}, size={} bytes", 
                audio.getOriginalFilename(), audio.getSize());
        
        try {
            // 执行语音识别
            String text = speechRecognitionService.recognizeSpeech(audio, language);
            
            // 构建响应
            SpeechRecognitionResponse response = new SpeechRecognitionResponse();
            response.setSuccess(true);
            response.setText(text);
            response.setLanguage(language);
            response.setMessage("语音识别成功");
            
            log.info("[SpeechController] 识别成功: textLength={}", text != null ? text.length() : 0);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("[SpeechController] 识别失败: {}", e.getMessage(), e);
            
            SpeechRecognitionResponse response = new SpeechRecognitionResponse();
            response.setSuccess(false);
            response.setText(null);
            response.setLanguage(language);
            response.setMessage("语音识别失败: " + e.getMessage());
            
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * ⭐ 语音识别 + 智能对话（端到端）
     * 
     * <p>流程：</p>
     * <ol>
     *     <li>语音识别为文本</li>
     *     <li>调用 Router 进行意图路由</li>
     *     <li>转发到对应 Agent 处理</li>
     *     <li>返回 Agent 的响应</li>
     * </ol>
     * 
     * @param audio 音频文件
     * @param language 语言代码（可选，默认 zh-CN）
     * @param userId 用户ID（可选）
     * @param requestId 请求ID（可选，用于追踪）
     * @param response HTTP 响应（SSE 流式输出）
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void speechToChat(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false, defaultValue = "zh-CN") String language,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "requestId", required = false) String requestId,
            HttpServletResponse response) {
        
        log.info("[SpeechChat] 收到语音对话请求: fileName={}, size={} bytes", 
                audio.getOriginalFilename(), audio.getSize());
        
        try {
            // Step 1: 初始化 SSE 响应
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.flushBuffer();
            
            // Step 2: 语音识别
            sendEvent(response, "thinking", "正在识别语音...");
            String recognizedText = speechRecognitionService.recognizeSpeech(audio, language);
            
            if (recognizedText == null || recognizedText.trim().isEmpty()) {
                sendEvent(response, "error", "未识别到有效内容");
                sendEvent(response, "done", "");
                return;
            }
            
            log.info("[SpeechChat] 语音识别成功: text={}", recognizedText);
            sendEvent(response, "recognized", recognizedText);
            
            // Step 3: 生成 requestId（如果未提供）
            if (requestId == null || requestId.isEmpty()) {
                requestId = UUID.randomUUID().toString();
            }
            
            // Step 4: 获取用户ID
            if (userId == null || userId.isEmpty()) {
                userId = getUserIdFromRequest();
            }
            
            // Step 5: 触发 Router 路由决策
            sendEvent(response, "thinking", "正在分析意图...");
            
            // 异步触发 Router（不等待结果）
            routerClient.triggerRoutingDecision(recognizedText, userId, requestId);
            
            // Step 6: 等待路由决策
            sendEvent(response, "waiting", "等待路由决策...");
            Map<String, Object> decision = routerClient.waitForDecisionFromRedis(requestId, 5000);
            
            if (decision == null || !decision.containsKey("agentName")) {
                sendEvent(response, "error", "路由决策超时或失败");
                sendEvent(response, "done", "");
                return;
            }
            
            String agentName = (String) decision.get("agentName");
            log.info("[SpeechChat] 路由决策: agentName={}", agentName);
            sendEvent(response, "routed", agentName);
            
            // Step 7: 调用 Agent SSE 并转发
            if (agentStreamClient.isStreamingSupported(agentName)) {
                sendEvent(response, "error", "Agent 不支持流式响应");
                sendEvent(response, "done", "");
                return;
            }
            
            String agentUrl = agentStreamClient.getStreamUrl(agentName);
            String fullUrl = agentUrl + "?message=" + encodeUrl(recognizedText) + "&showThinking=true";
            
            log.info("[SpeechChat] 调用 Agent: url={}", fullUrl);
            forwardSSE(response, fullUrl);
            
        } catch (Exception e) {
            log.error("[SpeechChat] 处理失败: {}", e.getMessage(), e);
            try {
                sendEvent(response, "error", "处理失败: " + e.getMessage());
                sendEvent(response, "done", "");
            } catch (IOException ex) {
                log.error("[SpeechChat] 发送错误事件失败", ex);
            }
        }
    }
    
    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "speech-recognition");
        health.put("provider", "dashscope");
        return ResponseEntity.ok(health);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 发送 SSE 事件
     */
    private void sendEvent(HttpServletResponse response, String event, String data) throws IOException {
        response.getWriter().write("event: " + event + "\n");
        response.getWriter().write("data: " + data + "\n\n");
        response.getWriter().flush();
    }
    
    /**
     * URL 编码
     */
    private String encodeUrl(String text) {
        try {
            return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return text;
        }
    }
    
    /**
     * 获取用户 ID
     */
    private String getUserIdFromRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String userIdHeader = request.getHeader("X-User-Id");
                if (userIdHeader != null && !userIdHeader.isBlank()) {
                    return userIdHeader;
                }
            }
        } catch (Exception e) {
            log.debug("[SpeechChat] 获取请求上下文失败: {}", e.getMessage());
        }
        return "anonymous";
    }
    
    /**
     * 转发 SSE 流
     */
    private void forwardSSE(HttpServletResponse response, String url) {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(60000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                sendEvent(response, "error", "Agent 响应错误: " + responseCode);
                sendEvent(response, "done", "");
                return;
            }
            
            // 读取并转发 SSE 流
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                response.getWriter().write(line + "\n");
                response.getWriter().flush();
                
                // 检测结束
                if (line.contains("event: done")) {
                    break;
                }
            }
            
            reader.close();
            connection.disconnect();
            
        } catch (Exception e) {
            log.error("[SpeechChat] 转发 SSE 失败: {}", e.getMessage(), e);
            try {
                sendEvent(response, "error", "转发失败: " + e.getMessage());
                sendEvent(response, "done", "");
            } catch (IOException ex) {
                log.error("[SpeechChat] 发送错误事件失败", ex);
            }
        }
    }
    
    /**
     * 语音识别响应 DTO
     */
    @Data
    public static class SpeechRecognitionResponse {
        private boolean success;
        private String text;
        private String language;
        private String message;
    }
}
