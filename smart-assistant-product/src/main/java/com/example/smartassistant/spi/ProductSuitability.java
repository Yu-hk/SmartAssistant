package com.example.smartassistant.spi;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Administrator-declared audiences/use cases, distinct from measurable product features. */
public record ProductSuitability(List<String> audiences, List<String> useCases,
                                 String source, String reviewedAt) {
    public static final ProductSuitability UNKNOWN = new ProductSuitability(List.of(), List.of(), "", "");

    public ProductSuitability {
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
        useCases = useCases == null ? List.of() : List.copyOf(useCases);
        source = source == null ? "" : source.trim();
        reviewedAt = reviewedAt == null ? "" : reviewedAt.trim();
    }

    public boolean declared() {
        return (!audiences.isEmpty() || !useCases.isEmpty()) && !source.isBlank() && !reviewedAt.isBlank();
    }

    public String evidence() {
        if (!declared()) return "目录尚未提供已确认的适用人群或用途标签";
        StringBuilder text = new StringBuilder("目录标注（非性能保证）：");
        if (!audiences.isEmpty()) text.append("适用人群：").append(String.join("、", audiences)).append("；");
        if (!useCases.isEmpty()) text.append("用途：").append(String.join("、", useCases)).append("；");
        return text.append("标注依据：").append(source).append("；确认时间：").append(reviewedAt).toString();
    }

    public static ProductSuitability fromCatalog(String audiences, String useCases,
                                                  String source, String reviewedAt) {
        return new ProductSuitability(tags(audiences), tags(useCases), source, reviewedAt);
    }

    public static ProductSuitability from(Object value) {
        if (value instanceof ProductSuitability suitability) return suitability;
        if (!(value instanceof Map<?, ?> map)) return UNKNOWN;
        return new ProductSuitability(tagList(map.get("audiences")), tagList(map.get("useCases")),
                text(map.get("source")), text(map.get("reviewedAt")));
    }

    private static List<String> tags(String joined) {
        return joined == null || joined.isBlank() ? List.of()
                : Arrays.stream(joined.split("\\|", -1)).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static List<String> tagList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(tag -> !tag.isBlank()).toList();
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
