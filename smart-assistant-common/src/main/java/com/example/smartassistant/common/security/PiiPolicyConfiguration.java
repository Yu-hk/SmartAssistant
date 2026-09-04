package com.example.smartassistant.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Cross-cutting PII policy infrastructure shared by model, tool, stream, log and
 * observability boundaries. This configuration deliberately lives outside the
 * optional RAG package so non-AI services can safely consume common components.
 */
@AutoConfiguration
public class PiiPolicyConfiguration {

    @Bean
    @ConditionalOnMissingBean(PiiPolicyEngine.class)
    public PiiPolicyEngine piiPolicyEngine() {
        return PiiPolicyEngine.shared();
    }
}
