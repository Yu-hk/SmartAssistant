package com.example.smartassistant.user.oauth;

public record ExternalOAuthIdentity(
        OAuthProvider provider,
        String subject,
        String unionId,
        String displayName,
        String email,
        String avatarUrl) {
}
