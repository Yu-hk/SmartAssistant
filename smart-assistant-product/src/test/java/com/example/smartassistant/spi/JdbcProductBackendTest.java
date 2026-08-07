package com.example.smartassistant.spi;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcProductBackendTest {

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
