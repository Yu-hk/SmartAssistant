package com.example.smartassistant.spi;

import java.math.BigDecimal;

/** Hard requirements only. Unknown facts cannot satisfy an explicit requirement. */
public record ProductFeatureConstraints(BigDecimal maxWeightGrams, BigDecimal minBatteryLifeHours,
                                       String batteryLifeScenario, Boolean noiseCancelling) {
    public static final ProductFeatureConstraints NONE = new ProductFeatureConstraints(null, null, "", null);
    public ProductFeatureConstraints {
        batteryLifeScenario = batteryLifeScenario == null ? "" : batteryLifeScenario;
        if (maxWeightGrams != null && maxWeightGrams.signum() <= 0
                || minBatteryLifeHours != null && minBatteryLifeHours.signum() <= 0) {
            throw new IllegalArgumentException("Feature thresholds must be positive");
        }
        if (minBatteryLifeHours != null && !ProductFeatures.BATTERY_SCENARIOS.contains(batteryLifeScenario)) {
            throw new IllegalArgumentException("Battery requirement needs a comparable scenario");
        }
    }

    public boolean active() { return maxWeightGrams != null || minBatteryLifeHours != null || noiseCancelling != null; }

    public boolean matches(ProductFeatures facts) {
        if (!active()) return true;
        if (facts == null || !facts.documented()) return false;
        return (maxWeightGrams == null || facts.weightGrams() != null && facts.weightGrams().compareTo(maxWeightGrams) <= 0)
                && (minBatteryLifeHours == null || facts.batteryLifeHours() != null
                    && batteryLifeScenario.equals(facts.batteryLifeScenario())
                    && facts.batteryLifeHours().compareTo(minBatteryLifeHours) >= 0)
                && (noiseCancelling == null || noiseCancelling.equals(facts.noiseCancelling()));
    }
}
