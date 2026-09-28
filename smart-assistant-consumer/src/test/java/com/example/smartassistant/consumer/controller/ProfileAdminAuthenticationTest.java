package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminProfileService;
import com.example.smartassistant.consumer.service.recommendation.ProfileCleanupService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileAdminAuthenticationTest {
    private static final String SECRET="synthetic-admin-profile-test-key-at-least-thirty-two-bytes";
    private String token(String role,String type) {
        return Jwts.builder().claim("userId",42L).claim("role",role).claim("tokenType",type)
                .id("admin-jti").expiration(new Date(System.currentTimeMillis()+60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    @Test void headerSpoofAndNonAdminTokenCannotReadProfiles() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var redis=mock(StringRedisTemplate.class);
        var profiles=mock(AdminProfileService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ProfileAdminController(profiles,mock(ProfileCleanupService.class)))
                .addFilters(new ProfileAdminAuthentication(SECRET,redis,jdbc)).build();
        mvc.perform(get("/api/admin/profiles").header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/profiles").header("X-User-Role","ROLE_ADMIN")
                .header("Authorization","Bearer "+token("ROLE_USER","access")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/profiles").header("Authorization","Bearer "+token("ROLE_ADMIN","refresh")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles,jdbc,redis);
    }
    @Test void currentDbRoleAndRevocationAreRequired() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var redis=mock(StringRedisTemplate.class);
        var profiles=mock(AdminProfileService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ProfileAdminController(profiles,mock(ProfileCleanupService.class)))
                .addFilters(new ProfileAdminAuthentication(SECRET,redis,jdbc)).build();
        String bearer="Bearer "+token("ROLE_ADMIN","access");
        when(redis.hasKey("blacklist:admin-jti")).thenReturn(false);
        when(jdbc.queryForList("SELECT role FROM users WHERE id=?",String.class,42L)).thenReturn(List.of("ROLE_USER"));
        mvc.perform(get("/api/admin/profiles").header("Authorization",bearer)).andExpect(status().isForbidden());
        when(jdbc.queryForList("SELECT role FROM users WHERE id=?",String.class,42L)).thenReturn(List.of("ROLE_ADMIN"));
        mvc.perform(get("/api/admin/profiles").header("Authorization",bearer)).andExpect(status().isOk());
        verify(profiles).list(null,0,20);
        when(redis.hasKey("blacklist:admin-jti")).thenReturn(true);
        mvc.perform(get("/api/admin/profiles").header("Authorization",bearer)).andExpect(status().isUnauthorized());
    }
}
