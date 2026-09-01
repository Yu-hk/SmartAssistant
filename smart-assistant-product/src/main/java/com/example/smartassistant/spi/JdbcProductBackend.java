/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Reads the live product catalog from PostgreSQL and falls back to the bundled
 * catalog only when the database is not configured or temporarily unavailable.
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
                   COALESCE(to_jsonb(p)->>'colors', to_jsonb(p)->>'color', '') AS color
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
    private final ProductBackend fallback;

    public JdbcProductBackend(JdbcTemplate jdbcTemplate, ProductBackend fallback) {
        this.jdbcTemplate = jdbcTemplate;
        this.fallback = fallback;
    }

    @Override
    public String queryProductInfo(String productCode) {
        ProductRecord product;
        try {
            product = findProduct(productCode);
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 商品精确查询失败，降级到内存目录: {}", e.getMessage());
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
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 库存查询失败，降级到内存目录: {}", e.getMessage());
            return fallback.checkStock(productCode);
        }
        if (product == null) {
            return jdbcTemplate == null
                    ? fallback.checkStock(productCode)
                    : ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode);
        }
        return switch (product.stock()) {
            case "充足" -> product.name() + " 库存充足，下单后 24 小时内发货。";
            case "紧张" -> product.name() + " 库存紧张，建议尽快下单，预计 3-5 天发货。";
            default -> product.name() + " 暂时缺货，补货时间待定。";
        };
    }

    @Override
    public String getPrice(String productCode) {
        ProductRecord product;
        try {
            product = findProduct(productCode);
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 价格查询失败，降级到内存目录: {}", e.getMessage());
            return fallback.getPrice(productCode);
        }
        if (product == null) {
            return jdbcTemplate == null
                    ? fallback.getPrice(productCode)
                    : ToolResult.error(AgentErrorCode.PRODUCT_NOT_FOUND, "未找到商品 " + productCode);
        }
        return String.format("%s 售价 %s 元，支持 3/6/12/24 期免息分期。",
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
            log.warn("[JdbcProduct] 商品搜索失败，降级到内存目录: {}", e.getMessage());
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
            log.warn("[JdbcProduct] 商品类型查询失败，降级到内存目录: {}", e.getMessage());
            return fallback.listProductCategories();
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
                           %s + COALESCE(o.order_count, 0) AS popularity,
                           %s AS rating,
                           %s AS review_count
                      FROM products p
                      LEFT JOIN (
                          SELECT UPPER(product_name) AS product_key, COUNT(*) AS order_count
                            FROM orders
                           WHERE COALESCE(status, '') NOT IN ('已取消', '退款中', '已退款')
                           GROUP BY UPPER(product_name)
                     ) o ON o.product_key = UPPER(p.product_name)
                     WHERE %s
                           AND (CAST(? AS TEXT) = '' OR UPPER(%s) = CAST(? AS TEXT))
                           AND (CAST(? AS NUMERIC) IS NULL OR p.price <= CAST(? AS NUMERIC))
                           AND (CAST(? AS BOOLEAN) = FALSE OR COALESCE(p.stock, '') NOT IN ('缺货', '无货', '售罄'))
                     ORDER BY popularity DESC,
                              CASE p.stock WHEN '充足' THEN 0 WHEN '紧张' THEN 1 ELSE 2 END,
                              p.product_code
                         LIMIT CAST(? AS INTEGER)
                    """).formatted(DISCOVERY_CATEGORY, DISCOVERY_MARKET_PRICE,
                    DISCOVERY_SALES, DISCOVERY_RATING, DISCOVERY_REVIEW_COUNT,
                    PRODUCTION_CATALOG_FILTER, DISCOVERY_CATEGORY);
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
                    rs.getLong("review_count")),
                    category, category, maxPrice, maxPrice, inStockOnly, safeLimit);
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 热度统计查询失败，尝试读取实时商品目录: {}", e.getMessage());
            return listCatalogProducts(safeCriteria);
        }
    }

    private List<ProductSummary> listCatalogProducts(ProductDiscoveryCriteria criteria) {
        int limit = Math.max(1, Math.min(criteria.limit(), SEARCH_LIMIT));
        String category = normalize(criteria.category());
        BigDecimal maxPrice = criteria.maxPrice();
        boolean inStockOnly = criteria.inStockOnly();
        try {
            String sql = ("""
                    SELECT p.product_code, p.product_name, p.price, p.stock, p.spec,
                           %s AS category,
                           %s AS market_price,
                           %s AS popularity,
                           %s AS rating,
                           %s AS review_count
                     FROM products p
                     WHERE %s
                           AND (CAST(? AS TEXT) = '' OR UPPER(%s) = CAST(? AS TEXT))
                           AND (CAST(? AS NUMERIC) IS NULL OR p.price <= CAST(? AS NUMERIC))
                           AND (CAST(? AS BOOLEAN) = FALSE OR COALESCE(p.stock, '') NOT IN ('缺货', '无货', '售罄'))
                     ORDER BY popularity DESC,
                              CASE p.stock WHEN '充足' THEN 0 WHEN '紧张' THEN 1 ELSE 2 END,
                              p.product_code
                         LIMIT CAST(? AS INTEGER)
                    """).formatted(DISCOVERY_CATEGORY, DISCOVERY_MARKET_PRICE,
                    DISCOVERY_SALES, DISCOVERY_RATING, DISCOVERY_REVIEW_COUNT,
                    PRODUCTION_CATALOG_FILTER, DISCOVERY_CATEGORY);
            return jdbcTemplate.query(sql, (rs, rowNum) -> new ProductSummary(
                    rs.getString("product_code"), rs.getString("product_name"),
                    rs.getBigDecimal("price"), rs.getString("stock"),
                    rs.getString("spec"), rs.getLong("popularity"),
                    rs.getString("category"), rs.getBigDecimal("market_price"),
                    rs.getBigDecimal("rating"), rs.getLong("review_count")),
                    category, category, maxPrice, maxPrice, inStockOnly, limit);
        } catch (RuntimeException e) {
            log.warn("[JdbcProduct] 实时商品目录查询失败，降级到内存目录: {}", e.getMessage());
            return fallback.listPopularProducts(criteria);
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
                        LIMIT 1
                        """, this::mapProduct, normalized, normalized);
        return products.isEmpty() ? null : products.getFirst();
    }

    private ProductRecord mapProduct(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductRecord(
                rs.getString("product_code"),
                rs.getString("product_name"),
                rs.getBigDecimal("price"),
                rs.getString("stock"),
                rs.getString("spec"),
                rs.getString("color"));
    }

    private static String formatDetails(ProductRecord product) {
        return String.format("%s\n商品编码：%s\n价格：%s 元\n库存：%s\n规格：%s\n颜色：%s",
                product.name(), product.code(), formatPrice(product.price()), product.stock(),
                valueOrUnknown(product.spec()), valueOrUnknown(product.color()));
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
                                 String stock, String spec, String color) {
    }
}
