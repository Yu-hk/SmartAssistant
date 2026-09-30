package com.example.smartassistant.spi;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.ResultSet;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class JdbcProductIdentityTest {
    @Test @SuppressWarnings("unchecked") void onlyReviewedMetadataEntersIdentitySnapshot() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var row = mock(ResultSet.class);
        when(row.getString("product_code")).thenReturn("AP"); when(row.getString("product_name")).thenReturn("AirPods Pro");
        when(row.getString("aliases")).thenReturn("苹果耳机\u001f苹果降噪耳机");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> List.of(((RowMapper<?>)call.getArgument(1)).mapRow(row, 0)));
        when(row.getString("identity_metadata")).thenReturn("{\"brand\":\"Apple\",\"family\":\"AirPods\"}");
        assertThat(new JdbcProductBackend(jdbc).listProductIdentities().getFirst().brand()).isEmpty();
        when(row.getString("identity_metadata")).thenReturn("{\"brand\":\"Apple\",\"family\":\"AirPods\",\"source\":\"catalog\",\"reviewedAt\":\"2026-09-30\"}");
        var identity = new JdbcProductBackend(jdbc).listProductIdentities().getFirst();
        assertThat(identity.brand()).isEqualTo("Apple"); assertThat(identity.family()).isEqualTo("AirPods");
        assertThat(identity.aliases()).hasSize(2);
    }
}
