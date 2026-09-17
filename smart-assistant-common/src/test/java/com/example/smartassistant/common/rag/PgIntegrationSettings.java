package com.example.smartassistant.common.rag;

import java.util.Map;

/** Fail closed before any SQL: only an explicit loopback disposable database is accepted. */
record PgIntegrationSettings(String url, String user, String password) {
    static PgIntegrationSettings from(Map<String, String> env) {
        String url = required(env, "PG_TEST_URL");
        if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/smartassistant_integration")) {
            throw new IllegalArgumentException("PG_TEST_URL must select the loopback smartassistant_integration database without URL options");
        }
        return new PgIntegrationSettings(url + "?connectTimeout=3&socketTimeout=10",
                required(env, "PG_TEST_USER"), required(env, "PG_TEST_PASSWORD"));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    @Override public String toString() { return "PgIntegrationSettings[credentials redacted]"; }
}
