package com.example.smartassistant.router.service.trace;

import com.example.smartassistant.router.model.AgentFlowSnapshot;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.time.Duration;
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
    private final Cache<String, AgentFlowSnapshot> localFallback;

    @Autowired
    public AgentFlowTraceStore(ObjectMapper objectMapper,
                               @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this(objectMapper, redisTemplate, Ticker.systemTicker(), 1000);
    }

    AgentFlowTraceStore(ObjectMapper objectMapper, StringRedisTemplate redisTemplate, Ticker ticker, long capacity) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.localFallback = Caffeine.newBuilder().maximumSize(capacity)
                .expireAfterWrite(Duration.ofHours(TTL_HOURS)).ticker(ticker).build();
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
                MERGER_NODE, "汇总 Agent 执行结果", "", "merger", "pending",
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

    public void complete(String requestId, List<SubTaskResult> results, List<String> participatingAgents,
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
                        "", node.type(),
                        succeeded ? "completed" : "failed",
                        succeeded ? mergerSummary(participatingAgents) : "没有可汇总的成功结果",
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
                    // Legacy snapshots can contain personal model output. Never expose it on reads.
                    return Optional.of(metadataOnly(objectMapper.readValue(json, AgentFlowSnapshot.class)));
                }
                // An authoritative miss/deletion must not be resurrected from process memory.
                localFallback.invalidate(requestId);
                return Optional.empty();
            } catch (Exception e) {
                log.warn("[AgentFlow] Redis read failed: type={}", e.getClass().getSimpleName());
            }
        }
        return Optional.ofNullable(localFallback.getIfPresent(requestId));
    }

    private void save(AgentFlowSnapshot snapshot) {
        snapshot = metadataOnly(snapshot);
        localFallback.put(snapshot.requestId(), snapshot);
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForValue().set(RoutingKeys.executionGraph(snapshot.requestId()),
                    objectMapper.writeValueAsString(snapshot), TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[AgentFlow] Redis write failed: type={}", e.getClass().getSimpleName());
        }
    }

    /** Diagnostic copies retain topology, not user questions, generated descriptions or reply prose. */
    static AgentFlowSnapshot metadataOnly(AgentFlowSnapshot snapshot) {
        List<AgentFlowSnapshot.Node> nodes = snapshot.nodes().stream().map(node -> {
            String agent = switch (safe(node.agent(), "")) {
                case "product", "order", "knowledge", "general", "router-fallback" -> node.agent();
                default -> "";
            };
            String label = switch (safe(node.type(), "")) {
                case "planner" -> "意图拆解与节点分配";
                case "merger" -> "汇总 Agent 执行结果";
                default -> switch (agent) {
                    case "product" -> "商品服务";
                    case "order" -> "订单服务";
                    case "knowledge" -> "知识检索";
                    default -> "业务处理节点";
                };
            };
            String summary = switch (safe(node.status(), "")) {
                case "completed" -> "处理完成";
                case "failed" -> "处理失败";
                case "skipped" -> "未执行";
                default -> "等待处理";
            };
            if ("merger".equals(node.type()) && "completed".equals(node.status())) {
                summary = "已完成执行结果汇总";
            }
            return new AgentFlowSnapshot.Node(node.id(), label, agent, node.type(), node.status(),
                    summary, node.dependsOn(), node.elapsedMs());
        }).toList();
        // Edge labels are fixed by topology rather than arbitrary descriptions from old data.
        List<AgentFlowSnapshot.Edge> edges = snapshot.edges().stream().map(edge ->
                new AgentFlowSnapshot.Edge(edge.from(), edge.to(), "依赖")).toList();
        return new AgentFlowSnapshot(snapshot.requestId(), "", snapshot.modelName(), snapshot.modelTier(),
                snapshot.questionChars(), snapshot.status(), snapshot.startedAt(), snapshot.completedAt(), nodes, edges);
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

    private static String mergerSummary(List<String> participatingAgents) {
        if (participatingAgents == null || participatingAgents.isEmpty()) {
            return "已完成内置流程结果汇总";
        }
        return "已汇总业务 Agent: " + String.join(", ", participatingAgents);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }
}
