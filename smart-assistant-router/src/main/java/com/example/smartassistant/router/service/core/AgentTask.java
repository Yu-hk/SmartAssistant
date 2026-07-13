/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

/**
 * Agent 任务描述，用于多意图并行调度。
 * <p>
 * 从 RouterService 拆出的内部类，表示一个 Agent 调用任务。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-13
 */
public class AgentTask {

    private final String agentName;
    private final String question;
    private final String intentTag;
    private final double confidence;

    public AgentTask(String agentName, String question, String intentTag, double confidence) {
        this.agentName = agentName;
        this.question = question;
        this.intentTag = intentTag;
        this.confidence = confidence;
    }

    public String getAgentName() { return agentName; }
    public String getQuestion() { return question; }
    public String getIntentTag() { return intentTag; }
    public double getConfidence() { return confidence; }
}
