package com.example.smartassistant.router.service.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Vocabulary for coarse fallback dispatch. Domain Agents still own field parsing. */
final class FallbackDispatchSchema {
    private static final FallbackDispatchSchema DEFAULT = load();
    private final Properties properties;

    FallbackDispatchSchema(Properties properties) { this.properties = properties; }
    static FallbackDispatchSchema defaultSchema() { return DEFAULT; }

    boolean matches(String domain, Set<String> tokens, String question) {
        return values(domain + ".tokens").stream().anyMatch(tokens::contains)
                || values(domain + ".phrases").stream().anyMatch(question::contains);
    }

    private List<String> values(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing fallback dispatch key: " + key);
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
    }

    private static FallbackDispatchSchema load() {
        Properties properties = new Properties();
        try (var input = FallbackDispatchSchema.class.getResourceAsStream("/fallback-dispatch.properties")) {
            if (input == null) throw new IllegalStateException("Missing fallback dispatch schema");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new FallbackDispatchSchema(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load fallback dispatch schema", exception);
        }
    }
}
