package com.example.smartassistant.consumer.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfilePrivacyAuthenticationTest {
    final String secret="synthetic-privacy-auth-test-key-at-least-thirty-two-bytes";
    String token(String type,Date expiry,String signing) {
        return Jwts.builder().claim("userId",42L).claim("tokenType",type).id("fixture-jti").expiration(expiry)
            .signWith(Keys.hmacShaKeyFor(signing.getBytes(StandardCharsets.UTF_8))).compact();
    }
    @Test void directSpoofingRefreshExpiredAndWrongSignatureCannotReachService() throws Exception {
        var service=mock(com.example.smartassistant.consumer.service.recommendation.ProfileCleanupService.class);
        var redis=mock(StringRedisTemplate.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ProfilePrivacyController(service))
            .addFilters(new ProfilePrivacyAuthentication(secret,redis)).build();
        mvc.perform(get("/api/privacy/profile").header("X-User-Id","42")).andExpect(status().isUnauthorized());
        for(String bad:List.of(token("refresh",new Date(System.currentTimeMillis()+60000),secret),
                token("access",new Date(0),secret),token("access",new Date(System.currentTimeMillis()+60000),secret+"wrong")))
            mvc.perform(post("/api/privacy/profile/deletions").header("Authorization","Bearer "+bad).header("X-User-Id","43"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service,redis);
    }
    @Test void actualTokenOwnerWinsAndRevocationOrRedisFailureFailsClosed() throws Exception {
        var service=mock(com.example.smartassistant.consumer.service.recommendation.ProfileCleanupService.class);
        var redis=mock(StringRedisTemplate.class);when(redis.hasKey("blacklist:fixture-jti")).thenReturn(false);
        var mvc=MockMvcBuilders.standaloneSetup(new ProfilePrivacyController(service))
            .addFilters(new ProfilePrivacyAuthentication(secret,redis)).build();
        String bearer="Bearer "+token("access",new Date(System.currentTimeMillis()+60000),secret);
        when(service.overview(42L)).thenReturn(Map.of("available",true));
        mvc.perform(get("/api/privacy/profile").header("Authorization",bearer).header("X-User-Id","43"))
            .andExpect(status().isOk());verify(service).overview(42L);verify(service,never()).overview(43L);
        when(redis.hasKey("blacklist:fixture-jti")).thenReturn(true);
        mvc.perform(get("/api/privacy/profile").header("Authorization",bearer)).andExpect(status().isUnauthorized());
        when(redis.hasKey("blacklist:fixture-jti")).thenThrow(new IllegalStateException("internal secret"));
        var response=mvc.perform(get("/api/privacy/profile").header("Authorization",bearer)).andExpect(status().isServiceUnavailable()).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("secret"));
        verifyNoMoreInteractions(service);
    }
    @Test void missingKeyOnlyBlocksPrivacyRoutes() throws Exception {
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var filter=new ProfilePrivacyAuthentication("",mock(StringRedisTemplate.class));
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/privacy/profile");
        var chain=mock(jakarta.servlet.FilterChain.class);filter.doFilter(request,response,chain);
        assertEquals(503,response.getStatus());verifyNoInteractions(chain);
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET","/api/session"),response,chain);
        verify(chain).doFilter(any(),any());
    }
}
