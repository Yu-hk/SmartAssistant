package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.ProductFeatureConstraints;
import com.example.smartassistant.common.product.ProductFeatureDomainLimits;

import java.util.ArrayList;
import java.util.List;

/** Deterministic safety policy applied after intent understanding. */
public final class ProductFeatureIntentValidator {
    private final ProductFeatureSchema schema;

    public ProductFeatureIntentValidator(ProductFeatureSchema schema) {
        this.schema = schema;
    }

    public ProductFeatureRequest validate(ProductFeatureIntent intent) {
        List<Issue> issues = new ArrayList<>();
        var weight = schema.weight();
        var battery = schema.battery();

        if (intent.weightMentioned() && intent.maxWeightGrams() == null)
            issues.add(new Issue(weight.message("missing"), weight.formField()));
        if (intent.weightBounds() > 1)
            issues.add(new Issue(weight.message("multiple"), weight.formField()));
        if (intent.weightBounds() > 0 && intent.weightValues() > intent.weightBounds())
            issues.add(new Issue(weight.message("unsupported"), ""));
        if (intent.batteryMentioned() && intent.minBatteryLifeHours() == null)
            issues.add(new Issue(battery.message("missing"), ""));
        if (intent.batteryBounds() > 1)
            issues.add(new Issue(battery.message("multiple"), ""));
        if (intent.batteryBounds() > 0 && intent.batteryValues() > intent.batteryBounds())
            issues.add(new Issue(battery.message("multiple"), ""));
        if (intent.minBatteryLifeHours() != null && intent.batteryScenarios().size() != 1)
            issues.add(new Issue(battery.message("scenario-missing"), ""));
        if (intent.noiseCancellingTypeAmbiguous())
            issues.add(new Issue(schema.noiseCancelling().message("missing"), ""));
        if (intent.maxWeightGrams() != null && intent.maxWeightGrams().signum() <= 0
                || intent.minBatteryLifeHours() != null && intent.minBatteryLifeHours().signum() <= 0)
            issues.add(new Issue(schema.message("validation.non-positive"), ""));
        ProductFeatureDomainLimits limits = ProductFeatureDomainLimits.defaultLimits();
        if (intent.maxWeightGrams() != null && intent.maxWeightGrams().compareTo(limits.maximumWeightGrams()) > 0
                || intent.minBatteryLifeHours() != null && intent.minBatteryLifeHours().compareTo(
                limits.maximumBatteryHours()) > 0)
            issues.add(new Issue(schema.message("validation.upper-bound"), ""));
        if (intent.unsupportedUnit()) issues.add(new Issue(schema.message("validation.unit"), ""));
        if ((intent.maxWeightGrams() != null || intent.minBatteryLifeHours() != null
                || intent.noiseCancelling() != null) && intent.alternativeExpression())
            issues.add(new Issue(schema.message("validation.alternative"), ""));

        if (!issues.isEmpty()) {
            String clarification = schema.message("clarification.prefix")
                    + String.join("、", issues.stream().map(Issue::message).toList()) + "。"
                    + (intent.minBatteryLifeHours() != null && intent.batteryScenarios().size() != 1
                    ? schema.message("clarification.battery-scenario-note") : "");
            List<String> fields = issues.stream().allMatch(issue -> !issue.formField().isBlank())
                    ? issues.stream().map(Issue::formField).distinct().toList() : List.of();
            return new ProductFeatureRequest(ProductFeatureConstraints.NONE, clarification, fields,
                    intent.qualitativePreferences());
        }
        return new ProductFeatureRequest(new ProductFeatureConstraints(intent.maxWeightGrams(),
                intent.minBatteryLifeHours(), intent.batteryScenarios().size() == 1
                ? intent.batteryScenarios().getFirst() : "", intent.noiseCancelling()), "",
                List.of(), intent.qualitativePreferences());
    }

    private record Issue(String message, String formField) {}
}
