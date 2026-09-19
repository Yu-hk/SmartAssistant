package com.example.smartassistant.consumer.rag;

import com.example.smartassistant.common.memory.EntityProfileService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.smartassistant.consumer.service.recommendation.GovernedEntityProfileStore;

/**
 * EntityProfileService 配置（独立于 BGE，始终启用）
 */
@Configuration
public class EntityProfileConfig {

    @Bean
    public EntityProfileService entityProfileService(GovernedEntityProfileStore store) {
        return new EntityProfileService(store);
    }
}
