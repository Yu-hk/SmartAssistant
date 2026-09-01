package com.example.smartassistant.common.security;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMessageConverterTest {

    @Test
    void standardMessagePatternUsesSharedPiiPolicy() {
        PiiLogbackInstaller.install();
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern("%msg");
        layout.start();

        LoggingEvent event = new LoggingEvent();
        event.setMessage("question=手机号13800138000 email=user@example.com");

        String rendered = layout.doLayout(event);
        assertThat(rendered).doesNotContain("13800138000", "user@example.com");
        assertThat(rendered).contains("138****8000", "[EMAIL]");
    }
}
