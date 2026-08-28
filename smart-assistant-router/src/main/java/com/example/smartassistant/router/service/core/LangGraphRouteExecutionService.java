package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.HandoffCommand;
import com.example.smartassistant.router.model.ExecutionPlan;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.IntentGraph.IntentNode;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import com.example.smartassistant.router.service.recovery.WorkflowExecutionLeaseService;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * Native LangGraph4j Router runtime. Each {@link IntentNode} is compiled into a
 * LangGraph4j node; dependency layers become native parallel branches and join
 * barriers, while checkpoints and approval pauses are handled by LangGraph4j.
 */
@Service
public class LangGraphRouteExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LangGraphRouteExecutionService.class);
    private static final String CONTEXT_ID = "contextId";
    private static final String GRAPH_SPEC = "graphSpec";
    private static final String USER_ID = "userId";
    private static final String EVENTS_KEY = "eventsKey";
    private static final String REQUEST_ID = "requestId";
    private static final String RESULTS = "results";
    private static final String COMPLETED_IDS = "completedIds";
    private static final String PHASE = "phase";
    private static final String VALIDATE = "validate_plan";
    private static final String COMPLETE = "complete";
    private static final String REJECT = "reject";
    private static final String HANDOFF = "handoff";
    private static final String REPLAN = "replan";
    private static final String REROUTE = "reroute";
    private static final String REROUTE_DONE = "done";
    private static final int MAX_REROUTE_ITERATIONS = 8;
    private static final String JOIN_PREFIX = "join_";

    private final GraphNodeExecutionService nodeExecutor;
    private final TaskPlannerService taskPlannerService;
    private final LangGraphRedisCheckpointSaver checkpointSaver;
    private final Executor parallelExecutor;
    private final ConcurrentHashMap<String, ExecutionContext> contexts = new ConcurrentHashMap<>();
    private WorkflowExecutionLeaseService executionLeaseService;

    @Value("${router.graph.max-replans:1}")
    private int maxReplans;

    public LangGraphRouteExecutionService(GraphNodeExecutionService nodeExecutor,
                                          TaskPlannerService taskPlannerService,
                                          LangGraphRedisCheckpointSaver checkpointSaver,
                                          @Qualifier("routerParallelAgentExecutor") Executor parallelExecutor) {
        this.nodeExecutor = nodeExecutor;
        this.taskPlannerService = taskPlannerService;
        this.checkpointSaver = checkpointSaver;
        this.parallelExecutor = parallelExecutor;
    }

    @Autowired(required = false)
    void setExecutionLeaseService(WorkflowExecutionLeaseService executionLeaseService) {
        this.executionLeaseService = executionLeaseService;
    }

    public List<SubTaskResult> execute(IntentGraph graph, Long userId,
                                       String eventsKey, String requestId) {
        Objects.requireNonNull(graph, "graph cannot be null");
        var validation = ExecutionPlanValidator.validateGraph(graph, null);
        if (!validation.valid()) {
            log.warn("[LangGraph4j] 拒绝无效执行图: {}", validation.errors());
            return List.of();
        }

        String contextId = UUID.randomUUID().toString();
        String threadId = requestId != null && !requestId.isBlank()
                ? requestId : contextId;
        ExecutionContext context = new ExecutionContext(graph, userId, eventsKey, requestId);
        contexts.put(contextId, context);
        try {
            return withExecutionLease(threadId,
                    () -> runWithReplans(contextId, threadId, graph, false));
        } catch (RuntimeException e) {
            throw new IllegalStateException("LangGraph4j Router execution failed", e);
        } finally {
            contexts.remove(contextId);
        }
    }

    /** Publication-time smoke compile. It builds the native StateGraph without executing nodes. */
    public void validateCompilation(IntentGraph graph) {
        if (graph == null) throw new IllegalArgumentException("graph is required");
        String contextId = UUID.randomUUID().toString();
        contexts.put(contextId, new ExecutionContext(graph, 0L, null, "workflow-publication"));
        try {
            compile(graph, Set.of());
        } finally {
            contexts.remove(contextId);
        }
    }

    /** Resumes a graph that was paused by a native interruptBefore approval node. */
    public List<SubTaskResult> resumeApproved(IntentGraph graph, Long userId,
                                              String eventsKey, String requestId) {
        return resumeGraph(graph, userId, eventsKey, requestId, true,
                "LangGraph4j approved execution failed");
    }

    private List<SubTaskResult> resumeGraph(IntentGraph graph, Long userId,
                                            String eventsKey, String requestId,
                                            boolean approvalGranted, String failureMessage) {
        Objects.requireNonNull(requestId, "requestId is required to resume an approved graph");
        String contextId = UUID.randomUUID().toString();
        ExecutionContext context = new ExecutionContext(graph, userId, eventsKey, requestId);
        contexts.put(contextId, context);
        try {
            return withExecutionLease(requestId,
                    () -> runWithReplans(contextId, requestId, graph, approvalGranted));
        } catch (RuntimeException e) {
            throw new IllegalStateException(failureMessage, e);
        } finally {
            contexts.remove(contextId);
        }
    }

    /**
     * Restores both graph definition and execution state from the native checkpoint.
     * The authenticated user must match the checkpoint owner before a write node can resume.
     */
    public List<SubTaskResult> resumeApproved(Long authenticatedUserId, String requestId) {
        ResumeState state = loadResumeState(authenticatedUserId, requestId, "Approval");
        return resumeApproved(state.graph(), authenticatedUserId, state.eventsKey(), requestId);
    }

    /**
     * Resumes an interrupted graph without granting approval to pending write nodes.
     * The last durable node boundary is restored and an in-flight node may be replayed;
     * business-side idempotency must therefore use the node idempotency key.
     */
    public List<SubTaskResult> resumeInterrupted(Long authenticatedUserId, String requestId) {
        ResumeState state = loadResumeState(authenticatedUserId, requestId, "Execution");
        return resumeGraph(state.graph(), authenticatedUserId, state.eventsKey(), requestId,
                false, "LangGraph4j interrupted execution recovery failed");
    }

    /** Lightweight metadata used by the recovery scanner; it never executes the graph. */
    public java.util.Optional<AutomaticRecoveryCandidate> automaticRecoveryCandidate(String requestId) {
        if (requestId == null || requestId.isBlank()) return java.util.Optional.empty();
        RunnableConfig config = RunnableConfig.builder().threadId(requestId).build();
        var checkpoint = checkpointSaver.get(config);
        if (checkpoint.isEmpty()) return java.util.Optional.empty();
        Map<String, Object> state = checkpoint.orElseThrow().getState();
        IntentGraph graph = graphFromState(state.get(GRAPH_SPEC));
        Set<String> completed = new HashSet<>(stringList(state.get(COMPLETED_IDS)));
        boolean pendingApproval = graph.getAllNodes().stream()
                .anyMatch(node -> node.isHumanApprovalRequired() && !completed.contains(node.getId()));
        return java.util.Optional.of(new AutomaticRecoveryCandidate(
                longValue(state.get(USER_ID)), pendingApproval));
    }

    private <T> T withExecutionLease(String requestId, Supplier<T> action) {
        if (executionLeaseService == null) return action.get();
        return executionLeaseService.executeWithLease(requestId, action);
    }

    private ResumeState loadResumeState(Long authenticatedUserId, String requestId,
                                        String checkpointType) {
        Objects.requireNonNull(authenticatedUserId, "authenticated user id is required");
        Objects.requireNonNull(requestId, "requestId is required to resume a graph");
        RunnableConfig config = RunnableConfig.builder().threadId(requestId).build();
        var checkpoint = checkpointSaver.get(config)
                .orElseThrow(() -> new IllegalArgumentException(
                        checkpointType + " checkpoint does not exist or has expired"));
        Long ownerId = longValue(checkpoint.getState().get(USER_ID));
        if (!authenticatedUserId.equals(ownerId)) {
            throw new SecurityException("Checkpoint does not belong to the authenticated user");
        }
        IntentGraph graph = graphFromState(checkpoint.getState().get(GRAPH_SPEC));
        String eventsKey = nullableString(checkpoint.getState().get(EVENTS_KEY));
        return new ResumeState(graph, eventsKey);
    }

    private List<SubTaskResult> runGraph(String contextId, String threadId,
                                         IntentGraph graph, boolean approvalGranted) {
        RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();
        boolean resume = checkpointSaver.get(baseConfig).isPresent();
        if (resume) restoreContextFromCheckpoint(context(contextId), baseConfig);
        DynamicWorkflow dynamic = compile(graph, context(contextId).results.keySet());
        RunnableConfig.Builder config = RunnableConfig.builder(baseConfig);
        for (String parallelSource : dynamic.parallelSources) {
            config.addParallelNodeExecutor(parallelSource, parallelExecutor);
        }
        RunnableConfig runnableConfig = config.build();
        Map<String, Object> initial = initialState(contextId, context(contextId));
        if (resume && hasPendingApproval(graph, context(contextId)) && !approvalGranted) {
            addApprovalPrompt(context(contextId), graph);
            return context(contextId).orderedResults();
        }
        var output = dynamic.workflow.invokeFinal(
                resume ? GraphInput.resume(Map.of(CONTEXT_ID, contextId)) : GraphInput.args(initial),
                runnableConfig).orElse(null);
        ExecutionContext context = contexts.get(contextId);
        if (output != null && output.state() != null) {
            context.lastState = output.state();
        }
        if (output != null && !output.isEND()) {
            context.pausedForApproval = true;
            addApprovalPrompt(context, graph);
        } else if (output != null && output.isEND()) {
            try {
                checkpointSaver.release(runnableConfig);
            } catch (Exception e) {
                log.warn("[LangGraph4j] release completed checkpoint failed: {}", e.getMessage());
            }
        }
        return context.orderedResults();
    }

    private List<SubTaskResult> runWithReplans(String contextId, String threadId,
                                                IntentGraph graph, boolean approvalGranted) {
        List<SubTaskResult> results = runGraph(contextId, threadId, graph, approvalGranted);
        int replans = 0;
        while (replans < Math.max(0, maxReplans)) {
            List<IntentNode> planned = context(contextId).takeReplannedNodes();
            if (planned.isEmpty()) break;
            int added = graph.addNodes(planned);
            if (added == 0) break;
            replans++;
            results = runGraph(contextId, threadId + ":replan:" + replans, graph, false);
        }
        return results;
    }

    private static boolean hasApproval(IntentGraph graph) {
        return graph.getAllNodes().stream().anyMatch(IntentNode::isHumanApprovalRequired);
    }

    private static boolean hasPendingApproval(IntentGraph graph, ExecutionContext context) {
        return graph.getAllNodes().stream().anyMatch(node -> node.isHumanApprovalRequired()
                && !context.results.containsKey(node.getId()));
    }

    private static void addApprovalPrompt(ExecutionContext context, IntentGraph graph) {
        IntentNode approval = graph.getAllNodes().stream()
                .filter(IntentNode::isHumanApprovalRequired)
                .filter(node -> !context.results.containsKey(node.getId()))
                .findFirst().orElse(null);
        if (approval == null) return;
        String taskId = approval.getId() + ":approval";
        SubTaskResult approvalResult = new SubTaskResult(taskId, approval.getDescription(), null,
                "该操作会修改业务数据，需要你明确确认后才能继续执行："
                        + approval.getDescription(), true);
        approvalResult.setSystemNodeType(SubTaskResult.SystemNodeType.APPROVAL);
        context.put(approvalResult);
    }

    @SuppressWarnings("unchecked")
    private void restoreContextFromCheckpoint(ExecutionContext context,
                                              RunnableConfig runnableConfig) {
        checkpointSaver.get(runnableConfig).ifPresent(checkpoint -> {
            Object storedResults = checkpoint.getState().get(RESULTS);
            if (!(storedResults instanceof List<?> values)) return;
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> map)) continue;
                String taskId = Objects.toString(map.get("taskId"), "");
                if (taskId.isBlank()) continue;
                if (Boolean.parseBoolean(Objects.toString(map.get("removed"), "false"))) {
                    context.results.remove(taskId);
                    continue;
                }
                boolean success = Boolean.parseBoolean(Objects.toString(map.get("success"), "false"));
                String errorName = Objects.toString(map.get("errorType"),
                        SubTaskResult.ErrorType.FATAL_FAILED.name());
                SubTaskResult.ErrorType errorType;
                try {
                    errorType = SubTaskResult.ErrorType.valueOf(errorName);
                } catch (IllegalArgumentException ignored) {
                    errorType = SubTaskResult.ErrorType.FATAL_FAILED;
                }
                SubTaskResult restored = new SubTaskResult(taskId,
                        Objects.toString(map.get("description"), ""),
                        nullableString(map.get("agentName")),
                        Objects.toString(map.get("result"), ""), success, errorType);
                if (map.get("data") instanceof Map<?, ?> data) {
                    Map<String, Object> restoredData = new LinkedHashMap<>();
                    data.forEach((key, item) -> restoredData.put(Objects.toString(key), item));
                    restored.setStructuredData(restoredData);
                }
                restored.setRequired(Boolean.parseBoolean(
                        Objects.toString(map.get("required"), "true")));
                restored.setMergePolicy(mergePolicy(map.get("mergePolicy")));
                restored.setOutputSchema(nullableString(map.get("outputSchema")));
                restored.setDomainQuality(new DomainQualityResult(
                        qualityStatus(map.get("qualityStatus")),
                        doubleValue(map.get("qualityScore"), 0.5),
                        stringList(map.get("qualityReasonCodes"))));
                if (map.get("handoff") instanceof Map<?, ?> handoff) {
                    try {
                        restored.setHandoffCommand(new HandoffCommand(
                                HandoffCommand.HandoffType.valueOf(
                                        Objects.toString(handoff.get("type"), "COMPLETE")),
                                nullableString(handoff.get("targetAgent")),
                                nullableString(handoff.get("question")),
                                nullableString(handoff.get("contextPayload"))));
                    } catch (IllegalArgumentException ignored) {
                        log.warn("[LangGraph4j] ignored invalid handoff checkpoint for task {}", taskId);
                    }
                }
                context.put(restored);
            }
        });
    }

    private DynamicWorkflow compile(IntentGraph graph, Set<String> completedNodeIds) {
        try {
            Map<String, org.bsc.langgraph4j.state.Channel<?>> channels = new LinkedHashMap<>();
            channels.put(RESULTS, Channels.appender(ArrayList::new));
            channels.put(COMPLETED_IDS, Channels.appender(ArrayList::new));
            channels.put(CONTEXT_ID, Channels.base(() -> ""));
            channels.put(GRAPH_SPEC, Channels.base(() -> Map.<String, Object>of()));
            channels.put(USER_ID, Channels.base(() -> 0L));
            channels.put(EVENTS_KEY, Channels.base(() -> ""));
            channels.put(REQUEST_ID, Channels.base(() -> ""));
            channels.put(PHASE, Channels.base(() -> ""));
            StateGraph<RouterGraphState> stateGraph = new StateGraph<>(
                    channels, RouterGraphState::new);
            stateGraph.addNode(VALIDATE, node_async((state, config) -> Map.of(PHASE, VALIDATE)));
            stateGraph.addNode(COMPLETE, node_async((state, config) -> Map.of(PHASE, COMPLETE)));
            stateGraph.addNode(REJECT, node_async((state, config) -> Map.of(PHASE, REJECT)));
            stateGraph.addNode(HANDOFF, node_async((state, config) -> executeHandoffs(state)));
            stateGraph.addNode(REPLAN, node_async((state, config) -> executeReplan(state)));
            List<IntentNode> rerouteMarkers = graph.getAllNodes().stream()
                    .filter(LangGraphRouteExecutionService::isRerouteMarker).toList();
            if (!rerouteMarkers.isEmpty()) {
                stateGraph.addNode(REROUTE,
                        node_async((state, config) -> executeReroute(state, rerouteMarkers)));
            }

            Map<String, Integer> layers = dependencyLayers(graph);
            Map<Integer, List<IntentNode>> layerNodes = graph.getAllNodes().stream()
                    .filter(node -> !isRerouteMarker(node))
                    .collect(Collectors.groupingBy(node -> layers.getOrDefault(node.getId(), 0),
                            LinkedHashMap::new, Collectors.toList()));
            if (layerNodes.isEmpty()) {
                stateGraph.addEdge(START, VALIDATE).addEdge(VALIDATE, REJECT).addEdge(REJECT, END);
                return new DynamicWorkflow(stateGraph.compile(
                        compileConfig(graph, completedNodeIds)), Set.of());
            }

            for (IntentNode node : graph.getAllNodes()) {
                if (isRerouteMarker(node)) continue;
                stateGraph.addNode(node.getId(), node_async((state, config) -> executeNode(state, node)));
            }
            // LangGraph4j collapses fan-out branches into one ParallelNode. Interrupts are
            // evaluated at graph-node boundaries, so approval graphs stay serial to ensure
            // interruptBefore is applied before the business node can run.
            if (graph.getAllNodes().stream().anyMatch(IntentNode::isHumanApprovalRequired)) {
                layerNodes = serialApprovalLayers(layerNodes);
            }
            List<Integer> orderedLayers = layerNodes.keySet().stream().sorted().toList();
            Set<String> parallelSources = new LinkedHashSet<>();
            String previous = VALIDATE;
            stateGraph.addEdge(START, VALIDATE);
            for (Integer layer : orderedLayers) {
                List<IntentNode> nodes = layerNodes.get(layer);
                String join = JOIN_PREFIX + layer;
                stateGraph.addNode(join, node_async((state, config) -> Map.of(PHASE, join)));
                for (IntentNode node : nodes) {
                    stateGraph.addEdge(previous, node.getId());
                    stateGraph.addEdge(node.getId(), join);
                }
                if (nodes.size() > 1) parallelSources.add(previous);
                previous = join;
            }
            if (rerouteMarkers.isEmpty()) {
                stateGraph.addEdge(previous, HANDOFF);
            } else {
                stateGraph.addEdge(previous, REROUTE);
                Map<String, String> mappings = new LinkedHashMap<>();
                mappings.put(REROUTE_DONE, HANDOFF);
                rerouteMarkers.stream()
                        .flatMap(marker -> marker.getConditionalDeps().stream())
                        .map(IntentNode.ConditionalDependency::rerouteTarget)
                        .filter(Objects::nonNull).filter(target -> !target.isBlank())
                        .distinct().forEach(target -> mappings.put(target, target));
                stateGraph.addConditionalEdges(REROUTE,
                        edge_async(state -> context(state).pendingRerouteTarget), mappings);
            }
            stateGraph.addEdge(HANDOFF, REPLAN).addEdge(REPLAN, COMPLETE)
                    .addEdge(COMPLETE, END);
            return new DynamicWorkflow(stateGraph.compile(
                    compileConfig(graph, completedNodeIds)), parallelSources);
        } catch (GraphStateException e) {
            throw new IllegalStateException("Cannot compile native Router LangGraph4j DAG", e);
        }
    }

    private static Map<Integer, List<IntentNode>> serialApprovalLayers(
            Map<Integer, List<IntentNode>> layers) {
        Map<Integer, List<IntentNode>> serial = new LinkedHashMap<>();
        int index = 0;
        for (Integer layer : layers.keySet().stream().sorted().toList()) {
            for (IntentNode node : layers.get(layer)) serial.put(index++, List.of(node));
        }
        return serial;
    }

    private CompileConfig compileConfig(IntentGraph graph, Set<String> completedNodeIds) {
        List<String> approvals = graph.getAllNodes().stream()
                .filter(IntentNode::isHumanApprovalRequired)
                .filter(node -> !completedNodeIds.contains(node.getId()))
                .map(IntentNode::getId).toList();
        int rerouteBudget = hasReroute(graph)
                ? Math.min(MAX_REROUTE_ITERATIONS,
                graph.getMaxGraphIterations() > 0 ? graph.getMaxGraphIterations() : 3)
                : 0;
        int recursion = Math.max(16, graph.getNodeCount() * 4 + 8
                + rerouteBudget * (graph.getNodeCount() * 2 + 4));
        return CompileConfig.builder()
                .graphId("smart-assistant-router-native")
                .checkpointSaver(checkpointSaver)
                .interruptsBefore(approvals)
                .recursionLimit(recursion)
                .releaseThread(approvals.isEmpty())
                .build();
    }

    private Map<String, Object> executeNode(RouterGraphState state, IntentNode node) {
        ExecutionContext context = context(state);
        if (context.results.containsKey(node.getId())) {
            return Map.of(COMPLETED_IDS, List.of(node.getId()));
        }
        Map<String, SubTaskResult> completed = context.resultSnapshot();
        if (!conditionSatisfied(node, completed)) {
            // A false conditional edge is a deliberate branch decision, not a task failure.
            return Map.of(COMPLETED_IDS, List.of(node.getId()));
        }
        SubTaskResult result = nodeExecutor.execute(node, completed, context.breakerFailures,
                context.userId, context.eventsKey, context.requestId,
                context.graph.getWorkflowKey(), context.graph.getWorkflowVersion(),
                context.graph.getWorkflowChecksum(), context.graph.getQuestion());
        if (result == null) {
            throw new IllegalStateException("Graph node returned no result: " + node.getId());
        }
        applyNodeContract(node, result);
        context.put(result);
        return Map.of(RESULTS, List.of(resultState(result)),
                COMPLETED_IDS, List.of(node.getId()));
    }

    private Map<String, Object> executeHandoffs(RouterGraphState state) {
        ExecutionContext context = context(state);
        List<Map<String, Object>> appended = new ArrayList<>();
        for (SubTaskResult source : context.orderedResults()) {
            if (!source.hasHandoff()) continue;
            HandoffCommand command = source.getHandoffCommand();
            if (command.handoffType() != HandoffCommand.HandoffType.HANDOFF) continue;
            String key = "handoff:" + source.getTaskId() + ":" + command.targetAgent();
            if (!context.completedHandoffs.add(key)) continue;
            SubTaskResult result = nodeExecutor.executeHandoff(command, context.breakerFailures,
                    context.userId, context.eventsKey, context.requestId);
            context.put(result);
            appended.add(resultState(result));
        }
        return appended.isEmpty() ? Map.of(PHASE, HANDOFF)
                : Map.of(PHASE, HANDOFF, RESULTS, appended);
    }

    /** Native lifecycle node that decides and plans recovery for NEED_REPLAN results. */
    private Map<String, Object> executeReplan(RouterGraphState state) {
        ExecutionContext context = context(state);
        if (context.replansPlanned >= Math.max(0, maxReplans)) return Map.of(PHASE, REPLAN);
        List<IntentNode> planned = planReplacementNodes(context.graph, context.orderedResults());
        if (!planned.isEmpty()) {
            context.pendingReplannedNodes = List.copyOf(planned);
            context.replansPlanned++;
        }
        return Map.of(PHASE, REPLAN);
    }

    /** Evaluates legacy reroute markers as a native conditional back-edge. */
    private Map<String, Object> executeReroute(RouterGraphState state,
                                                List<IntentNode> markers) {
        ExecutionContext context = context(state);
        context.pendingRerouteTarget = REROUTE_DONE;
        int limit = Math.min(MAX_REROUTE_ITERATIONS,
                context.graph.getMaxGraphIterations() > 0
                        ? context.graph.getMaxGraphIterations() : 3);
        if (context.rerouteIterations >= limit) return Map.of(PHASE, REROUTE);
        Map<String, SubTaskResult> results = context.resultSnapshot();
        for (IntentNode marker : markers) {
            if (!conditionSatisfied(marker, results)) continue;
            String target = marker.getConditionalDeps().stream()
                    .map(IntentNode.ConditionalDependency::rerouteTarget)
                    .filter(Objects::nonNull).filter(value -> !value.isBlank())
                    .findFirst().orElse(null);
            if (target == null) continue;
            Set<String> reset = downstreamNodes(context.graph, target);
            List<Map<String, Object>> tombstones = new ArrayList<>();
            for (String nodeId : reset) {
                if (context.results.remove(nodeId) != null) {
                    tombstones.add(Map.of("taskId", nodeId, "removed", true));
                }
            }
            context.rerouteIterations++;
            context.pendingRerouteTarget = target;
            log.info("[LangGraph4j] native conditional reroute: target={}, iteration={}/{}",
                    target, context.rerouteIterations, limit);
            return tombstones.isEmpty() ? Map.of(PHASE, REROUTE)
                    : Map.of(PHASE, REROUTE, RESULTS, tombstones);
        }
        return Map.of(PHASE, REROUTE);
    }

    private static Set<String> downstreamNodes(IntentGraph graph, String target) {
        Set<String> reset = new LinkedHashSet<>();
        reset.add(target);
        boolean changed;
        do {
            changed = false;
            for (IntentNode node : graph.getAllNodes()) {
                if (isRerouteMarker(node) || reset.contains(node.getId())) continue;
                if (allDependencies(node).stream().anyMatch(reset::contains)) {
                    changed |= reset.add(node.getId());
                }
            }
        } while (changed);
        return reset;
    }

    private static Map<String, Object> resultState(SubTaskResult result) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("taskId", result.getTaskId());
        state.put("description", result.getDescription());
        if (result.getAgentName() != null && !result.getAgentName().isBlank()) {
            state.put("agentName", result.getAgentName());
        }
        state.put("result", result.getResult());
        state.put("success", result.isSuccess());
        state.put("errorType", result.getErrorType().name());
        state.put("data", result.getStructuredData());
        state.put("required", result.isRequired());
        state.put("mergePolicy", result.getMergePolicy().name());
        state.put("outputSchema", Objects.toString(result.getOutputSchema(), ""));
        state.put("qualityStatus", result.getDomainQuality().getStatus().name());
        state.put("qualityScore", result.getDomainQuality().getScore());
        state.put("qualityReasonCodes", result.getDomainQuality().getReasonCodes());
        if (result.hasHandoff()) {
            HandoffCommand command = result.getHandoffCommand();
            state.put("handoff", Map.of(
                    "type", command.handoffType().name(),
                    "targetAgent", Objects.toString(command.targetAgent(), ""),
                    "question", Objects.toString(command.question(), ""),
                    "contextPayload", Objects.toString(command.contextPayload(), "")));
        }
        return state;
    }

    private static Map<String, Object> initialState(String contextId, ExecutionContext context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(CONTEXT_ID, contextId);
        state.put(GRAPH_SPEC, graphState(context.graph));
        if (context.userId != null) state.put(USER_ID, context.userId);
        if (context.eventsKey != null) state.put(EVENTS_KEY, context.eventsKey);
        if (context.requestId != null) state.put(REQUEST_ID, context.requestId);
        return state;
    }

    private static Map<String, Object> graphState(IntentGraph graph) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (IntentNode node : graph.getAllNodes()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", node.getId());
            value.put("description", node.getDescription());
            value.put("targetAgent", node.getTargetAgent());
            value.put("dependsOn", node.getDependsOn());
            value.put("successCriteria", Objects.toString(node.getSuccessCriteria(), ""));
            value.put("humanApprovalRequired", node.isHumanApprovalRequired());
            value.put("operation", node.getOperation());
            value.put("input", node.getInput());
            value.put("constraints", node.getConstraints());
            value.put("idempotencyKey", Objects.toString(node.getIdempotencyKey(), ""));
            value.put("accessMode", node.getAccessMode());
            value.put("required", node.isRequired());
            value.put("mergePolicy", node.getMergePolicy().name());
            value.put("outputSchema", Objects.toString(node.getOutputSchema(), ""));
            value.put("inputBindings", node.getInputBindings());
            List<Map<String, Object>> conditions = node.getConditionalDeps().stream()
                    .map(condition -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("sourceNodeId", condition.sourceNodeId());
                        item.put("conditionType", condition.conditionType().name());
                        item.put("conditionValue", Objects.toString(condition.conditionValue(), ""));
                        item.put("rerouteTarget", Objects.toString(condition.rerouteTarget(), ""));
                        return item;
                    }).toList();
            value.put("conditionalDeps", conditions);
            nodes.add(value);
        }
        return Map.of(
                "question", Objects.toString(graph.getQuestion(), ""),
                "maxGraphIterations", graph.getMaxGraphIterations(),
                "nodes", nodes);
    }

    private static IntentGraph graphFromState(Object value) {
        if (!(value instanceof Map<?, ?> graph)) {
            throw new IllegalStateException("Approval checkpoint is missing its graph definition");
        }
        Object nodeValue = graph.get("nodes");
        if (!(nodeValue instanceof List<?> rawNodes)) {
            throw new IllegalStateException("Approval checkpoint has an invalid graph definition");
        }
        List<IntentNode> nodes = new ArrayList<>();
        for (Object rawNode : rawNodes) {
            if (!(rawNode instanceof Map<?, ?> node)) continue;
            List<String> dependencies = stringList(node.get("dependsOn"));
            List<String> constraints = stringList(node.get("constraints"));
            List<IntentNode.ConditionalDependency> conditions = new ArrayList<>();
            if (node.get("conditionalDeps") instanceof List<?> rawConditions) {
                for (Object rawCondition : rawConditions) {
                    if (!(rawCondition instanceof Map<?, ?> condition)) continue;
                    conditions.add(new IntentNode.ConditionalDependency(
                            Objects.toString(condition.get("sourceNodeId"), ""),
                            IntentGraph.ConditionType.valueOf(
                                    Objects.toString(condition.get("conditionType"), "RESULT_SUCCESS")),
                            nullableString(condition.get("conditionValue")),
                            nullableString(condition.get("rerouteTarget"))));
                }
            }
            Map<String, Object> input = new LinkedHashMap<>();
            if (node.get("input") instanceof Map<?, ?> rawInput) {
                rawInput.forEach((key, item) -> input.put(Objects.toString(key), item));
            }
            Map<String, String> inputBindings = stringMap(node.get("inputBindings"));
            nodes.add(new IntentNode(
                    Objects.toString(node.get("id"), ""),
                    Objects.toString(node.get("description"), ""),
                    Objects.toString(node.get("targetAgent"), ""),
                    dependencies,
                    nullableString(node.get("successCriteria")),
                    conditions,
                    Boolean.parseBoolean(Objects.toString(node.get("humanApprovalRequired"), "false")),
                    Objects.toString(node.get("operation"), "ANSWER"),
                    input,
                    constraints,
                    nullableString(node.get("idempotencyKey")),
                    Objects.toString(node.get("accessMode"), "READ"),
                    Boolean.parseBoolean(Objects.toString(node.get("required"), "true")),
                    mergePolicy(node.get("mergePolicy")),
                    nullableString(node.get("outputSchema")),
                    inputBindings));
        }
        int maxIterations = intValue(graph.get("maxGraphIterations"));
        return new IntentGraph(Objects.toString(graph.get("question"), ""), nodes, maxIterations);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(Objects::toString).toList();
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(Objects.toString(key), Objects.toString(item));
            }
        });
        return result;
    }

    private static ExecutionPlan.MergePolicy mergePolicy(Object value) {
        try {
            return ExecutionPlan.MergePolicy.valueOf(
                    Objects.toString(value, ExecutionPlan.MergePolicy.APPEND.name())
                            .trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ExecutionPlan.MergePolicy.APPEND;
        }
    }

    private static DomainQualityResult.Status qualityStatus(Object value) {
        try {
            return DomainQualityResult.Status.valueOf(
                    Objects.toString(value, DomainQualityResult.Status.UNKNOWN.name())
                            .trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return DomainQualityResult.Status.UNKNOWN;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null || value.toString().isBlank()) return fallback;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void applyNodeContract(IntentNode node, SubTaskResult result) {
        result.setRequired(node.isRequired());
        result.setMergePolicy(node.getMergePolicy());
        result.setOutputSchema(node.getOutputSchema());
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return null;
        return Long.valueOf(value.toString());
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null || value.toString().isBlank()) return 0;
        return Integer.parseInt(value.toString());
    }

    private static String nullableString(Object value) {
        String text = Objects.toString(value, "");
        return text.isBlank() ? null : text;
    }

    private static Map<String, Integer> dependencyLayers(IntentGraph graph) {
        Map<String, Integer> layers = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        Map<String, IntentNode> nodes = graph.getAllNodes().stream()
                .collect(Collectors.toMap(IntentNode::getId, node -> node));
        for (IntentNode node : graph.getAllNodes()) layer(node, nodes, layers, visiting);
        return layers;
    }

    private static int layer(IntentNode node, Map<String, IntentNode> nodes,
                             Map<String, Integer> layers, Set<String> visiting) {
        Integer existing = layers.get(node.getId());
        if (existing != null) return existing;
        if (!visiting.add(node.getId())) throw new IllegalArgumentException("cycle: " + node.getId());
        int value = 0;
        for (String dependency : allDependencies(node)) {
            IntentNode parent = nodes.get(dependency);
            if (parent != null) value = Math.max(value, layer(parent, nodes, layers, visiting) + 1);
        }
        visiting.remove(node.getId());
        layers.put(node.getId(), value);
        return value;
    }

    private static Collection<String> allDependencies(IntentNode node) {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>(node.getDependsOn());
        node.getConditionalDeps().stream().map(IntentNode.ConditionalDependency::sourceNodeId)
                .forEach(dependencies::add);
        return dependencies;
    }

    private static boolean conditionSatisfied(IntentNode node, Map<String, SubTaskResult> results) {
        for (IntentNode.ConditionalDependency condition : node.getConditionalDeps()) {
            SubTaskResult source = results.get(condition.sourceNodeId());
            if (source == null) return false;
            String text = source.getResult() != null ? source.getResult() : "";
            boolean matches = switch (condition.conditionType()) {
                case RESULT_CONTAINS -> condition.conditionValue() != null
                        && text.contains(condition.conditionValue());
                case RESULT_NOT_CONTAINS -> condition.conditionValue() != null
                        && !text.contains(condition.conditionValue());
                case RESULT_SUCCESS -> source.isSuccess();
                case RESULT_FAILED -> !source.isSuccess();
            };
            if (!matches) return false;
        }
        return true;
    }

    private static boolean isRerouteMarker(IntentNode node) {
        return node.getConditionalDeps().stream()
                .anyMatch(dependency -> dependency.rerouteTarget() != null
                        && !dependency.rerouteTarget().isBlank());
    }

    private static boolean hasReroute(IntentGraph graph) {
        return graph.getAllNodes().stream().anyMatch(LangGraphRouteExecutionService::isRerouteMarker);
    }

    private List<IntentNode> planReplacementNodes(IntentGraph graph, List<SubTaskResult> results) {
        SubTaskResult failed = results.stream().filter(SubTaskResult::needsReplan).findFirst().orElse(null);
        if (failed == null) return List.of();
        String successful = results.stream().filter(SubTaskResult::isSuccess)
                .map(result -> "[" + result.getAgentName() + "] " + result.getSummary())
                .collect(Collectors.joining("\n"));
        String prompt = "原始问题：" + graph.getQuestion() + "\n已完成结果：\n" + successful
                + "\n失败任务：" + failed.getDescription()
                + "\n请只为失败任务重新规划子任务，保留已有成果。";
        try {
            IntentGraph replanned = taskPlannerService.replan(prompt);
            if (replanned == null) return List.of();
            failed.setErrorType(SubTaskResult.ErrorType.FATAL_FAILED);
            return new ArrayList<>(replanned.getAllNodes());
        } catch (Exception e) {
            log.warn("[LangGraph4j] replan failed: {}", e.getMessage());
            return List.of();
        }
    }

    private ExecutionContext context(RouterGraphState state) {
        String id = state.<String>value(CONTEXT_ID)
                .orElseThrow(() -> new IllegalStateException("Missing Router graph context id"));
        ExecutionContext context = contexts.get(id);
        if (context == null) throw new IllegalStateException("Router graph context expired: " + id);
        return context;
    }

    private ExecutionContext context(String contextId) {
        ExecutionContext context = contexts.get(contextId);
        if (context == null) throw new IllegalStateException("Router graph context expired: " + contextId);
        return context;
    }

    static final class RouterGraphState extends AgentState {
        RouterGraphState(Map<String, Object> initData) { super(initData); }
    }

    private record DynamicWorkflow(CompiledGraph<RouterGraphState> workflow,
                                   Set<String> parallelSources) {}

    private record ResumeState(IntentGraph graph, String eventsKey) {}

    public record AutomaticRecoveryCandidate(Long userId, boolean pendingApproval) {}

    private static final class ExecutionContext {
        final IntentGraph graph;
        final Long userId;
        final String eventsKey;
        final String requestId;
        final ConcurrentHashMap<String, SubTaskResult> results = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Integer> breakerFailures = new ConcurrentHashMap<>();
        final Set<String> completedHandoffs = ConcurrentHashMap.newKeySet();
        volatile boolean pausedForApproval;
        volatile RouterGraphState lastState;
        volatile List<IntentNode> pendingReplannedNodes = List.of();
        volatile int replansPlanned;
        volatile String pendingRerouteTarget = REROUTE_DONE;
        volatile int rerouteIterations;

        ExecutionContext(IntentGraph graph, Long userId, String eventsKey, String requestId) {
            this.graph = graph;
            this.userId = userId;
            this.eventsKey = eventsKey;
            this.requestId = requestId;
        }

        void put(SubTaskResult result) { results.put(result.getTaskId(), result); }
        Map<String, SubTaskResult> resultSnapshot() { return Map.copyOf(results); }
        List<IntentNode> takeReplannedNodes() {
            List<IntentNode> nodes = pendingReplannedNodes;
            pendingReplannedNodes = List.of();
            return nodes;
        }
        List<SubTaskResult> orderedResults() {
            Map<String, Integer> order = new HashMap<>();
            int index = 0;
            for (IntentNode node : graph.getAllNodes()) order.put(node.getId(), index++);
            return results.values().stream()
                    .sorted(Comparator.comparingInt(result -> order.getOrDefault(result.getTaskId(), Integer.MAX_VALUE)))
                    .toList();
        }
    }

}
