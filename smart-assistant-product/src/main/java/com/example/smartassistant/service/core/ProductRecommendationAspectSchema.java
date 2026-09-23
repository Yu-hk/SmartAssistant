package com.example.smartassistant.service.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Grounded explanation vocabulary; a matching word is not proof of product suitability. */
final class ProductRecommendationAspectSchema {
    private static final ProductRecommendationAspectSchema DEFAULT = load();
    private final Properties properties;

    private ProductRecommendationAspectSchema(Properties properties) { this.properties = properties; }
    static ProductRecommendationAspectSchema defaultSchema() { return DEFAULT; }

    boolean questionMentions(String aspect, String question) {
        return contains("aspect." + aspect + ".question", question);
    }

    List<String> relatedLabels(String question, String spec) {
        return values("aspects").stream()
                .filter(aspect -> contains("aspect." + aspect + ".question", question)
                        && contains("aspect." + aspect + ".spec", spec))
                .map(aspect -> value("aspect." + aspect + ".label")).toList();
    }

    private boolean contains(String key, String text) {
        return text != null && values(key).stream().anyMatch(text::contains);
    }
    private List<String> values(String key) {
        return Arrays.stream(value(key).split(",")).map(String::trim)
                .filter(s -> !s.isBlank()).distinct().toList();
    }
    private String value(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing recommendation aspect key: " + key);
        return raw.trim();
    }
    private static ProductRecommendationAspectSchema load() {
        Properties properties = new Properties();
        try (var input = ProductRecommendationAspectSchema.class.getResourceAsStream(
                "/product-recommendation-aspects.properties")) {
            if (input == null) throw new IllegalStateException("Missing recommendation aspects schema");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new ProductRecommendationAspectSchema(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load recommendation aspect schema", exception);
        }
    }
}
