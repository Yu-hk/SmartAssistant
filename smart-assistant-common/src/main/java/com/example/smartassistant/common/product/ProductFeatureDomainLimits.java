package com.example.smartassistant.common.product;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Shared sanity bounds for product facts and customer hard constraints. */
public final class ProductFeatureDomainLimits {
    private static final ProductFeatureDomainLimits DEFAULT = load();
    private final Properties properties;

    private ProductFeatureDomainLimits(Properties properties) { this.properties = properties; }
    public static ProductFeatureDomainLimits defaultLimits() { return DEFAULT; }
    public BigDecimal maximumWeightGrams() { return decimal("weight.max-grams"); }
    public BigDecimal maximumBatteryHours() { return decimal("battery.max-hours"); }
    public int weightMaxScale() { return integer("weight.max-scale"); }
    public int batteryMaxScale() { return integer("battery.max-scale"); }
    private BigDecimal decimal(String key) { return new BigDecimal(value(key)); }
    private int integer(String key) { return Integer.parseInt(value(key)); }
    private String value(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing product limit: " + key);
        return raw.trim();
    }
    private static ProductFeatureDomainLimits load() {
        Properties properties = new Properties();
        try (var input = ProductFeatureDomainLimits.class.getResourceAsStream("/product-feature-domain.properties")) {
            if (input == null) throw new IllegalStateException("Missing product feature limits");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new ProductFeatureDomainLimits(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load product feature limits", exception);
        }
    }
}
