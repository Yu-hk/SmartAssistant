package com.example.smartassistant.user.service;

import com.example.smartassistant.user.mapper.UserMapper;
import com.example.smartassistant.user.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceTest {

    @Test
    void usesStoredRole() {
        UserMapper mapper = mock(UserMapper.class);
        User user = user("admin", "ROLE_ADMIN");
        when(mapper.findByUsername("admin")).thenReturn(user);

        UserDetails details = new CustomUserDetailsService(mapper).loadUserByUsername("admin");

        assertEquals("ROLE_ADMIN", details.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void fallsBackToUserRoleForLegacyRows() {
        UserMapper mapper = mock(UserMapper.class);
        User user = user("legacy", null);
        when(mapper.findByUsername("legacy")).thenReturn(user);

        UserDetails details = new CustomUserDetailsService(mapper).loadUserByUsername("legacy");

        assertEquals("ROLE_USER", details.getAuthorities().iterator().next().getAuthority());
    }

    private User user(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("password-hash");
        user.setRole(role);
        return user;
    }
}
