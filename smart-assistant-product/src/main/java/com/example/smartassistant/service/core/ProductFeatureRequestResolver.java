package com.example.smartassistant.service.core;

import java.util.Objects;

/** Composes replaceable understanding with deterministic validation. */
public final class ProductFeatureRequestResolver {
    private final ProductFeatureIntentParser parser;
    private final ProductFeatureIntentValidator validator;

    public ProductFeatureRequestResolver(ProductFeatureIntentParser parser,
                                         ProductFeatureIntentValidator validator) {
        this.parser = Objects.requireNonNull(parser);
        this.validator = Objects.requireNonNull(validator);
    }

    public ProductFeatureRequest resolve(String question) {
        return validator.validate(parser.parse(question));
    }

    public static ProductFeatureRequestResolver defaultResolver() {
        ProductFeatureSchema schema = ProductFeatureSchema.defaultSchema();
        return new ProductFeatureRequestResolver(new RuleBasedProductFeatureIntentParser(schema),
                new ProductFeatureIntentValidator(schema));
    }
}
