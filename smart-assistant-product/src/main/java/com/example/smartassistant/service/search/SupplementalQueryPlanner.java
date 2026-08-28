/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

/** Plans one evidence-gap query for the next bounded retrieval attempt. */
@FunctionalInterface
public interface SupplementalQueryPlanner {

    String plan(String originalQuery, String previousQuery, String evidenceSummary, int nextAttempt);
}
