package com.example.smartassistant.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-cutting PII policy infrastructure shared by model, tool, stream, log and
 * observability boundaries. This configuration deliberately lives outside the
 * optional RAG package so non-AI services can safely consume common components.
 */
@Configuration(proxyBeanMethods = false)
public class PiiPolicyConfiguration {

    @Bean
    public PiiPolicyEngine piiPolicyEngine() {
        return PiiPolicyEngine.shared();
    }
}
