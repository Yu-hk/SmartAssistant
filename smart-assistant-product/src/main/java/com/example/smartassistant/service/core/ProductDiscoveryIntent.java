package com.example.smartassistant.service.core;

/** Structured discovery request; only validated quantitative fields become database filters. */
public record ProductDiscoveryIntent(boolean recommendation, boolean catalogBrowse,
                                     boolean popularity, boolean inStockOnly,
                                     boolean categoryRestricted, boolean detailQuestion,
                                     boolean singleChoice, boolean scenarioSpecific,
                                     boolean qualitativePreference,
                                     ProductFeatureRequest features,
                                     ProductDiscoveryService.BudgetResolution budget) {
    public boolean hasFeatureInterest() {
        return qualitativePreference || features.constraints().active() || !features.clarification().isBlank();
    }
    public boolean hardConstraintRequested() {
        return features.constraints().active() || budget.max() != null || budget.ambiguous() || categoryRestricted;
    }
}
