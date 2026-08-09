/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.audit;

/** Internal HTTP headers used to propagate measured token usage between services. */
public final class TokenUsageHeaders {

    public static final String PROMPT_TOKENS = "X-Agent-Prompt-Tokens";
    public static final String COMPLETION_TOKENS = "X-Agent-Completion-Tokens";
    public static final String TOTAL_TOKENS = "X-Agent-Total-Tokens";

    private TokenUsageHeaders() {
    }
}
