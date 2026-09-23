package com.example.smartassistant.service.core;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Default deterministic syntax adapter. Domain vocabulary comes from {@link ProductFeatureSchema}. */
public final class RuleBasedProductFeatureIntentParser implements ProductFeatureIntentParser {
    private static final String NUMBER = "(\\d+(?:\\.\\d+)?)";

    private final ProductFeatureSchema schema;
    private final Pattern weightPattern;
    private final Pattern weightValuePattern;
    private final Pattern batteryPattern;
    private final Pattern batteryValuePattern;
    private final Pattern negatedPreferenceOrFeature;

    public RuleBasedProductFeatureIntentParser(ProductFeatureSchema schema) {
        this.schema = schema;
        String weightUnit = "(" + schema.weight().unitsRegex() + ")";
        String batteryAlias = "(?:" + schema.battery().aliasesRegex() + ")";
        String batteryUnit = "(?:" + schema.battery().unitsRegex() + ")";
        weightPattern = Pattern.compile(
                "(?:重量上[限线](?:为|是|设为|改为|调整为|[:：=])?|不超过|不大于|最多|至多|<=|≤)" + NUMBER + weightUnit
                        + "|" + NUMBER + weightUnit + "(?:以内|以下|及以下)");
        weightValuePattern = Pattern.compile(NUMBER + weightUnit);
        batteryPattern = Pattern.compile(
                batteryAlias + "(?:至少|不低于|不少于|>=|≥)" + NUMBER + batteryUnit
                        + "|" + batteryAlias + NUMBER + batteryUnit + "(?:以上|及以上|起)");
        batteryValuePattern = Pattern.compile(NUMBER + batteryUnit);
        negatedPreferenceOrFeature = Pattern.compile(
                "(?:不需要|不要求|不关心|无需)(?:" + schema.allPreferenceAndFeatureAliasesRegex() + ")");
    }

    @Override
    public ProductFeatureIntent parse(String question) {
        String normalizedQuestion = normalize(question);
        Set<String> preferences = new LinkedHashSet<>();
        schema.preferences().stream().filter(preference -> preference.mentionedIn(normalizedQuestion))
                .map(ProductFeatureSchema.Definition::code).forEach(preferences::add);

        String weightQuery = normalize(ProductQueryContext.latest(question,
                text -> schema.weight().mentionedIn(text) || containsNumberWithUnit(text, schema.weight())));
        String batteryQuery = normalize(ProductQueryContext.latest(question, schema.battery()::mentionedIn));
        String noiseQuery = normalize(ProductQueryContext.latest(question, schema.noiseCancelling()::mentionedIn));

        BigDecimal weight = null;
        int weightBounds = 0;
        var weightMatcher = weightPattern.matcher(weightQuery);
        while (weightMatcher.find()) {
            BigDecimal value = new BigDecimal(weightMatcher.group(1) != null
                    ? weightMatcher.group(1) : weightMatcher.group(3));
            String unit = weightMatcher.group(2) != null ? weightMatcher.group(2) : weightMatcher.group(4);
            if (!unit.equals("克") && !unit.equals("g")) value = value.multiply(BigDecimal.valueOf(1000));
            weight = weight == null ? value : weight.min(value);
            weightBounds++;
        }

        BigDecimal battery = null;
        int batteryBounds = 0;
        var batteryMatcher = batteryPattern.matcher(batteryQuery);
        while (batteryMatcher.find()) {
            BigDecimal value = new BigDecimal(batteryMatcher.group(1) != null
                    ? batteryMatcher.group(1) : batteryMatcher.group(2));
            battery = battery == null ? value : battery.max(value);
            batteryBounds++;
        }
        List<String> scenarios = schema.batteryScenarios().stream()
                .filter(scenario -> scenario.mentionedIn(batteryQuery))
                .map(ProductFeatureSchema.Scenario::code).toList();

        boolean noiseTypeAmbiguous = containsAny(noiseQuery, schema.noiseCancellingNonEquivalentAliases());
        Boolean noiseCancelling = null;
        if (containsAny(noiseQuery, schema.noiseCancellingFalseAliases())) noiseCancelling = false;
        else if (schema.noiseCancelling().mentionedIn(noiseQuery)
                && !noiseQuery.matches(".*(?:关闭|关掉|不开)(?:主动)?降噪.*")) noiseCancelling = true;

        return new ProductFeatureIntent(preferences, weight,
                schema.weight().mentionedIn(weightQuery) || containsNumberWithUnit(weightQuery, schema.weight()),
                weightBounds, (int) weightValuePattern.matcher(weightQuery).results().count(),
                battery, schema.battery().mentionedIn(batteryQuery), batteryBounds,
                (int) batteryValuePattern.matcher(batteryQuery).results().count(), scenarios,
                noiseCancelling, noiseTypeAmbiguous,
                (weightQuery + batteryQuery + noiseQuery).matches(".*(?:或者|或是|二选一).*"),
                hasUnsupportedUnit(weightQuery, schema.unsupportedUnits("feature.weight.unsupported-units"))
                        || hasUnsupportedUnit(batteryQuery, schema.unsupportedUnits("feature.battery.unsupported-units")));
    }

    private String normalize(String question) {
        if (question == null) return "";
        return negatedPreferenceOrFeature.matcher(question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT))
                .replaceAll("");
    }

    private static boolean containsNumberWithUnit(String text, ProductFeatureSchema.Definition definition) {
        if (text == null) return false;
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .matches(".*\\d(?:" + definition.unitsRegex() + ").*");
    }

    private static boolean containsAny(String text, List<String> values) {
        return values.stream().anyMatch(text::contains);
    }

    private static boolean hasUnsupportedUnit(String text, List<String> units) {
        return units.stream().anyMatch(unit -> Pattern.compile("\\d+(?:\\.\\d+)?" + Pattern.quote(unit))
                .matcher(text).find());
    }
}
