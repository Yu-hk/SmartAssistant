package com.example.smartassistant.consumer.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatedUserTest {

    @Test
    void acceptsPositiveGatewayUserIdAndNormalizesMissingRole() {
        AuthenticatedUser user = AuthenticatedUser.require("42", "alice", null);

        assertEquals(42L, user.userId());
        assertEquals("ROLE_USER", user.role());
        assertFalse(user.isAdmin());
    }

    @Test
    void recognizesAdministratorRole() {
        assertTrue(AuthenticatedUser.require("7", "admin", "ROLE_ADMIN").isAdmin());
    }

    @Test
    void rejectsMissingOrInvalidIdentity() {
        assertThrows(ResponseStatusException.class,
                () -> AuthenticatedUser.require(null, null, "ROLE_USER"));
        assertThrows(ResponseStatusException.class,
                () -> AuthenticatedUser.require("not-a-number", null, "ROLE_USER"));
        assertThrows(ResponseStatusException.class,
                () -> AuthenticatedUser.require("0", null, "ROLE_USER"));
    }
}
