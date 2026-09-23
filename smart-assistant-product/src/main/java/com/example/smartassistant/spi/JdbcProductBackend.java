/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.service.core.ProductDiscoverySchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

/**
 * Reads only the live PostgreSQL catalog. Failure must not substitute demo facts.
 */
public class JdbcProductBackend implements ProductBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductBackend.class);
    private static final int SEARCH_LIMIT = 20;
    private static final String PRODUCTION_CATALOG_FILTER = """
            UPPER(p.product_code) NOT LIKE 'LOAD-PROD-%'
            AND UPPER(p.product_code) NOT LIKE 'E2E-PROD-%'
            """;
    private static final String SELECT_COLUMNS = """
            SELECT product_code, product_name, price, stock, spec,
                   COALESCE(to_jsonb(p)->>'colors', to_jsonb(p)->>'color', '') AS color,
                   NULLIF(to_jsonb(p)->>'weight_grams', '')::NUMERIC AS weight_grams,
                   NULLIF(to_jsonb(p)->>'battery_life_hours', '')::NUMERIC AS battery_life_hours,
                   to_jsonb(p)->>'battery_life_scenario' AS battery_life_scenario,
                   NULLIF(to_jsonb(p)->>'noise_cancelling', '')::BOOLEAN AS noise_cancelling,
                   to_jsonb(p)->>'feature_source' AS feature_source,
                   to_jsonb(p)->>'features_verified_at' AS features_verified_at
              FROM products p
            """;
    private static final String DISCOVERY_CATEGORY =
            "COALESCE(to_jsonb(p)->>'category', '')";
    private static final String DISCOVERY_SALES =
            "COALESCE(NULLIF(to_jsonb(p)->>'sales_30d', '')::BIGINT, 0)";
    private static final String DISCOVERY_MARKET_PRICE =
            "NULLIF(to_jsonb(p)->>'market_price', '')::NUMERIC";
    private static final String DISCOVERY_RATING =
            "NULLIF(to_jsonb(p)->>'rating', '')::NUMERIC";
    private static final String DISCOVERY_REVIEW_COUNT =
            "COALESCE(NULLIF(to_jsonb(p)->>'review_count', '')::BIGINT, 0)";

    private final JdbcTemplate jdbcTemplate;
    private final ProductBackend fallback = new UnavailableProductBackend();

    /** Compatibility overload: a demo backend is deliberately never used as a live fallback. */
    public JdbcProductBackend(JdbcTemplate jdbcTemplate, ProductBackend fallback) {
        this(jdbcTemplate);
    }

    public JdbcProductBackend(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String queryProductInfo(String productCode) {
        ProductRecord product;
        try {
            product = findProduct(productCode);
        } catch (AmbiguousProductException e) {
            return e.clarification();
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 商品精确查询失败: {}", e.getClass().getSimpleName());
            return fallback.queryProductInfo(productCode);
        }
        if (product != null) {
            return formatDetails(product);
        }
        if (jdbcTemplate == null) {
            return fallback.queryProductInfo(productCode);
        }
        return ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode,
                "请确认商品编码或名称是否正确");
    }

    @Override
    public String checkStock(String productCode) {
        ProductRecord product;
        try {
            product = findProduct(productCode);
        } catch (AmbiguousProductException e) {
            return e.clarification();
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 库存查询失败: {}", e.getClass().getSimpleName());
            return fallback.checkStock(productCode);
        }
        if (product == null) {
            return jdbcTemplate == null
                    ? fallback.checkStock(productCode)
                    : ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode);
        }
        return switch (java.util.Objects.toString(product.stock(), "")) {
            case "充足" -> product.name() + " 库存充足。";
            case "紧张" -> product.name() + " 库存紧张。";
            case "缺货" -> product.name() + " 暂时缺货。";
            default -> product.name() + " 库存状态尚未确认。";
        };
    }

    @Override
    public String getPrice(String productCode) {
        ProductRecord product;
        try {
            product = findProduct(productCode);
        } catch (AmbiguousProductException e) {
            return e.clarification();
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 价格查询失败: {}", e.getClass().getSimpleName());
            return fallback.getPrice(productCode);
        }
        if (product == null) {
            return jdbcTemplate == null
                    ? fallback.getPrice(productCode)
                    : ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode);
        }
        return String.format("%s 售价 %s 元。",
                product.name(), formatPrice(product.price()));
    }

    @Override
    public String searchProduct(String keyword) {
        if (jdbcTemplate == null) {
            return fallback.searchProduct(keyword);
        }
        String query = normalize(keyword);
        if (query.isBlank()) {
            return "未找到匹配的商品";
        }
        String like = "%" + query + "%";
        List<ProductRecord> products;
        try {
            String sql = (SELECT_COLUMNS + """
                            WHERE (UPPER(product_code) LIKE ?
                               OR UPPER(product_name) LIKE ?
                               OR UPPER(COALESCE(spec, '')) LIKE ?
                               OR ? LIKE '%' || UPPER(product_code) || '%'
                               OR ? LIKE '%' || UPPER(product_name) || '%')
                              AND __PRODUCTION_CATALOG_FILTER__
                            ORDER BY CASE
                                WHEN UPPER(product_code) = ? THEN 0
                                WHEN UPPER(product_name) = ? THEN 1
                                ELSE 2
                            END, product_code
                            LIMIT ?
                            """).replace("__PRODUCTION_CATALOG_FILTER__",
                            PRODUCTION_CATALOG_FILTER);
            products = jdbcTemplate.query(sql, this::mapProduct,
                    like, like, like, query, query, query, query, SEARCH_LIMIT);
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 商品搜索失败: {}", e.getClass().getSimpleName());
            return fallback.searchProduct(keyword);
        }
        if (products.isEmpty()) {
            return "未找到匹配的商品";
        }
        StringBuilder result = new StringBuilder("搜索结果：\n");
        for (ProductRecord product : products) {
            result.append("· ").append(product.name())
                    .append(" — ¥").append(formatPrice(product.price())).append('\n');
        }
        return result.toString().trim();
    }

    @Override
    public List<ProductSummary> listPopularProducts(int limit) {
        return listPopularProducts(new ProductDiscoveryCriteria("", "", limit));
    }

    @Override
    public List<String> listProductCategories() {
        if (jdbcTemplate == null) {
            return fallback.listProductCategories();
        }
        try {
            String sql = ("""
                    SELECT DISTINCT %s AS category
                      FROM products p
                     WHERE %s
                       AND %s <> ''
                     ORDER BY category
                    """).formatted(DISCOVERY_CATEGORY, PRODUCTION_CATALOG_FILTER,
                    DISCOVERY_CATEGORY);
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("category")).stream()
                    .filter(category -> category != null && !category.isBlank())
                    .toList();
        } catch (RuntimeException e) {
            throw new ProductCatalogUnavailableException(e);
        }
    }

    @Override
    public List<ProductSummary> listPopularProducts(ProductDiscoveryCriteria criteria) {
        ProductDiscoveryCriteria safeCriteria = criteria != null
                ? criteria : new ProductDiscoveryCriteria("", "", 5);
        if (jdbcTemplate == null) {
            return fallback.listPopularProducts(safeCriteria);
        }
        int safeLimit = Math.max(1, Math.min(safeCriteria.limit(), SEARCH_LIMIT));
        String category = normalize(safeCriteria.category());
        BigDecimal maxPrice = safeCriteria.maxPrice();
        boolean inStockOnly = safeCriteria.inStockOnly();
        try {
            String sql = ("""
                    SELECT p.product_code, p.product_name, p.price, p.stock, p.spec,
                           %s AS category,
                           %s AS market_price,
                           %s AS popularity,
                           %s AS rating,
                           %s AS review_count,
                           NULLIF(to_jsonb(p)->>'weight_grams', '')::NUMERIC AS weight_grams,
                           NULLIF(to_jsonb(p)->>'battery_life_hours', '')::NUMERIC AS battery_life_hours,
                           to_jsonb(p)->>'battery_life_scenario' AS battery_life_scenario,
                           NULLIF(to_jsonb(p)->>'noise_cancelling', '')::BOOLEAN AS noise_cancelling,
                           to_jsonb(p)->>'feature_source' AS feature_source,
                           to_jsonb(p)->>'features_verified_at' AS features_verified_at
                      FROM products p
                     WHERE %s
                           AND (CAST(? AS TEXT) = '' OR UPPER(%s) = CAST(? AS TEXT))
                           AND (CAST(? AS NUMERIC) IS NULL OR p.price <= CAST(? AS NUMERIC))
                           __STOCK_FILTER__
                           __FEATURE_FILTER__
                     ORDER BY popularity DESC,
                              CASE p.stock WHEN '充足' THEN 0 WHEN '紧张' THEN 1 ELSE 2 END,
                              p.product_code
                         LIMIT CAST(? AS INTEGER)
                    """).formatted(DISCOVERY_CATEGORY, DISCOVERY_MARKET_PRICE,
                    DISCOVERY_SALES, DISCOVERY_RATING, DISCOVERY_REVIEW_COUNT,
                    PRODUCTION_CATALOG_FILTER, DISCOVERY_CATEGORY);
            List<Object> parameters = new ArrayList<>(java.util.Arrays.asList(
                    category, category, maxPrice, maxPrice));
            sql = sql.replace("__STOCK_FILTER__", stockFilter(parameters, inStockOnly));
            sql = sql.replace("__FEATURE_FILTER__", featureFilter(safeCriteria.features(), parameters));
            parameters.add(safeLimit);
            return jdbcTemplate.query(sql, (rs, rowNum) -> new ProductSummary(
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getBigDecimal("price"),
                    rs.getString("stock"),
                    rs.getString("spec"),
                    rs.getLong("popularity"),
                    rs.getString("category"),
                    rs.getBigDecimal("market_price"),
                    rs.getBigDecimal("rating"),
                    rs.getLong("review_count"),
                    mapFeatures(rs)),
                    parameters.toArray());
        } catch (RuntimeException e) {
            throw new ProductCatalogUnavailableException(e);
        }
    }


    @Override
    public FactLookup lookupFacts(String name) {
        if (jdbcTemplate == null) throw new ProductCatalogUnavailableException();
        try {
            ProductRecord row = findProduct(name);
            return new FactLookup(row == null ? List.of() : List.of(new ProductFact(
                    row.code(), row.name(), row.price(), row.stock(), row.spec(), row.color())), false);
        } catch (AmbiguousProductException ambiguous) {
            // Never select an arbitrary version or return its price.
            return new FactLookup(List.of(), true);
        } catch (RuntimeException unavailable) {
            throw new ProductCatalogUnavailableException();
        }
    }

    private ProductRecord findProduct(String productCodeOrName) {
        if (jdbcTemplate == null) {
            return null;
        }
        String normalized = normalize(productCodeOrName);
        if (normalized.isBlank()) {
            return null;
        }
        List<ProductRecord> products = jdbcTemplate.query(SELECT_COLUMNS + """
                        WHERE UPPER(product_code) = ? OR UPPER(product_name) = ?
                        ORDER BY CASE WHEN UPPER(product_code) = ? THEN 0 ELSE 1 END, product_code
                        LIMIT 2
                        """, this::mapProduct, normalized, normalized, normalized);
        // A unique code wins over another product having the same display name.
        if (!products.isEmpty() && normalize(products.getFirst().code()).equals(normalized)) {
            return products.getFirst();
        }
        if (products.isEmpty()) {
            // Only omit a terminal parenthetical qualifier. No prefix/substring matching:
            // AirPods Pro may identify AirPods Pro（第二代）, but AirPods must not pick Pro/Max.
            // Bind the input literally; '%' and '_' are never search wildcards here.
            products = jdbcTemplate.query(SELECT_COLUMNS + """
                            WHERE UPPER(BTRIM(regexp_replace(product_name,
                                '[[:space:]]*[(（][^()（）]*[)）][[:space:]]*$', ''))) = ?
                            ORDER BY product_code
                            LIMIT 2
                            """, this::mapProduct, normalized);
        }
        if (products.size() > 1) throw new AmbiguousProductException(products);
        return products.isEmpty() ? null : products.getFirst();
    }

    @Override
    public List<String> listMatchingCategories(ProductDiscoveryCriteria criteria) {
        if (jdbcTemplate == null) throw new ProductCatalogUnavailableException();
        List<Object> parameters = new ArrayList<>();
        // This query intentionally has no LIMIT: popularity top-N must not imply a unique category.
        String sql = "SELECT DISTINCT " + DISCOVERY_CATEGORY + " AS category FROM products p WHERE "
                + PRODUCTION_CATALOG_FILTER + " AND " + DISCOVERY_CATEGORY + " <> ''"
                + " AND (CAST(? AS NUMERIC) IS NULL OR p.price <= CAST(? AS NUMERIC))"
                + " __STOCK_FILTER__";
        parameters.add(criteria.maxPrice());
        parameters.add(criteria.maxPrice());
        sql = sql.replace("__STOCK_FILTER__", stockFilter(parameters, criteria.inStockOnly()));
        sql += featureFilter(criteria.features(), parameters) + " ORDER BY category";
        try {
            return jdbcTemplate.query(sql, (rs, row) -> rs.getString("category"), parameters.toArray());
        } catch (RuntimeException e) {
            throw new ProductCatalogUnavailableException(e);
        }
    }

    /** Match the same configured unavailable terms before SQL LIMIT and category inference. */
    private static String stockFilter(List<Object> parameters, boolean inStockOnly) {
        parameters.add(inStockOnly);
        StringBuilder clause = new StringBuilder(" AND (CAST(? AS BOOLEAN) = FALSE OR ("
                + "NULLIF(BTRIM(p.stock), '') IS NOT NULL"
                + " AND LOWER(BTRIM(p.stock)) !~ '^0([.][0]+)?$'");
        for (String term : ProductDiscoverySchema.defaultSchema().unavailableStockTerms()) {
            clause.append(" AND POSITION(CAST(? AS TEXT) IN LOWER(BTRIM(p.stock))) = 0");
            parameters.add(term.toLowerCase(Locale.ROOT));
        }
        return clause.append("))").toString();
    }

    /** Conditions are applied before ordering/limiting and shared with full-catalog category inference. */
    private static String featureFilter(ProductFeatureConstraints features, List<Object> parameters) {
        if (!features.active()) return "";
        StringBuilder where = new StringBuilder(" AND NULLIF(BTRIM(to_jsonb(p)->>'feature_source'), '') IS NOT NULL"
                + " AND NULLIF(to_jsonb(p)->>'features_verified_at', '') IS NOT NULL");
        if (features.maxWeightGrams() != null) {
            where.append(" AND NULLIF(to_jsonb(p)->>'weight_grams', '')::NUMERIC > 0")
                    .append(" AND NULLIF(to_jsonb(p)->>'weight_grams', '')::NUMERIC <= CAST(? AS NUMERIC)");
            parameters.add(features.maxWeightGrams());
        }
        if (features.minBatteryLifeHours() != null) {
            where.append(" AND NULLIF(to_jsonb(p)->>'battery_life_hours', '')::NUMERIC >= CAST(? AS NUMERIC)")
                    .append(" AND to_jsonb(p)->>'battery_life_scenario' = CAST(? AS TEXT)");
            parameters.add(features.minBatteryLifeHours());
            parameters.add(features.batteryLifeScenario());
        }
        if (features.noiseCancelling() != null) {
            where.append(" AND NULLIF(to_jsonb(p)->>'noise_cancelling', '')::BOOLEAN = CAST(? AS BOOLEAN)");
            parameters.add(features.noiseCancelling());
        }
        return where.toString();
    }

    private static final class AmbiguousProductException extends RuntimeException {
        private final String options;

        private AmbiguousProductException(List<ProductRecord> products) {
            super("Multiple catalog products match the supplied name");
            options = products.stream().map(p -> p.name() + "（编码：" + p.code() + "）")
                    .collect(java.util.stream.Collectors.joining("、"));
        }

        private String clarification() {
            return ToolResult.error(AgentErrorCode.TOOL_INVALID_ARGUMENT,
                    "匹配到多款商品，请提供完整商品名称或编码后查询，不能直接确定价格或库存。",
                    "候选包括：" + options);
        }
    }

    private ProductRecord mapProduct(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductRecord(
                rs.getString("product_code"),
                rs.getString("product_name"),
                rs.getBigDecimal("price"),
                rs.getString("stock"),
                rs.getString("spec"),
                rs.getString("color"), mapFeatures(rs));
    }

    private static ProductFeatures mapFeatures(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProductFeatures(rs.getBigDecimal("weight_grams"), rs.getBigDecimal("battery_life_hours"),
                rs.getString("battery_life_scenario"), rs.getObject("noise_cancelling", Boolean.class),
                rs.getString("feature_source"), rs.getString("features_verified_at"));
    }

    private static String formatDetails(ProductRecord product) {
        return String.format("%s\n商品编码：%s\n价格：%s 元\n库存：%s\n规格：%s\n颜色：%s",
                product.name(), product.code(), formatPrice(product.price()), product.stock(),
                valueOrUnknown(product.spec()), valueOrUnknown(product.color()))
                + (product.features().documented() ? "\n结构化参数：" + product.features().evidence() : "");
    }

    private static String formatPrice(BigDecimal price) {
        return price == null ? "未知" : price.stripTrailingZeros().toPlainString();
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ProductRecord(String code, String name, BigDecimal price,
                                 String stock, String spec, String color, ProductFeatures features) {
    }
}
