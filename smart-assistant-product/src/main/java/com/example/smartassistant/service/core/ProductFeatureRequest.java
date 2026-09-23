package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.ProductFeatureConstraints;

import java.util.List;
import java.util.Set;

/** Stable request contract. Understanding and deterministic validation are delegated to a resolver. */
public record ProductFeatureRequest(ProductFeatureConstraints constraints, String clarification,
                                    List<String> missingFields, Set<String> qualitativePreferences) {
    private static final ProductFeatureRequestResolver DEFAULT_RESOLVER =
            ProductFeatureRequestResolver.defaultResolver();

    public ProductFeatureRequest(ProductFeatureConstraints constraints, String clarification) {
        this(constraints, clarification, List.of(), Set.of());
    }

    public ProductFeatureRequest(ProductFeatureConstraints constraints, String clarification,
                                 List<String> missingFields) {
        this(constraints, clarification, missingFields, Set.of());
    }

    public ProductFeatureRequest {
        missingFields = List.copyOf(missingFields);
        qualitativePreferences = Set.copyOf(qualitativePreferences);
    }

    public static ProductFeatureRequest parse(String question) {
        return DEFAULT_RESOLVER.resolve(question);
    }
}
