/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.model.dto;

/**
 * Safe projection of the currently authenticated user.
 * Deliberately excludes password and persistence metadata.
 */
public record CurrentUserResponse(
        Long userId,
        String username,
        String email,
        String role) {
}
