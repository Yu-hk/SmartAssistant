/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.quality;

/** Plain-text Agent response accompanied by a domain-owned quality decision. */
public record DomainAgentResponse(String answer, DomainQualityResult quality) {

    public DomainAgentResponse {
        quality = quality != null ? quality : DomainQualityResult.unknown();
    }

    public static DomainAgentResponse of(String answer, DomainQualityResult quality) {
        return new DomainAgentResponse(answer, quality);
    }
}
