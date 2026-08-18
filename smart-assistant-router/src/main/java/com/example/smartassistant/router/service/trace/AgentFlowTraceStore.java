package com.example.smartassistant.router.service.trace;

import com.example.smartassistant.router.model.AgentFlowSnapshot;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Stores the real LangGraph topology and its final node states for the admin console. */
@Service
public class AgentFlowTraceStore {

    private static final Logger log = LoggerFactory.getLogger(AgentFlowTraceStore.class);
    private static final long TTL_HOURS = 24;
    private static final String PLANNER_NODE = "__intent_planner__";
    private static final String MERGER_NODE = "__result_merger__";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, AgentFlowSnapshot> localFallback = new ConcurrentHashMap<>();

    public AgentFlowTraceStore(ObjectMapper objectMapper,
                               @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public void start(String requestId, String question, TaskAnalysisResult analysis,
                      IntentGraph graph) {
        if (requestId == null || requestId.isBlank() || graph == null) return;
        long now = System.currentTimeMillis();
        List<AgentFlowSnapshot.Node> nodes = new ArrayList<>();
        nodes.add(new AgentFlowSnapshot.Node(
                PLANNER_NODE, "意图拆解与节点分配",
                analysis != null ? safe(analysis.getAnalysisModel(), "DeepSeek") : "DeepSeek",
                "planner", "completed",
                plannerSummary(analysis, graph), List.of(),
                analysis != null ? analysis.getAnalysisLatencyMs() : null));

        Set<String> graphIds = new LinkedHashSet<>();
        for (IntentGraph.IntentNode node : graph.getAllNodes()) {
            graphIds.add(node.getId());
            nodes.add(new AgentFlowSnapshot.Node(
                    node.getId(), node.getDescription(), node.getTargetAgent(), "agent", "pending",
                    "", List.copyOf(node.getDependsOn()), null));
        }
        nodes.add(new AgentFlowSnapshot.Node(
                MERGER_NODE, "汇总 Agent 执行结果", "orchestrator", "merger", "pending",
                "", terminalIds(graph), null));

        List<AgentFlowSnapshot.Edge> edges = new ArrayList<>();
        for (IntentGraph.IntentNode node : graph.getAllNodes()) {
            if (node.getDependsOn().isEmpty()) {
                edges.add(new AgentFlowSnapshot.Edge(PLANNER_NODE, node.getId(), "分配"));
            } else {
                node.getDependsOn().forEach(parent -> {
                    if (graphIds.contains(parent)) {
                        edges.add(new AgentFlowSnapshot.Edge(parent, node.getId(), "依赖"));
                    }
                });
            }
        }
        terminalIds(graph).forEach(id ->
                edges.add(new AgentFlowSnapshot.Edge(id, MERGER_NODE, "汇总")));

        save(new AgentFlowSnapshot(requestId, safe(question, ""),
                analysis != null ? safe(analysis.getAnalysisModel(), "") : "",
                analysis != null ? safe(analysis.getAnalysisModelTier(), "") : "",
                analysis != null ? analysis.getAnalysisQuestionChars() : 0,
                "running", now, null, List.copyOf(nodes), List.copyOf(edges)));
    }

    public void complete(String requestId, List<SubTaskResult> results, String finalAgent,
                         long totalElapsedMs) {
        AgentFlowSnapshot current = get(requestId).orElse(null);
        if (current == null) return;
        Map<String, SubTaskResult> byId = new HashMap<>();
        if (results != null) {
            results.forEach(result -> byId.put(result.getTaskId(), result));
        }
        List<AgentFlowSnapshot.Node> nodes = current.nodes().stream().map(node -> {
            if ("agent".equals(node.type())) {
                SubTaskResult result = byId.get(node.id());
                if (result == null) {
                    return new AgentFlowSnapshot.Node(node.id(), node.label(), node.agent(), node.type(),
                            "skipped", "未返回执行结果", node.dependsOn(), null);
                }
                return new AgentFlowSnapshot.Node(node.id(), node.label(),
                        safe(result.getAgentName(), node.agent()), node.type(),
                        result.isSuccess() ? "completed" : "failed",
                        truncate(result.getSummary(), 180), node.dependsOn(), null);
            }
            if (MERGER_NODE.equals(node.id())) {
                boolean succeeded = results != null && results.stream().anyMatch(SubTaskResult::isSuccess);
                return new AgentFlowSnapshot.Node(node.id(), node.label(),
                        safe(finalAgent, "orchestrator"), node.type(),
                        succeeded ? "completed" : "failed",
                        succeeded ? "已完成结果汇总" : "没有可汇总的成功结果",
                        node.dependsOn(), totalElapsedMs);
            }
            return node;
        }).toList();
        boolean failed = results == null || results.stream().noneMatch(SubTaskResult::isSuccess);
        save(new AgentFlowSnapshot(current.requestId(), current.question(), current.modelName(),
                current.modelTier(), current.questionChars(), failed ? "failed" : "completed",
                current.startedAt(), System.currentTimeMillis(), nodes, current.edges()));
    }

    public void fail(String requestId, String message) {
        AgentFlowSnapshot current = get(requestId).orElse(null);
        if (current == null) return;
        List<AgentFlowSnapshot.Node> nodes = current.nodes().stream().map(node -> {
            if ("pending".equals(node.status())) {
                return new AgentFlowSnapshot.Node(node.id(), node.label(), node.agent(), node.type(),
                        "failed", truncate(message, 180), node.dependsOn(), node.elapsedMs());
            }
            return node;
        }).toList();
        save(new AgentFlowSnapshot(current.requestId(), current.question(), current.modelName(),
                current.modelTier(), current.questionChars(), "failed", current.startedAt(),
                System.currentTimeMillis(), nodes, current.edges()));
    }

    public Optional<AgentFlowSnapshot> get(String requestId) {
        if (requestId == null || requestId.isBlank()) return Optional.empty();
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(RoutingKeys.executionGraph(requestId));
                if (json != null && !json.isBlank()) {
                    return Optional.of(objectMapper.readValue(json, AgentFlowSnapshot.class));
                }
            } catch (Exception e) {
                log.warn("[AgentFlow] Redis read failed: {}", e.getMessage());
            }
        }
        return Optional.ofNullable(localFallback.get(requestId));
    }

    private void save(AgentFlowSnapshot snapshot) {
        localFallback.put(snapshot.requestId(), snapshot);
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForValue().set(RoutingKeys.executionGraph(snapshot.requestId()),
                    objectMapper.writeValueAsString(snapshot), TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[AgentFlow] Redis write failed: {}", e.getMessage());
        }
    }

    private static List<String> terminalIds(IntentGraph graph) {
        Set<String> dependedOn = new HashSet<>();
        graph.getAllNodes().forEach(node -> dependedOn.addAll(node.getDependsOn()));
        return graph.getAllNodes().stream().map(IntentGraph.IntentNode::getId)
                .filter(id -> !dependedOn.contains(id)).toList();
    }

    private static String plannerSummary(TaskAnalysisResult analysis, IntentGraph graph) {
        if (analysis == null) return "已生成 " + graph.getNodeCount() + " 个执行节点";
        return "按 " + analysis.getAnalysisQuestionChars() + " 字输入选择 "
                + safe(analysis.getAnalysisModel(), "DeepSeek") + "，生成 "
                + graph.getNodeCount() + " 个执行节点";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }
}
