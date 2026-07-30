package com.example.smartassistant.user.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSerializationTest {

    @Test
    void neverSerializesPasswordHash() throws Exception {
        User user = new User();
        user.setId(6L);
        user.setUsername("e2e-user");
        user.setPassword("$2a$10$secret-hash");

        String json = new ObjectMapper().writeValueAsString(user);

        assertTrue(json.contains("\"username\":\"e2e-user\""));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("secret-hash"));
    }
}
