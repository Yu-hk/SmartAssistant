package com.example.smartassistant.spi;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcProductBackendTest {

    @Test
    void resolvesProductCodeFromNaturalLanguageAndIncludesLatestWarehouseInventory() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                contains("FROM products WHERE UPPER(product_code)"),
                any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "product_code", "E2E-PROD-0001",
                        "product_name", "Aurora 无线降噪耳机",
                        "price", new BigDecimal("1299.00"),
                        "stock", "充足",
                        "spec", "蓝牙 5.4 / 主动降噪",
                        "color", "曜石黑")));
        when(jdbcTemplate.queryForList(
                contains("FROM inventory_snapshots"),
                any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "warehouse", "杭州仓",
                        "on_hand", 100,
                        "reserved", 12,
                        "quality_hold", 2,
                        "available", 86)));

        JdbcProductBackend backend = new JdbcProductBackend(jdbcTemplate);

        String result = backend.queryProductInfo(
                "Aurora 无线降噪耳机 E2E-PROD-0001 现在多少钱，杭州仓还有多少可售库存？");

        assertThat(result)
                .contains("E2E-PROD-0001")
                .contains("Aurora 无线降噪耳机")
                .contains("¥1299.00")
                .contains("杭州仓")
                .contains("可售 86");
    }
}
