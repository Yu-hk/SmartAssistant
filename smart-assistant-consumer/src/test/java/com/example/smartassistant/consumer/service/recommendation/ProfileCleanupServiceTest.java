package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileCleanupServiceTest {
    @Test void disabledByDefaultMeansNoStorageAccessOrJobs() {
        var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);var redis=mock(ProfileRedisCleanup.class);
        var service=new ProfileCleanupService(jdbc,manager,redis,false);
        assertThrows(IllegalStateException.class,()->service.request(42L,UUID.randomUUID()));
        assertFalse(service.runNext());service.poll();assertTrue(service.status(42L,UUID.randomUUID()).isEmpty());
        verifyNoInteractions(jdbc,manager,redis);
    }
    @Test void invalidIdentityNeverTouchesStorage() {
        var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);var redis=mock(ProfileRedisCleanup.class);
        var service=new ProfileCleanupService(jdbc,manager,redis,true);
        assertThrows(IllegalArgumentException.class,()->service.request(null,UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->service.request(-1L,UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->service.request(42L,null));
        assertTrue(service.status(0L,UUID.randomUUID()).isEmpty());verifyNoInteractions(jdbc,manager,redis);
    }
}
