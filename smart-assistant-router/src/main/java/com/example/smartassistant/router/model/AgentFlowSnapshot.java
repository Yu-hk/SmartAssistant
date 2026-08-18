package com.example.smartassistant.router.model;

import java.util.List;

/** Persisted, administrator-facing snapshot of one conversational Agent execution flow. */
public record AgentFlowSnapshot(
        String requestId,
        String question,
        String modelName,
        String modelTier,
        int questionChars,
        String status,
        long startedAt,
        Long completedAt,
        List<Node> nodes,
        List<Edge> edges) {

    public record Node(
            String id,
            String label,
            String agent,
            String type,
            String status,
            String summary,
            List<String> dependsOn,
            Long elapsedMs) {
    }

    public record Edge(String from, String to, String label) {
    }
}
