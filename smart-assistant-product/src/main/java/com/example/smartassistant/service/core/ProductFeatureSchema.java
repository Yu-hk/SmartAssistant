package com.example.smartassistant.service.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** Typed domain metadata backed by product-feature-schema.properties. */
public final class ProductFeatureSchema {
    public enum Kind { QUALITATIVE, QUANTITATIVE, BOOLEAN }

    public record Definition(String code, Kind kind, List<String> aliases, List<String> units,
                             String formField, Map<String, String> messages) {
        public Definition {
            aliases = List.copyOf(aliases);
            units = List.copyOf(units);
            messages = Map.copyOf(messages);
        }
        public boolean mentionedIn(String text) {
            return text != null && aliases.stream().anyMatch(text::contains);
        }
        public String aliasesRegex() { return alternatives(aliases); }
        public String unitsRegex() { return alternatives(units); }
        public String message(String name) {
            String value = messages.get(name);
            if (value == null || value.isBlank())
                throw new IllegalStateException("Missing product feature message: " + code + "." + name);
            return value;
        }
    }

    public record Scenario(String code, List<String> aliases) {
        public Scenario { aliases = List.copyOf(aliases); }
        public boolean mentionedIn(String text) {
            return text != null && aliases.stream().anyMatch(text::contains);
        }
    }

    private static final String RESOURCE = "/product-feature-schema.properties";
    private static final ProductFeatureSchema DEFAULT = loadDefault();
    private final Definition portability;
    private final Definition business;
    private final Definition weight;
    private final Definition battery;
    private final Definition noiseCancelling;
    private final List<Scenario> batteryScenarios;
    private final List<String> noiseCancellingNonEquivalentAliases;
    private final List<String> noiseCancellingFalseAliases;
    private final Properties properties;

    private ProductFeatureSchema(Properties properties) {
        this.properties = properties;
        portability = definition("portability", "preference.portability",
                List.of(), "", List.of());
        business = definition("business", "preference.business", List.of(), "", List.of());
        weight = definition("weight", "feature.weight",
                values("feature.weight.units"), value("feature.weight.form-field"),
                List.of("missing", "multiple", "unsupported"));
        battery = definition("battery", "feature.battery",
                values("feature.battery.units"), "", List.of("missing", "multiple", "scenario-missing"));
        noiseCancelling = definition("noise_cancelling", "feature.noise-cancelling",
                List.of(), "", List.of("missing"));
        batteryScenarios = List.of(
                scenario("video_playback", "feature.battery.scenario.video-playback.aliases"),
                scenario("audio_anc_on", "feature.battery.scenario.audio-anc-on.aliases"),
                scenario("audio_anc_off", "feature.battery.scenario.audio-anc-off.aliases"),
                scenario("mixed_use", "feature.battery.scenario.mixed-use.aliases"));
        noiseCancellingNonEquivalentAliases = values("feature.noise-cancelling.non-equivalent-aliases");
        noiseCancellingFalseAliases = values("feature.noise-cancelling.explicit-false-aliases");
    }

    public static ProductFeatureSchema defaultSchema() { return DEFAULT; }
    public Definition portability() { return portability; }
    public Definition business() { return business; }
    public List<Definition> preferences() { return List.of(portability, business); }
    public List<String> preferenceLabels(java.util.Set<String> codes) {
        return preferences().stream().filter(preference -> codes.contains(preference.code()))
                .map(preference -> preference.aliases().getFirst()).toList();
    }
    public Definition weight() { return weight; }
    public Definition battery() { return battery; }
    public Definition noiseCancelling() { return noiseCancelling; }
    public List<Scenario> batteryScenarios() { return batteryScenarios; }
    public List<String> noiseCancellingNonEquivalentAliases() { return noiseCancellingNonEquivalentAliases; }
    public List<String> noiseCancellingFalseAliases() { return noiseCancellingFalseAliases; }
    public String message(String key) { return value(key); }
    public List<String> unsupportedUnits(String key) { return values(key); }
    public String allPreferenceAndFeatureAliasesRegex() {
        return alternatives(java.util.stream.Stream.concat(preferences().stream(),
                        List.of(weight, battery, noiseCancelling).stream())
                .flatMap(definition -> definition.aliases().stream()).distinct().toList());
    }

    private Definition definition(String code, String prefix, List<String> units,
                                  String formField, List<String> messageNames) {
        Map<String, String> messages = new LinkedHashMap<>();
        for (String name : messageNames) messages.put(name, value(prefix + "." + name));
        Kind kind = Kind.valueOf(value(prefix + ".kind").toUpperCase(java.util.Locale.ROOT));
        return new Definition(code, kind, values(prefix + ".aliases"), units, formField, messages);
    }
    private Scenario scenario(String code, String key) { return new Scenario(code, values(key)); }
    private String value(String key) {
        String result = properties.getProperty(key);
        if (result == null || result.isBlank()) throw new IllegalStateException("Missing product feature schema key: " + key);
        return result.trim();
    }
    private List<String> values(String key) {
        return Arrays.stream(value(key).split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).distinct().toList();
    }
    private static ProductFeatureSchema loadDefault() {
        Properties properties = new Properties();
        try (var input = ProductFeatureSchema.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing product feature schema: " + RESOURCE);
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new ProductFeatureSchema(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load product feature schema: " + RESOURCE, exception);
        }
    }
    private static String alternatives(List<String> values) {
        return values.stream().sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElse("(?!)");
    }
}
