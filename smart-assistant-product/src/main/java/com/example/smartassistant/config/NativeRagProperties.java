/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the project-native agentic RAG loop. */
@ConfigurationProperties(prefix = "product.rag.agentic")
public class NativeRagProperties {

    /** Enables bounded evidence-gap retrieval. */
    private boolean enabled = true;

    /** Total retrieval attempts, including the first one. */
    private int maxAttempts = 2;

    /** Maximum evidence items exposed to the answer model. */
    private int maxEvidenceItems = 8;

    /** Avoid retrying when the best result already reaches this score. */
    private double sufficientScore = 0.5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return Math.max(1, Math.min(3, maxAttempts));
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getMaxEvidenceItems() {
        return Math.max(1, Math.min(20, maxEvidenceItems));
    }

    public void setMaxEvidenceItems(int maxEvidenceItems) {
        this.maxEvidenceItems = maxEvidenceItems;
    }

    public double getSufficientScore() {
        return Math.max(0.0, Math.min(1.0, sufficientScore));
    }

    public void setSufficientScore(double sufficientScore) {
        this.sufficientScore = sufficientScore;
    }
}
