package com.example.smartassistant.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("chat.dispatch")
public record ChatDispatchProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("30000") long queueWaitMs, @DefaultValue("150000") long resultWaitMs,
        @DefaultValue("4") int concurrency, @DefaultValue("100") int maxLength) {
    public ChatDispatchProperties {
        if (queueWaitMs < 1000 || queueWaitMs > 60000 || resultWaitMs < queueWaitMs
                || resultWaitMs > 300000 || concurrency < 1 || concurrency > 32 || maxLength < 1) {
            throw new IllegalArgumentException("Invalid chat dispatch limits");
        }
    }
}
