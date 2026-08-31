package com.example.smartassistant.spi;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class JdbcProductBackendTest {

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
        assertThat(backend.queryProductInfo("MACBOOK-AIR-M3"))
                .contains("商品编码：MACBOOK-AIR-M3")
                .contains("颜色：午夜色");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackWhenDatabaseIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        JdbcProductBackend backend = new JdbcProductBackend(jdbc, new InMemoryProductBackend());

        assertThat(backend.queryProductInfo("MACBOOK-AIR-M3"))
                .contains("MacBook Air M3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsPopularProductsFromOrderSignals() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = productRow();
        when(row.getLong("popularity")).thenReturn(7L);
        when(row.getString("category")).thenReturn("笔记本电脑");
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
                });

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("LOAD-PROD-%")
                .contains("E2E-PROD-%")
                .contains("category")
                .doesNotContain("WHEREUPPER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsLiveCatalogWhenOrderSignalsAreUnavailable() throws Exception {
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

        assertThat(backend.listPopularProducts(5))
                .singleElement()
                .satisfies(product -> {
                    assertThat(product.name()).isEqualTo("MacBook Air M3");
                    assertThat(product.popularity()).isZero();
                });
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
