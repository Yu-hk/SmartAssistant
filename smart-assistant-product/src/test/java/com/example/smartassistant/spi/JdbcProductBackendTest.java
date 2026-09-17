package com.example.smartassistant.spi;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class JdbcProductBackendTest {

    @Test
    @SuppressWarnings("unchecked")
    void priceAndStockNeverInventPaymentOrDeliveryPolicies() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = airPodsRow("AIRPODS-PRO", "AirPods Pro（第二代）");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0)));
        JdbcProductBackend backend = new JdbcProductBackend(jdbc, mock(ProductBackend.class));
        assertThat(backend.getPrice("AIRPODS-PRO")).isEqualTo("AirPods Pro（第二代） 售价 1999 元。");
        for (String stock : new String[]{"充足", "紧张", "缺货", "未知", "", null}) {
            when(row.getString("stock")).thenReturn(stock);
            String answer = backend.checkStock("AIRPODS-PRO");
            assertThat(answer).doesNotContain("发货", "免息", "分期", "尽快下单", "补货时间");
            if (stock == null || stock.isBlank() || "未知".equals(stock)) {
                assertThat(answer).contains("尚未确认").doesNotContain("缺货");
            } else {
                assertThat(answer).contains(stock);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesUniqueParentheticalShortNameForInfoPriceAndStock() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductBackend fallback = mock(ProductBackend.class);
        ResultSet row = airPodsRow("AIRPODS-PRO", "AirPods Pro（第二代）");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (!sql.contains("regexp_replace")) return List.of();
                    assertThat(sql).contains("[(（][^()（）]*[)）]", "= ?", "LIMIT 2")
                            .doesNotContain("LIKE");
                    assertThat((String) invocation.getArgument(2)).isEqualTo("AIRPODS PRO");
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        JdbcProductBackend backend = new JdbcProductBackend(jdbc, fallback);
        assertThat(backend.queryProductInfo("  AirPods Pro  "))
                .contains("AirPods Pro（第二代）", "AIRPODS-PRO", "1999", "库存：充足");
        assertThat(backend.getPrice("AirPods Pro")).contains("1999", "AirPods Pro（第二代）");
        assertThat(backend.checkStock("AirPods Pro")).contains("库存充足", "AirPods Pro（第二代）");
        org.mockito.Mockito.verifyNoInteractions(fallback);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambiguousShortNamesAskForVersionAndNeverFallBackToAnArbitraryPrice() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductBackend fallback = mock(ProductBackend.class);
        ResultSet second = airPodsRow("AIRPODS-PRO-2", "AirPods Pro（第二代）");
        ResultSet third = airPodsRow("AIRPODS-PRO-3", "AirPods Pro(第三代)");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    if (!((String) invocation.getArgument(0)).contains("regexp_replace")) return List.of();
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(second, 0), mapper.mapRow(third, 1));
                });
        JdbcProductBackend backend = new JdbcProductBackend(jdbc, fallback);
        for (String response : List.of(backend.queryProductInfo("AirPods Pro"),
                backend.getPrice("AirPods Pro"), backend.checkStock("AirPods Pro"))) {
            assertThat(response).contains("TOOL_INVALID_ARGUMENT", "匹配到多款商品", "AIRPODS-PRO-2", "AIRPODS-PRO-3")
                    .doesNotContain("PRODUCT_NOT_FOUND", "1999", "库存充足");
        }
        org.mockito.Mockito.verifyNoInteractions(fallback);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactCodeWinsAndExactNamesDoNotFallThroughToAliases() throws Exception {
        for (String query : List.of("AIRPODS-PRO", "AirPods Pro（第二代）")) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ResultSet row = airPodsRow("AIRPODS-PRO", "AirPods Pro（第二代）");
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenAnswer(invocation -> {
                        assertThat((String) invocation.getArgument(0)).doesNotContain("regexp_replace");
                        RowMapper<Object> mapper = invocation.getArgument(1);
                        return List.of(mapper.mapRow(row, 0));
                    });
            assertThat(new JdbcProductBackend(jdbc, mock(ProductBackend.class)).queryProductInfo(query))
                    .contains("1999", "AIRPODS-PRO");
            verify(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactCodeWinsOverDisplayNameCollisionButDuplicateNamesRequireClarification() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductBackend fallback = mock(ProductBackend.class);
        ResultSet first = airPodsRow("AIRPODS-PRO", "AirPods Pro（第二代）");
        ResultSet other = airPodsRow("ANOTHER-CODE", "AIRPODS-PRO");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    assertThat((String) invocation.getArgument(0))
                            .contains("ORDER BY CASE WHEN UPPER(product_code) = ? THEN 0 ELSE 1 END")
                            .doesNotContain("regexp_replace");
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(first, 0), mapper.mapRow(other, 1));
                });
        JdbcProductBackend backend = new JdbcProductBackend(jdbc, fallback);
        assertThat(backend.queryProductInfo("AIRPODS-PRO")).contains("价格：1999").doesNotContain("ANOTHER-CODE");
        when(other.getString("product_name")).thenReturn("AirPods Pro（第二代）");
        assertThat(backend.queryProductInfo("AirPods Pro（第二代）")).contains("匹配到多款商品").doesNotContain("价格：1999");
        org.mockito.Mockito.verifyNoInteractions(fallback);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shortNamesAreLiteralAndMissingProductsDoNotUseMockCatalog() {
        for (String input : List.of("AirPods", "AirPods%", "AIRPODS_PRO", "AirPods Pro（不存在的版本）")) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ProductBackend fallback = mock(ProductBackend.class);
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
            assertThat(new JdbcProductBackend(jdbc, fallback).queryProductInfo(input)).contains("PRODUCT_NOT_FOUND");
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
            org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(2))
                    .query(sql.capture(), any(RowMapper.class), arguments.capture());
            assertThat(sql.getAllValues().get(1)).contains("= ?", "LIMIT 2").doesNotContain("LIKE");
            assertThat(arguments.getAllValues().get(1)).containsExactly(input.toUpperCase(java.util.Locale.ROOT));
            org.mockito.Mockito.verifyNoInteractions(fallback);
        }
    }

    private static ResultSet airPodsRow(String code, String name) throws Exception {
        ResultSet row = productRow();
        when(row.getString("product_code")).thenReturn(code);
        when(row.getString("product_name")).thenReturn(name);
        when(row.getBigDecimal("price")).thenReturn(new BigDecimal("1999.00"));
        when(row.getString("stock")).thenReturn("充足");
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsDistinctProductCategoriesFromLiveCatalog() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("category")).thenReturn("平板电脑");
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<String> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        JdbcProductBackend backend = new JdbcProductBackend(jdbc, new InMemoryProductBackend());

        assertThat(backend.listProductCategories()).containsExactly("平板电脑");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("SELECT DISTINCT").contains("category");
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsLiveCatalogDataForNaturalLanguageSearch() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = productRow();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        JdbcProductBackend backend = new JdbcProductBackend(jdbc, new InMemoryProductBackend());

        assertThat(backend.searchProduct("请问 MacBook Air M3 还有货吗"))
                .contains("MacBook Air M3")
                .contains("¥8999");
        ArgumentCaptor<String> searchSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(searchSql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(searchSql.getValue())
                .contains("AND UPPER(p.product_code)")
                .doesNotContain("ANDUPPER");
        assertThat(backend.queryProductInfo("MACBOOK-AIR-M3"))
                .contains("商品编码：MACBOOK-AIR-M3")
                .contains("颜色：午夜色");
    }

    @Test
    @SuppressWarnings("unchecked")
    void neverReturnsDemoProductsWhenDatabaseIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        JdbcProductBackend backend = new JdbcProductBackend(jdbc, new InMemoryProductBackend());

        assertThat(backend.queryProductInfo("MACBOOK-AIR-M3"))
                .contains("商品目录暂时不可用", "TOOL_EXECUTION_ERROR")
                .doesNotContain("MacBook Air M3", "8999");
        assertThat(backend.getPrice("MACBOOK-AIR-M3")).contains("商品目录暂时不可用");
        assertThat(backend.checkStock("MACBOOK-AIR-M3")).contains("商品目录暂时不可用");
        assertThat(backend.searchProduct("MacBook")).contains("商品目录暂时不可用");
        assertThatThrownBy(() -> backend.listPopularProducts(5))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsPopularProductsFromSales30dWithoutDoubleCountingOrders() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = productRow();
        when(row.getLong("popularity")).thenReturn(7L);
        when(row.getString("category")).thenReturn("笔记本电脑");
        when(row.getBigDecimal("market_price")).thenReturn(new BigDecimal("9999.00"));
        when(row.getBigDecimal("rating")).thenReturn(new BigDecimal("4.8"));
        when(row.getLong("review_count")).thenReturn(1280L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        JdbcProductBackend backend = new JdbcProductBackend(jdbc, new InMemoryProductBackend());

        assertThat(backend.listPopularProducts(5))
                .singleElement()
                .satisfies(product -> {
                    assertThat(product.name()).isEqualTo("MacBook Air M3");
                    assertThat(product.popularity()).isEqualTo(7L);
                    assertThat(product.category()).isEqualTo("笔记本电脑");
                    assertThat(product.marketPrice()).isEqualByComparingTo("9999.00");
                    assertThat(product.rating()).isEqualByComparingTo("4.8");
                    assertThat(product.reviewCount()).isEqualTo(1280L);
                });

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("LOAD-PROD-%")
                .contains("E2E-PROD-%")
                .contains("category")
                .contains("sales_30d")
                .doesNotContain("FROM orders", "order_count", "LEFT JOIN")
                .contains("review_count")
                .contains("UPPER(COALESCE(to_jsonb(p)->>'category', '')) = CAST(? AS TEXT)")
                .contains("CAST(? AS NUMERIC) IS NULL")
                .contains("p.price <= CAST(? AS NUMERIC)")
                .contains("CAST(? AS BOOLEAN) = FALSE")
                .contains("LIMIT CAST(? AS INTEGER)")
                .doesNotContain("WHEREUPPER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogQueryFailureDoesNotRetryAgainstDemoOrReturnAnEmptyList() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = productRow();
        AtomicInteger calls = new AtomicInteger();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("orders table unavailable");
                    }
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        JdbcProductBackend backend = new JdbcProductBackend(jdbc, new InMemoryProductBackend());

        assertThatThrownBy(() -> backend.listPopularProducts(5))
                .isInstanceOf(ProductCatalogUnavailableException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void missingDatabaseAndUnavailableBackendNeverPretendToBeEmptyCatalogs() {
        ProductBackend fallback = mock(ProductBackend.class);
        var backend = new JdbcProductBackend(null, fallback);
        assertThatThrownBy(backend::listProductCategories).isInstanceOf(ProductCatalogUnavailableException.class);
        assertThatThrownBy(() -> backend.listPopularProducts(5)).isInstanceOf(ProductCatalogUnavailableException.class);
        assertThat(backend.queryProductInfo("AIRPODS-PRO")).contains("商品目录暂时不可用");
        org.mockito.Mockito.verifyNoInteractions(fallback);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pushesBudgetCategoryAndStockConstraintsIntoSql() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = productRow();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        JdbcProductBackend backend = new JdbcProductBackend(jdbc, new InMemoryProductBackend());

        backend.listPopularProducts(new ProductBackend.ProductDiscoveryCriteria(
                "笔记本电脑", "预算9000元，只看有货", new BigDecimal("9000"), true, 5));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), arguments.capture());
        assertThat(sql.getValue())
                .contains("UPPER(COALESCE(to_jsonb(p)->>'category', '')) = CAST(? AS TEXT)")
                .contains("CAST(? AS NUMERIC) IS NULL")
                .contains("p.price <= CAST(? AS NUMERIC)")
                .contains("CAST(? AS BOOLEAN) = FALSE")
                .contains("p.stock")
                .contains("'售罄'");
        assertThat(arguments.getValue())
                .contains("笔记本电脑", new BigDecimal("9000"), true, 5);
    }

    private static ResultSet productRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("product_code")).thenReturn("MACBOOK-AIR-M3");
        when(row.getString("product_name")).thenReturn("MacBook Air M3");
        when(row.getBigDecimal("price")).thenReturn(new BigDecimal("8999.00"));
        when(row.getString("stock")).thenReturn("紧张");
        when(row.getString("spec")).thenReturn("M3 芯片");
        when(row.getString("color")).thenReturn("午夜色");
        return row;
    }
}
