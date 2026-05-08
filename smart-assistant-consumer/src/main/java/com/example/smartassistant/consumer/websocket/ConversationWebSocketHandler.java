package com.example.smartassistant.consumer.websocket;

import com.example.smartassistant.consumer.service.core.MathConsumerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话 WebSocket 处理器
 * 支持实时双向通信
 */
@Component
public class ConversationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationWebSocketHandler.class);

    private final MathConsumerService mathConsumerService;
    private final ObjectMapper objectMapper;

    // 存储活跃的 WebSocket 会话：sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public ConversationWebSocketHandler(MathConsumerService mathConsumerService) {
        this.mathConsumerService = mathConsumerService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * WebSocket 连接建立时调用
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("[WebSocket] 新连接建立: {}", session.getId());
        
        // 发送欢迎消息
        sendTextMessage(session, Map.of(
            "type", "connected",
            "message", "WebSocket 连接成功",
            "sessionId", session.getId()
        ));
    }

    /**
     * 接收到文本消息时调用
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.info("[WebSocket] 收到消息: sessionId={}, payload={}", session.getId(), payload);

        try {
            // 解析消息
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(payload, Map.class);

            String type = (String) request.get("type");
            
            switch (type) {
                case "chat":
                    handleChatMessage(session, request);
                    break;
                case "create_session":
                    handleCreateSession(session, request);
                    break;
                default:
                    sendErrorMessage(session, "未知的消息类型: " + type);
            }

        } catch (Exception e) {
            log.error("[WebSocket] 处理消息失败: {}", e.getMessage(), e);
            sendErrorMessage(session, "处理消息失败: " + e.getMessage());
        }
    }

    /**
     * 处理聊天消息
     */
    private void handleChatMessage(WebSocketSession session, Map<String, Object> request) throws Exception {
        String sessionId = (String) request.get("sessionId");
        Long userId = ((Number) request.get("userId")).longValue();
        String message = (String) request.get("message");

        if (sessionId == null || message == null) {
            sendErrorMessage(session, "缺少必要参数: sessionId, userId, message");
            return;
        }

        // 注册会话
        activeSessions.put(sessionId, session);

        // 发送处理中状态
        sendTextMessage(session, Map.of(
            "type", "processing",
            "sessionId", sessionId,
            "message", "正在处理..."
        ));

        // 调用 MathConsumerService 处理对话(包含完整的路由和 Agent 调用)
        long startTime = System.currentTimeMillis();
        String aiResponse = mathConsumerService.calculate(message);
        long duration = System.currentTimeMillis() - startTime;

        // 发送结果
        sendTextMessage(session, Map.of(
            "type", "chat_response",
            "sessionId", sessionId,
            "reply", aiResponse,
            "duration_ms", duration
        ));

        log.info("[WebSocket] 聊天消息处理完成: sessionId={}, duration={}ms", sessionId, duration);
    }

    /**
     * 处理创建会话请求
     */
    private void handleCreateSession(WebSocketSession session, Map<String, Object> request) throws Exception {
        String userId = (String) request.get("userId");

        if (userId == null) {
            sendErrorMessage(session, "缺少 userId 参数");
            return;
        }

        // 生成会话 ID
        String sessionId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        sendTextMessage(session, Map.of(
            "type", "session_created",
            "sessionId", sessionId,
            "userId", userId,
            "message", "会话创建成功"
        ));

        log.info("[WebSocket] 会话创建成功: sessionId={}, userId={}", sessionId, userId);
    }

    /**
     * WebSocket 连接关闭时调用
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, @NotNull CloseStatus status) throws Exception {
        log.info("[WebSocket] 连接关闭: sessionId={}, status={}", session.getId(), status);
        
        // 从活跃会话中移除
        activeSessions.values().removeIf(s -> s.getId().equals(session.getId()));
    }

    /**
     * 处理传输错误
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("[WebSocket] 传输错误: sessionId={}, error={}", session.getId(), exception.getMessage());
        activeSessions.values().removeIf(s -> s.getId().equals(session.getId()));
    }

    /**
     * 发送文本消息
     */
    private void sendTextMessage(WebSocketSession session, Map<String, Object> data) throws Exception {
        if (session.isOpen()) {
            String json = objectMapper.writeValueAsString(data);
            session.sendMessage(new TextMessage(json));
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(WebSocketSession session, String errorMessage) throws Exception {
        sendTextMessage(session, Map.of(
            "type", "error",
            "message", errorMessage
        ));
    }

    /**
     * 向指定会话推送消息（可用于服务端主动推送）
     */
    public void pushToSession(String sessionId, Map<String, Object> data) {
        WebSocketSession session = activeSessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                sendTextMessage(session, data);
            } catch (Exception e) {
                log.error("[WebSocket] 推送消息失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
    }
}
