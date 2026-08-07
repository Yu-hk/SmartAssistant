package com.example.smartassistant.user.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

    @Test
    void acceptsProductionOriginAndIndividualRequestHeaders() {
        SecurityConfig securityConfig = new SecurityConfig(
                mock(JwtAuthenticationFilter.class),
                mock(UserDetailsService.class));

        ReflectionTestUtils.setField(securityConfig, "allowedOrigins",
                List.of("https://xiaoyuai.cloud", "https://www.xiaoyuai.cloud"));
        ReflectionTestUtils.setField(securityConfig, "allowedMethods",
                List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        ReflectionTestUtils.setField(securityConfig, "allowedHeaders",
                List.of("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin"));
        ReflectionTestUtils.setField(securityConfig, "allowCredentials", true);

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/auth/login"));

        assertNotNull(cors);
        assertEquals("https://xiaoyuai.cloud", cors.checkOrigin("https://xiaoyuai.cloud"));
        assertEquals(List.of("content-type", "authorization"),
                cors.checkHeaders(List.of("content-type", "authorization")));
    }
}
