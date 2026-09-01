package com.example.smartassistant.common.security;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback message converter that applies the same PII policy used by model,
 * tool and streaming boundaries.
 */
public final class PiiMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return PiiPolicyEngine.shared().mask(super.convert(event));
    }
}
