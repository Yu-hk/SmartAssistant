/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.skill;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Request-scoped inputs used to select the smallest useful set of skills.
 *
 * <p>The context only contains trusted routing results and the names of tools
 * that are actually available for the current Agent. The raw query is used as
 * a compatibility fallback for direct module calls that do not carry a
 * structured operation yet.</p>
 */
public final class SkillSelectionContext {

    private final String query;
    private final Set<String> operations;
    private final Set<String> tags;
    private final Set<String> availableTools;
    private final int maxSkills;

    private SkillSelectionContext(Builder builder) {
        this.query = builder.query == null ? "" : builder.query;
        this.operations = normalized(builder.operations);
        this.tags = normalized(builder.tags);
        this.availableTools = normalized(builder.availableTools);
        this.maxSkills = Math.max(1, builder.maxSkills);
    }

    public String getQuery() { return query; }
    public Set<String> getOperations() { return operations; }
    public Set<String> getTags() { return tags; }
    public Set<String> getAvailableTools() { return availableTools; }
    public int getMaxSkills() { return maxSkills; }

    public boolean hasAvailableToolCatalog() {
        return !availableTools.isEmpty();
    }

    public boolean containsOperation(String operation) {
        return operation != null && operations.contains(normalize(operation));
    }

    public boolean containsTag(String tag) {
        return tag != null && tags.contains(normalize(tag));
    }

    public boolean containsTool(String toolName) {
        return toolName != null && availableTools.contains(normalize(toolName));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SkillSelectionContext forQuery(String query) {
        return builder().query(query).build();
    }

    private static Set<String> normalized(Collection<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(normalize(value));
        }
        return Set.copyOf(result);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class Builder {
        private String query = "";
        private final Set<String> operations = new LinkedHashSet<>();
        private final Set<String> tags = new LinkedHashSet<>();
        private final Set<String> availableTools = new LinkedHashSet<>();
        private int maxSkills = 4;

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder operation(String operation) {
            if (operation != null) this.operations.add(operation);
            return this;
        }

        public Builder operations(Collection<String> operations) {
            if (operations != null) this.operations.addAll(operations);
            return this;
        }

        public Builder tag(String tag) {
            if (tag != null) this.tags.add(tag);
            return this;
        }

        public Builder tags(Collection<String> tags) {
            if (tags != null) this.tags.addAll(tags);
            return this;
        }

        public Builder availableTools(Collection<String> availableTools) {
            if (availableTools != null) this.availableTools.addAll(availableTools);
            return this;
        }

        public Builder maxSkills(int maxSkills) {
            this.maxSkills = maxSkills;
            return this;
        }

        public SkillSelectionContext build() {
            return new SkillSelectionContext(this);
        }
    }
}
