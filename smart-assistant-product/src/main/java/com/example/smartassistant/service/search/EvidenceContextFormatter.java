/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a bounded, citation-ready context without exposing retrieval reasoning. */
public final class EvidenceContextFormatter {

    private static final Pattern CID_PATTERN = Pattern.compile("\\[CID:([^\\]]+)]");

    private EvidenceContextFormatter() {
    }

    public static FormattedEvidence format(List<RagSearchContext.RankedItem> items,
                                           List<String> knowledgeBases,
                                           int attempts,
                                           int maxItems) {
        if (items == null || items.isEmpty()) {
            return new FormattedEvidence("", List.of());
        }
        int limit = Math.min(Math.max(1, maxItems), items.size());
        String scope = knowledgeBases == null || knowledgeBases.isEmpty()
                ? "business-data" : String.join(",", knowledgeBases);
        StringBuilder content = new StringBuilder("【商品检索证据】\n")
                .append("知识域：").append(scope)
                .append("；检索轮次：").append(Math.max(1, attempts)).append('\n');
        Set<String> citationIds = new LinkedHashSet<>();
        for (int i = 0; i < limit; i++) {
            String evidenceId = "E" + (i + 1);
            String item = items.get(i).getContent();
            content.append('[').append(evidenceId).append("] ")
                    .append(item == null ? "" : item.strip()).append('\n');
            citationIds.add(evidenceId);
            if (item != null) {
                Matcher matcher = CID_PATTERN.matcher(item);
                while (matcher.find()) citationIds.add("CID:" + matcher.group(1));
            }
        }
        return new FormattedEvidence(content.toString().strip(), new ArrayList<>(citationIds));
    }

    public record FormattedEvidence(String content, List<String> citationIds) {
        public FormattedEvidence {
            content = content == null ? "" : content;
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }
}
