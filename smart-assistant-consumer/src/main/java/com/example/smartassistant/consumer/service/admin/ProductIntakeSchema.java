package com.example.smartassistant.consumer.service.admin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** Intake-side product vocabulary. Facts remain subject to extractor safety checks. */
final class ProductIntakeSchema {
    private static final ProductIntakeSchema DEFAULT = load();
    private final Properties properties;

    private ProductIntakeSchema(Properties properties) { this.properties = properties; }
    static ProductIntakeSchema defaultSchema() { return DEFAULT; }
    List<String> values(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing product intake key: " + key);
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
    }
    boolean contains(String key, String text) {
        return text != null && values(key).stream().anyMatch(text::contains);
    }
    String alternatives(String key) {
        return values(key).stream().sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElse("(?!)");
    }
    private static ProductIntakeSchema load() {
        Properties properties = new Properties();
        try (var input = ProductIntakeSchema.class.getResourceAsStream("/product-intake-schema.properties")) {
            if (input == null) throw new IllegalStateException("Missing product intake schema");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new ProductIntakeSchema(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load product intake schema", exception);
        }
    }
}
