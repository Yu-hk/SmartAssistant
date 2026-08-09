package com.example.smartassistant.user.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authResponseIncludesRole() throws Exception {
        AuthResponse response = new AuthResponse(
                "access",
                "refresh",
                1L,
                "alice",
                "alice@example.com",
                "ROLE_ADMIN");

        String json = objectMapper.writeValueAsString(response);

        assertTrue(json.contains("\"role\":\"ROLE_ADMIN\""));
        assertEquals("ROLE_ADMIN", response.getRole());
    }

    @Test
    void currentUserResponseCannotSerializePassword() throws Exception {
        CurrentUserResponse response = new CurrentUserResponse(
                1L,
                "alice",
                "alice@example.com",
                "ROLE_USER");

        String json = objectMapper.writeValueAsString(response);

        assertTrue(json.contains("\"userId\":1"));
        assertFalse(json.contains("password"));
    }
}
