package com.example.smartassistant.consumer.config;

import com.example.smartassistant.consumer.websocket.ConversationWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ConversationWebSocketHandler conversationWebSocketHandler;

    public WebSocketConfig(ConversationWebSocketHandler conversationWebSocketHandler) {
        this.conversationWebSocketHandler = conversationWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册 WebSocket 处理器
        registry.addHandler(conversationWebSocketHandler, "/ws/conversation")
                .setAllowedOrigins("http://localhost:3000", "http://localhost:3001", "http://127.0.0.1:3000", "http://127.0.0.1:3001");  // 限制为前端地址
    }
}
