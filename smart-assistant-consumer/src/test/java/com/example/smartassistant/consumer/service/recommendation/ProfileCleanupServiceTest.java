package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import java.util.Map;
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
    @Test void adminMustMatchCurrentRoleAndTargetBeforeAuditedJob() {
        var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var service=new ProfileCleanupService(jdbc,manager,mock(ProfileRedisCleanup.class),true);
        UUID action=UUID.randomUUID(),job=UUID.randomUUID();
        when(jdbc.queryForList("SELECT role FROM users WHERE id=? FOR UPDATE",42L))
                .thenReturn(List.of(Map.of("role","ROLE_USER")),List.of(Map.of("role","ROLE_ADMIN")),
                        List.of(Map.of("role","ROLE_ADMIN")));
        assertThrows(SecurityException.class,()->service.requestForAdmin(42L,99L,"qa-user","USER_REQUEST",action));
        when(jdbc.queryForList("SELECT username,role FROM users WHERE id=? FOR UPDATE",99L))
                .thenReturn(List.of(Map.of("username","someone-else","role","ROLE_USER")),
                        List.of(Map.of("username","qa-user","role","ROLE_USER")));
        assertThrows(IllegalArgumentException.class,()->service.requestForAdmin(42L,99L,"qa-user","USER_REQUEST",action));
        when(jdbc.queryForList("SELECT job_id FROM profile_cleanup_job WHERE user_id=? AND idempotency_key=?",UUID.class,99L,action))
                .thenReturn(List.of(job));
        assertEquals(job,service.requestForAdmin(42L,99L,"qa-user","USER_REQUEST",action));
        verify(jdbc).update("INSERT INTO profile_admin_cleanup_audit(action_id,actor_user_id,target_user_id,reason_code,job_id) VALUES (?,?,?,?,?)",
                action,42L,99L,"USER_REQUEST",job);
    }
}
