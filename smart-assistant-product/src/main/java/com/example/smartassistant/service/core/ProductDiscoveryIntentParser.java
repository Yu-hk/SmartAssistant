package com.example.smartassistant.service.core;

import java.util.Locale;

/** Converts a question into typed discovery observations before the service makes decisions. */
public final class ProductDiscoveryIntentParser {
    private final ProductDiscoverySchema schema;
    private final ProductFeatureIntentParser featureParser;
    private final ProductFeatureIntentValidator featureValidator;

    public ProductDiscoveryIntentParser(ProductDiscoverySchema schema, ProductFeatureSchema featureSchema) {
        this.schema = schema;
        this.featureParser = new RuleBasedProductFeatureIntentParser(featureSchema);
        this.featureValidator = new ProductFeatureIntentValidator(featureSchema);
    }

    public ProductDiscoveryIntent parse(String query) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        ProductFeatureIntent featureIntent = featureParser.parse(query);
        ProductFeatureRequest features = featureValidator.validate(featureIntent);
        boolean recommendation = schema.contains("intent.recommend", normalized);
        boolean catalog = schema.contains("intent.browse", normalized)
                || (schema.contains("intent.catalog", normalized) && schema.contains("intent.popularity", normalized));
        return new ProductDiscoveryIntent(recommendation, catalog,
                schema.contains("intent.popularity", normalized), schema.contains("intent.in-stock", normalized),
                schema.contains("intent.restrict", normalized), schema.contains("intent.question", normalized),
                schema.contains("intent.single-choice", normalized), schema.contains("intent.scenario", normalized),
                !featureIntent.qualitativePreferences().isEmpty(),
                features, ProductDiscoveryService.resolveBudget(query));
    }
}
