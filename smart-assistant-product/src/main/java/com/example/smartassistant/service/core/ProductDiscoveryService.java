package com.example.smartassistant.service.core;

import com.example.smartassistant.common.util.UserQuestionNormalizer;
import com.example.smartassistant.spi.ProductBackend;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** Deterministic product discovery for generic catalog and popularity queries. */
@Service
public class ProductDiscoveryService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    private final ProductBackend productBackend;

    public ProductDiscoveryService(ProductBackend productBackend) {
        this.productBackend = productBackend;
    }

    /** Returns live catalog categories for tools, routing and UI clients. */
    public List<String> listProductCategories() {
        List<String> categories = productBackend.listProductCategories();
        return categories == null ? List.of() : categories.stream()
                .filter(category -> category != null && !category.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    /** Returns true only for generic discovery requests, not specific product recommendations. */
    public boolean supports(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = UserQuestionNormalizer.normalize(query)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        boolean categoryRequest = !detectCategory(normalized).isBlank();
        boolean popularityRequest = normalized.contains("热门")
                || normalized.contains("热销") || normalized.contains("畅销")
                || normalized.contains("排行");
        return normalized.contains("热门商品")
                || normalized.contains("热销商品")
                || normalized.contains("畅销商品")
                || normalized.contains("商品排行榜")
                || normalized.contains("商品排行")
                || normalized.contains("有什么商品")
                || normalized.contains("有哪些商品")
                || normalized.contains("商品列表")
                || normalized.matches(".*推荐(?:一些|一款|几款|几个|点)?商品.*")
                || (categoryRequest && (popularityRequest || normalized.contains("推荐")));
    }

    public DiscoveryResult discover(String query, Integer requestedLimit) {
        return discover(query, null, requestedLimit);
    }

    /** Category-aware discovery. A specific category always gets a candidate pool, not limit=1. */
    public DiscoveryResult discover(String query, String requestedCategory, Integer requestedLimit) {
        String normalizedQuery = UserQuestionNormalizer.normalize(query);
        String category = normalizeCategory(requestedCategory);
        if (category.isBlank()) category = detectCategory(normalizedQuery);

        int requested = requestedLimit == null
                ? DEFAULT_LIMIT
                : Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        int candidateLimit = category.isBlank() ? requested : Math.max(DEFAULT_LIMIT, requested);
        List<ProductBackend.ProductSummary> products = productBackend.listPopularProducts(
                new ProductBackend.ProductDiscoveryCriteria(category, normalizedQuery, candidateLimit));
        if (products == null || products.isEmpty()) {
            String scope = category.isBlank() ? "商品" : category;
            return new DiscoveryResult("当前暂无可推荐的" + scope + "，请调整条件或稍后再试。",
                    0, false, List.of(), false, category);
        }

        boolean hasPopularityData = products.stream().anyMatch(product -> product.popularity() > 0);
        boolean asksForPopularity = asksForPopularity(normalizedQuery);
        boolean scenarioEvidenceLimited = isScenarioSpecific(normalizedQuery);
        StringBuilder answer = new StringBuilder();
        if (scenarioEvidenceLimited) {
            answer.append("以下仅是当前目录中的可售候选。目录没有可验证的场景适配字段，")
                    .append("因此不能把热度直接等同于适合该办公或会议场景：\n");
        } else if (asksForPopularity && hasPopularityData) {
            answer.append("近期热门").append(category.isBlank() ? "商品" : category)
                    .append("（按订单热度排序）：\n");
        } else if (asksForPopularity) {
            answer.append("当前推荐").append(category.isBlank() ? "商品" : category)
                    .append("（暂无足够销量数据，按可售状态展示）：\n");
        } else {
            answer.append("当前可选").append(category.isBlank() ? "商品" : category).append("：\n");
        }

        for (int i = 0; i < products.size(); i++) {
            ProductBackend.ProductSummary product = products.get(i);
            answer.append(i + 1).append(". ").append(value(product.name(), product.code()))
                    .append("（").append(product.code()).append("）")
                    .append(" — ").append(formatPrice(product.price()))
                    .append("，库存：").append(value(product.stock(), "未知"));
            if (hasPopularityData && product.popularity() > 0) {
                answer.append("，近期订单：").append(product.popularity());
            }
            if (scenarioEvidenceLimited) {
                answer.append("，场景适配：现有目录证据不足，需核实规格和实际需求");
            }
            answer.append('\n');
        }
        if (scenarioEvidenceLimited) {
            answer.append("\n若用于多人办公室或视频会议，请继续确认并发使用人数、摄像头、麦克风、")
                    .append("扬声器、接口和预算要求；在这些规格得到验证前，不应把上述候选表述为最终推荐。");
        } else {
            answer.append("\n告诉我商品名称或编码，我可以继续查询价格、规格和库存。");
        }
        return new DiscoveryResult(answer.toString().trim(), products.size(), hasPopularityData,
                products, scenarioEvidenceLimited, category);
    }

    private static boolean asksForPopularity(String query) {
        if (query == null) return false;
        return query.contains("热门") || query.contains("热销")
                || query.contains("畅销") || query.contains("排行");
    }

    private static String formatPrice(BigDecimal price) {
        return price == null ? "价格待确认" : "¥" + price.stripTrailingZeros().toPlainString();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record DiscoveryResult(
            String answer,
            int productCount,
            boolean popularityBased,
            List<ProductBackend.ProductSummary> products,
            boolean scenarioEvidenceLimited,
            String category) {
        public DiscoveryResult {
            products = products != null ? List.copyOf(products) : List.of();
            category = category == null ? "" : category;
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased) {
            this(answer, productCount, popularityBased, List.of(), false, "");
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased,
                               List<ProductBackend.ProductSummary> products) {
            this(answer, productCount, popularityBased, products, false, "");
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased,
                               List<ProductBackend.ProductSummary> products,
                               boolean scenarioEvidenceLimited) {
            this(answer, productCount, popularityBased, products, scenarioEvidenceLimited, "");
        }
    }

    private String normalizeCategory(String value) {
        if (value == null || value.isBlank()) return "";
        String canonical = detectCategory(value);
        return canonical.isBlank() ? value.trim() : canonical;
    }

    private String detectCategory(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        List<String> categories = listProductCategories();

        // Prefer an explicit category occurring in the question. The longest
        // match wins when the catalog contains nested category names.
        String explicit = categories.stream()
                .filter(category -> normalized.contains(normalizeForMatch(category)))
                .max((left, right) -> Integer.compare(
                        normalizeForMatch(left).length(), normalizeForMatch(right).length()))
                .orElse("");
        if (!explicit.isBlank()) {
            return explicit;
        }

        // A structured short value such as "平板" may identify a single live
        // category. Ambiguous fragments such as "电脑" are deliberately ignored.
        List<String> partialMatches = categories.stream()
                .filter(category -> normalizeForMatch(category).contains(normalized))
                .toList();
        return partialMatches.size() == 1 ? partialMatches.getFirst() : "";
    }

    private static String normalizeForMatch(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isScenarioSpecific(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = query.replaceAll("\\s+", "");
        return normalized.contains("适合") || normalized.contains("用于")
                || normalized.contains("使用场景") || normalized.contains("适用场景")
                || normalized.contains("采购方案") || normalized.contains("办公室")
                || normalized.contains("办公") || normalized.contains("视频会议")
                || normalized.contains("会议室");
    }
}
