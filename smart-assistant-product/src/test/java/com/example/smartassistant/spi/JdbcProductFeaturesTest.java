package com.example.smartassistant.spi;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdbcProductFeaturesTest {
    private ProductBackend.ProductDiscoveryCriteria criteria() {
        return new ProductBackend.ProductDiscoveryCriteria("耳机", "", new BigDecimal("1000"), true, 1,
                new ProductFeatureConstraints(new BigDecimal("300"), new BigDecimal("25"), "audio_anc_on", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sqlFiltersFeaturesBeforeLimitAndMapsStructuredEvidence() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var rs = mock(ResultSet.class);
        when(rs.getString("product_code")).thenReturn("FEATURE-TEST-A");
        when(rs.getString("product_name")).thenReturn("虚构耳机");
        when(rs.getBigDecimal("weight_grams")).thenReturn(new BigDecimal("250"));
        when(rs.getBigDecimal("battery_life_hours")).thenReturn(new BigDecimal("30"));
        when(rs.getString("battery_life_scenario")).thenReturn("audio_anc_on");
        when(rs.getObject("noise_cancelling", Boolean.class)).thenReturn(true);
        when(rs.getString("feature_source")).thenReturn("synthetic-test-fixture");
        when(rs.getString("features_verified_at")).thenReturn("2026-09-14T00:00:00Z");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
        var result = new JdbcProductBackend(jdbc).listPopularProducts(criteria());
        assertThat(result.getFirst().features().weightGrams()).isEqualByComparingTo("250");
        assertThat(result.getFirst().features().noiseCancelling()).isTrue();
        assertThat(criteria().features().matches(result.getFirst().features())).isTrue();
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        String conditions = sql.getValue().substring(sql.getValue().indexOf("WHERE"), sql.getValue().indexOf("ORDER BY"));
        assertThat(conditions).contains("weight_grams", "battery_life_hours", "battery_life_scenario", "noise_cancelling", "feature_source", "features_verified_at");
        assertThat(args.getValue()).containsExactly("耳机", "耳机", new BigDecimal("1000"), new BigDecimal("1000"), true,
                new BigDecimal("300"), new BigDecimal("25"), "audio_anc_on", true, 1);
        assertThat(sql.getValue()).doesNotContain("__FEATURE_FILTER__");
    }

    @Test
    @SuppressWarnings("unchecked")
    void categoryInferenceUsesFullCatalogDistinctQueryNotPopularityTopN() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("耳机", "其他音频设备"));
        assertThat(new JdbcProductBackend(jdbc).listMatchingCategories(criteria())).hasSize(2);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("SELECT DISTINCT", "weight_grams", "battery_life_scenario", "p.price <=")
                .doesNotContain("LIMIT", "popularity", "sales_30d");
    }

    @Test
    void missingDatabaseCannotBeMistakenForNoFeatureMatches() {
        assertThatThrownBy(() -> new JdbcProductBackend(null).listMatchingCategories(criteria()))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }
}
