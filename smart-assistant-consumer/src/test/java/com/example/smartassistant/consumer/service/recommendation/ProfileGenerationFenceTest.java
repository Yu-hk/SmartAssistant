package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfileGenerationFenceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ProfileGenerationFence fence=new ProfileGenerationFence(jdbc,mock(PlatformTransactionManager.class));
    @Test void onlyMissingControlRowDefaultsToLegacyZero() {
        when(jdbc.queryForMap(anyString(),eq(42L))).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertEquals(0,fence.capture(42L));
    }
    @Test void databaseFailureDoesNotAuthorizeLegacyGeneration() {
        when(jdbc.queryForMap(anyString(),eq(42L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->fence.capture(42L));
    }
    @Test void malformedDisabledAndNegativeRowsFailClosed() {
        for (Map<String,Object> row : java.util.List.<Map<String,Object>>of(Map.of(),
                Map.of("generation",0L,"analysis_enabled",false),
                Map.of("generation",-1L,"analysis_enabled",true))) {
            when(jdbc.queryForMap(anyString(),eq(42L))).thenReturn(row);
            assertThrows(ProfileGenerationFence.Rejected.class,()->fence.capture(42L));
        }
    }
    @Test void mismatchedGenerationCannotBeRecapturedByValidation() {
        when(jdbc.queryForMap(anyString(),eq(42L))).thenReturn(Map.of("generation",3L,"analysis_enabled",true));
        assertThrows(ProfileGenerationFence.Rejected.class,()->fence.requireCurrent(42L,2));
    }
    @Test void invalidIdentityNeverTouchesDatabase() {
        assertThrows(IllegalArgumentException.class,()->fence.capture(null));
        assertThrows(IllegalArgumentException.class,()->fence.capture(-1L));
        verifyNoInteractions(jdbc);
    }
    @Test void controlLookupIsOwnerScoped() {
        when(jdbc.queryForMap(anyString(),eq(42L))).thenReturn(Map.of("generation",5L,"analysis_enabled",false));
        when(jdbc.queryForMap(anyString(),eq(43L))).thenReturn(Map.of("generation",1L,"analysis_enabled",true));
        assertThrows(ProfileGenerationFence.Rejected.class,()->fence.capture(42L));
        assertEquals(1,fence.capture(43L));
    }
}
