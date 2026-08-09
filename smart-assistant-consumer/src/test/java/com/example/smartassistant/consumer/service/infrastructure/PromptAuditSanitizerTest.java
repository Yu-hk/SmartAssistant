package com.example.smartassistant.consumer.service.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptAuditSanitizerTest {

    @Test
    void redactsCredentialsAndBoundsStoredPrompt() {
        String jwt = "eyJabcdefghijk.abcdefghijk.abcdefghijk";
        String value = PromptAuditSanitizer.sanitize(
                "password=secret api_key:abc {\"access_token\":\"json-secret\"} " +
                        "Bearer bearer-value " + jwt + " " + "x".repeat(3000));

        assertNotNull(value);
        assertFalse(value.contains("secret"));
        assertFalse(value.contains("bearer-value"));
        assertFalse(value.contains("json-secret"));
        assertFalse(value.contains(jwt));
        assertTrue(value.contains("[REDACTED]"));
        assertTrue(value.length() <= 2001);
    }
}
