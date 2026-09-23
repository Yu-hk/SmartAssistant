package com.example.smartassistant.service.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Parser output containing observations only; validation policy is applied separately. */
public record ProductFeatureIntent(
        Set<String> qualitativePreferences,
        BigDecimal maxWeightGrams, boolean weightMentioned, int weightBounds, int weightValues,
        BigDecimal minBatteryLifeHours, boolean batteryMentioned, int batteryBounds, int batteryValues,
        List<String> batteryScenarios, Boolean noiseCancelling,
        boolean noiseCancellingTypeAmbiguous, boolean alternativeExpression,
        boolean unsupportedUnit) {
    public ProductFeatureIntent {
        qualitativePreferences = Set.copyOf(qualitativePreferences);
        batteryScenarios = List.copyOf(batteryScenarios);
    }
}
