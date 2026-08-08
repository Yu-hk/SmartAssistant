package com.example.smartassistant.service.core;

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

    /** Returns true only for generic discovery requests, not specific product recommendations. */
    public boolean supports(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = query.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return normalized.contains("热门商品")
                || normalized.contains("热销商品")
                || normalized.contains("畅销商品")
                || normalized.contains("商品排行榜")
                || normalized.contains("商品排行")
                || normalized.contains("有什么商品")
                || normalized.contains("有哪些商品")
                || normalized.contains("商品列表")
                || normalized.matches(".*推荐(?:一些|几款|几个|点)?商品.*");
    }

    public DiscoveryResult discover(String query, Integer requestedLimit) {
        int limit = requestedLimit == null
                ? DEFAULT_LIMIT
                : Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        List<ProductBackend.ProductSummary> products = productBackend.listPopularProducts(limit);
        if (products == null || products.isEmpty()) {
            return new DiscoveryResult("当前暂无可推荐商品，请稍后再试。", 0, false);
        }

        boolean hasPopularityData = products.stream().anyMatch(product -> product.popularity() > 0);
        boolean asksForPopularity = asksForPopularity(query);
        StringBuilder answer = new StringBuilder();
        if (asksForPopularity && hasPopularityData) {
            answer.append("近期热门商品（按订单热度排序）：\n");
        } else if (asksForPopularity) {
            answer.append("当前推荐商品（暂无足够销量数据，按可售状态展示）：\n");
        } else {
            answer.append("当前可选商品：\n");
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
            answer.append('\n');
        }
        answer.append("\n告诉我商品名称或编码，我可以继续查询价格、规格和库存。");
        return new DiscoveryResult(answer.toString().trim(), products.size(), hasPopularityData);
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

    public record DiscoveryResult(String answer, int productCount, boolean popularityBased) {}
}
