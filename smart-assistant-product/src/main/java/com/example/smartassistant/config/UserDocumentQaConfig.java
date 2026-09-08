package com.example.smartassistant.config;

import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TierModelRegistry;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.rag.source.UserDocumentQaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserDocumentQaConfig {
    @Bean
    UserDocumentQaService userDocumentQaService(TierModelRegistry models, AiChatService ai) {
        return new UserDocumentQaService(models.get(ModelTier.STANDARD), ai);
    }
}
