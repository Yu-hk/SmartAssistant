package com.example.smartassistant.router.config;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.rag.source.UserDocumentQaService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserDocumentQaConfig {
    @Bean
    UserDocumentQaService userDocumentQaService(@Qualifier("lightChatModel") ChatModel model, AiChatService ai) {
        return new UserDocumentQaService(model, ai);
    }
}
