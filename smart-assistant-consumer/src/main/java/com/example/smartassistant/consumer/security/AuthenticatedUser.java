package com.example.smartassistant.consumer.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Identity asserted by the gateway after JWT validation.
 *
 * <p>Business endpoints must never fall back to a user id supplied in a request
 * body or query parameter. The gateway removes untrusted identity headers before
 * adding these values.</p>
 */
public record AuthenticatedUser(long userId, String username, String role) {

    public static AuthenticatedUser require(String rawUserId, String username, String role) {
        if (rawUserId == null || rawUserId.isBlank()) {
            throw unauthorized();
        }
        try {
            long parsedUserId = Long.parseLong(rawUserId);
            if (parsedUserId <= 0) {
                throw unauthorized();
            }
            String normalizedRole = role == null || role.isBlank() ? "ROLE_USER" : role.trim();
            return new AuthenticatedUser(parsedUserId, username, normalizedRole);
        } catch (NumberFormatException exception) {
            throw unauthorized();
        }
    }

    public boolean isAdmin() {
        return "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authenticated user");
    }
}
