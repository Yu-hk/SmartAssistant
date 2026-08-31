/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import java.util.List;

/** Selects knowledge bases from registered agent capabilities, never from user-visible keywords. */
@FunctionalInterface
public interface KnowledgeScopeSelector {

    KnowledgeScope select(String agentName, List<String> skills, String query);

    record KnowledgeScope(List<String> knowledgeBases, String reason) {
        public KnowledgeScope {
            knowledgeBases = knowledgeBases == null ? List.of() : List.copyOf(knowledgeBases);
            reason = reason == null ? "" : reason;
        }
    }
}
