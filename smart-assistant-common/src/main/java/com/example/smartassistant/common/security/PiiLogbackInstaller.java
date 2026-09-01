package com.example.smartassistant.common.security;

import ch.qos.logback.classic.PatternLayout;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs PII-aware replacements for Logback's standard message conversion
 * words before Spring Boot creates appenders and parses logging patterns.
 */
public final class PiiLogbackInstaller {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private PiiLogbackInstaller() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("m", PiiMessageConverter::new);
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("msg", PiiMessageConverter::new);
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("message", PiiMessageConverter::new);
    }
}
