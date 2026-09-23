package com.example.smartassistant.service.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Domain vocabulary for deterministic read-only observations, not write authorization. */
final class OrderIntentVocabulary {
    private static final OrderIntentVocabulary DEFAULT = load();
    private final Properties properties;

    private OrderIntentVocabulary(Properties properties) { this.properties = properties; }
    static OrderIntentVocabulary defaultVocabulary() { return DEFAULT; }
    boolean contains(String key, String text) {
        return text != null && values(key).stream().anyMatch(text::contains);
    }
    boolean exact(String key, String text) { return values(key).contains(text); }
    private List<String> values(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing order intent vocabulary: " + key);
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
    }
    private static OrderIntentVocabulary load() {
        Properties properties = new Properties();
        try (var input = OrderIntentVocabulary.class.getResourceAsStream("/order-intent-vocabulary.properties")) {
            if (input == null) throw new IllegalStateException("Missing order intent vocabulary");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new OrderIntentVocabulary(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load order intent vocabulary", exception);
        }
    }
}
