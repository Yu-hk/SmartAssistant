/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.model.dto;

/** Optional refresh token supplied during logout for complete revocation. */
public record LogoutRequest(String refreshToken) {
}
