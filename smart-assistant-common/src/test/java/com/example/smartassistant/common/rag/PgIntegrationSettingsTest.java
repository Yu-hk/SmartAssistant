package com.example.smartassistant.common.rag;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PgIntegrationSettingsTest {
    @Test void acceptsOnlyExplicitDisposableDatabase() {
        var settings = PgIntegrationSettings.from(env("jdbc:postgresql://127.0.0.1:15433/smartassistant_integration"));
        assertTrue(settings.url().endsWith("?connectTimeout=3&socketTimeout=10"));
        assertFalse(settings.toString().contains("test-only-value"));
    }
    @Test void rejectsBusinessDatabaseRemoteHostAndInjectedOptions() {
        for (String url : new String[]{"jdbc:postgresql://localhost:5433/a2a_system",
                "jdbc:postgresql://production:5432/smartassistant_integration",
                "jdbc:postgresql://localhost:15433/smartassistant_integration?currentSchema=public"}) {
            assertThrows(IllegalArgumentException.class, () -> PgIntegrationSettings.from(env(url)));
        }
    }
    @Test void noImplicitUrlOrCredentials() {
        assertThrows(IllegalArgumentException.class, () -> PgIntegrationSettings.from(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PgIntegrationSettings.from(Map.of(
                "PG_TEST_URL", "jdbc:postgresql://localhost:15433/smartassistant_integration")));
    }
    private Map<String, String> env(String url) {
        return Map.of("PG_TEST_URL", url, "PG_TEST_USER", "integration", "PG_TEST_PASSWORD", "test-only-value");
    }
}
