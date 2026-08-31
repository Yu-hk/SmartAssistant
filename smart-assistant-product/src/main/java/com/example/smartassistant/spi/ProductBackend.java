/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * ⭐ 商品数据后端 SPI。
 * <p>
 * 下游集成商可以通过实现此接口替换默认的商品数据源。
 * 框架提供 {@link InMemoryProductBackend} 作为默认 Mock 实现，
 * 通过 {@code @ConditionalOnMissingBean} 自动注册。
 * </p>
 *
 * <h3>接入方式</h3>
 * <pre>
 * &#64;Component
 * public class MyDbProductBackend implements ProductBackend {
 *     // Spring 自动检测到此 Bean，默认的 InMemoryProductBackend 自动让位
 * }
 * </pre>
 */
public interface ProductBackend {

    /** 查询商品详细信息 */
    String queryProductInfo(String productCode);

    /** 查询商品库存状态 */
    String checkStock(String productCode);

    /** 查询商品价格 */
    String getPrice(String productCode);

    /** 搜索商品（按关键词模糊匹配） */
    String searchProduct(String keyword);

    /**
     * 返回可用于商品发现/推荐的目录摘要。
     *
     * <p>实现可以按销量、浏览热度或其他可靠业务信号排序；当没有热度信号时，
     * 应返回稳定的可售商品顺序，并将 {@link ProductSummary#popularity()} 置为 0。</p>
     */
    default List<ProductSummary> listPopularProducts(int limit) {
        return List.of();
    }

    /**
     * Returns the product categories currently available in the catalog.
     *
     * <p>The discovery layer uses this method instead of maintaining a hard-coded
     * category list. Integrations should override it when their catalog supports
     * a native category query.</p>
     */
    default List<String> listProductCategories() {
        return listPopularProducts(20).stream()
                .map(ProductSummary::category)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(category -> !category.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns popular products constrained by a structured discovery request.
     * Implementations must not silently drop a non-empty category constraint.
     */
    default List<ProductSummary> listPopularProducts(ProductDiscoveryCriteria criteria) {
        if (criteria == null || criteria.category().isBlank()) {
            return listPopularProducts(criteria != null ? criteria.limit() : 5);
        }
        String category = criteria.category().toLowerCase();
        return listPopularProducts(Math.max(criteria.limit(), 20)).stream()
                .filter(product -> product.category().toLowerCase().contains(category)
                        || product.name().toLowerCase().contains(category)
                        || product.spec().toLowerCase().contains(category))
                .limit(criteria.limit())
                .toList();
    }

    record ProductDiscoveryCriteria(String category, String keyword, int limit) {
        public ProductDiscoveryCriteria {
            category = category == null ? "" : category.trim();
            keyword = keyword == null ? "" : keyword.trim();
            limit = Math.max(1, Math.min(limit, 20));
        }
    }

    record ProductSummary(
            String code,
            String name,
            BigDecimal price,
            String stock,
            String spec,
            long popularity,
            String category
    ) {
        public ProductSummary {
            code = code == null ? "" : code;
            name = name == null ? "" : name;
            stock = stock == null ? "" : stock;
            spec = spec == null ? "" : spec;
            category = category == null ? "" : category;
        }

        /** Compatibility constructor for integrations that have not exposed category yet. */
        public ProductSummary(String code, String name, BigDecimal price, String stock,
                              String spec, long popularity) {
            this(code, name, price, stock, spec, popularity, "");
        }
    }
}
