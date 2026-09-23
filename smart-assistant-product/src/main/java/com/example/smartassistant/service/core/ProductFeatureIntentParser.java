package com.example.smartassistant.service.core;

/** Replaceable understanding boundary; implementations may be rule-based or model-backed. */
@FunctionalInterface
public interface ProductFeatureIntentParser {
    ProductFeatureIntent parse(String question);
}
