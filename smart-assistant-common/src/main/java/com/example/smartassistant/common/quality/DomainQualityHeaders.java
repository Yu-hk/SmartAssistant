/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.quality;

/** HTTP headers used to propagate domain-owned quality decisions to Router. */
public final class DomainQualityHeaders {

    public static final String STATUS = "X-Agent-Quality-Status";
    public static final String SCORE = "X-Agent-Quality-Score";
    public static final String REASON_CODES = "X-Agent-Quality-Reasons";

    private DomainQualityHeaders() {
    }
}
