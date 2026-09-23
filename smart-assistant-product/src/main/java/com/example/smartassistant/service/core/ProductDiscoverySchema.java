package com.example.smartassistant.service.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Locale;
import java.math.BigDecimal;

/** Versioned domain vocabulary; hard-filter structure and numeric validation remain in Java. */
public final class ProductDiscoverySchema {
    private static final ProductDiscoverySchema DEFAULT = load();
    private final Properties properties;

    private ProductDiscoverySchema(Properties properties) { this.properties = properties; }
    public static ProductDiscoverySchema defaultSchema() { return DEFAULT; }

    public List<String> terms(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing discovery schema key: " + key);
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
    }

    public boolean contains(String key, String text) {
        return text != null && terms(key).stream().anyMatch(text::contains);
    }

    public boolean unavailableStock(String stock) {
        if (stock == null || stock.isBlank()) return false;
        String normalized = stock.toLowerCase(Locale.ROOT).trim();
        return normalized.matches("0(?:\\.0+)?") || contains("stock.unavailable", normalized);
    }

    public List<String> unavailableStockTerms() { return terms("stock.unavailable"); }

    public List<String> categoryAliases() { return terms("category.alias.notebook"); }
    public BigDecimal budgetMinimum() { return new BigDecimal(value("budget.min-yuan")); }
    public BigDecimal budgetMaximum() { return new BigDecimal(value("budget.max-yuan")); }
    public String budgetMessage(String key) { return value("budget." + key + "-message"); }

    private String value(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing discovery schema key: " + key);
        return raw.trim();
    }

    private static ProductDiscoverySchema load() {
        Properties properties = new Properties();
        try (var input = ProductDiscoverySchema.class.getResourceAsStream("/product-discovery-schema.properties")) {
            if (input == null) throw new IllegalStateException("Missing product discovery schema");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new ProductDiscoverySchema(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load product discovery schema", exception);
        }
    }
}
