package com.example.smartassistant.spi;

import java.text.Normalizer;
import java.util.*;

/** Catalog-owned identities. Empty metadata is unknown, never guessed from a name. */
public record ProductIdentity(String code, String name, List<String> aliases, String brand,
                              String family, String model, String generation, String variant,
                              String parentCode, String source) {
    public ProductIdentity {
        Objects.requireNonNull(code); Objects.requireNonNull(name);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        brand = text(brand); family = text(family); model = text(model);
        generation = text(generation); variant = text(variant);
        parentCode = text(parentCode); source = text(source);
    }
    public ProductIdentity(String code, String name, List<String> aliases) {
        this(code, name, aliases, "", "", "", "", "", "", "catalog");
    }
    public List<String> names() {
        Set<String> values = new LinkedHashSet<>(List.of(name, code));
        values.addAll(aliases);
        if (!model.isBlank()) values.add(model);
        if (!family.isBlank()) {
            values.add(family);
            if (!generation.isBlank()) values.add(family + " " + generation + (variant.isBlank() ? "" : " " + variant));
        }
        String base = name.replaceFirst("[（(][^）)]*[）)]$", "").trim();
        if (base.length() >= 3) values.add(base);
        return List.copyOf(values);
    }
    public String descriptor() {
        return String.join(" ", name, brand, family, model, generation, variant);
    }
    /** Ignore formatting, not model suffixes or generation numerals. */
    public static String normalize(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Z}._-]", "");
    }
    private static String text(String value) { return Objects.toString(value, "").trim(); }
}
