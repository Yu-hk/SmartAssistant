package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentNodeOutput;
import com.example.smartassistant.router.model.HandoffCommand;
import com.example.smartassistant.router.model.InputBindingExpression;
import com.example.smartassistant.router.model.IntentGraph.IntentNode;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.AgentMessageDispatcher;
import com.example.smartassistant.router.service.agent.RouterFallbackAgentService;
import com.example.smartassistant.router.service.heartbeat.AgentHeartbeatService;
import com.example.smartassistant.routing.contract.RoutingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes one Router task node. Graph topology, scheduling, checkpointing and
 * approval are intentionally owned by LangGraph4j; this class only implements
 * the reusable task protocol, retry and result-quality policy.
 */
@Service
public class GraphNodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(GraphNodeExecutionService.class);
    private static final int BREAKER_THRESHOLD = 2;
    private static final long SSE_TTL_SECONDS = 120;
    private static final long MAX_STREAM_EVENTS = 5_000;

    private final AgentCallerService agentCallerService;
    private final ReflectionService reflectionService;
    private final DegradationService degradationService;
    private final AgentHeartbeatService heartbeatService;
    private final RouterFallbackAgentService fallbackAgentService;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private AgentMessageDispatcher agentMessageDispatcher;

    @Autowired(required = false)
    private WorkflowCancellationService cancellationService;

    @Value("${router.graph.max-criteria-corrections:1}")
    private int maxCriteriaCorrections;

    public GraphNodeExecutionService(AgentCallerService agentCallerService,
                                     ReflectionService reflectionService,
                                     DegradationService degradationService,
                                     AgentHeartbeatService heartbeatService,
                                     RouterFallbackAgentService fallbackAgentService) {
        this.agentCallerService = agentCallerService;
        this.reflectionService = reflectionService;
        this.degradationService = degradationService;
        this.heartbeatService = heartbeatService;
        this.fallbackAgentService = fallbackAgentService;
    }

    public SubTaskResult execute(IntentNode node,
                          Map<String, SubTaskResult> completed,
                          ConcurrentHashMap<String, Integer> breakerFailures,
                          Long userId, String eventsKey, String requestId) {
        return execute(node, completed, breakerFailures, userId, eventsKey, requestId,
                null, null, null);
    }

    public SubTaskResult execute(IntentNode node,
                          Map<String, SubTaskResult> completed,
                          ConcurrentHashMap<String, Integer> breakerFailures,
                          Long userId, String eventsKey, String requestId,
                          String workflowKey, Integer workflowVersion, String workflowChecksum) {
        return execute(node, completed, breakerFailures, userId, eventsKey, requestId,
                workflowKey, workflowVersion, workflowChecksum, null);
    }

    public SubTaskResult execute(IntentNode node,
                          Map<String, SubTaskResult> completed,
                          ConcurrentHashMap<String, Integer> breakerFailures,
                          Long userId, String eventsKey, String requestId,
                          String workflowKey, Integer workflowVersion, String workflowChecksum,
                          String originalQuestion) {
        return executeRegistered(node, completed, breakerFailures, userId, eventsKey, requestId,
                workflowKey, workflowVersion, workflowChecksum, originalQuestion);
    }

    private SubTaskResult executeRegistered(IntentNode node,
                          Map<String, SubTaskResult> completed,
                          ConcurrentHashMap<String, Integer> breakerFailures,
                          Long userId, String eventsKey, String requestId,
                          String workflowKey, Integer workflowVersion, String workflowChecksum,
                          String originalQuestion) {
        String targetAgent = node.getTargetAgent();
        progress(eventsKey, "node_started", "节点[" + node.getDescription() + "]开始执行", targetAgent);

        if (RouteExecutionService.BUILTIN_ORDER_PREPARATION_AGENT.equals(targetAgent)) {
            SubTaskResult result = new SubTaskResult(node.getId(), node.getDescription(), null,
                    RouteExecutionService.builtInOrderPreparationReply(), true, List.of(), Map.of());
            result.setSystemNodeType(SubTaskResult.SystemNodeType.ORDER_PREPARATION);
            result.setDomainQuality(
                    com.example.smartassistant.common.quality.DomainQualityResult.pass(
                            1.0, "BUILTIN_ORDER_PREPARATION_GUIDANCE"));
            return result;
        }

        if (requestId != null && targetAgent != null) {
            heartbeatService.beat(requestId, targetAgent, "RUNNING", node.getDescription());
        }
        int failCount = breakerFailures.getOrDefault(targetAgent, 0);
        if (failCount >= BREAKER_THRESHOLD) {
            progress(eventsKey, "node_skipped", "节点[" + node.getDescription() + "]已被熔断", targetAgent);
            return failed(node, SubTaskResult.ErrorType.RETRYABLE_FAILED);
        }

        Map<String, Object> resolvedInput;
        try {
            resolvedInput = resolveInput(node, completed);
        } catch (IllegalArgumentException error) {
            log.warn("[GraphNode] 输入绑定解析失败: nodeId={}, reason={}",
                    node.getId(), truncate(error.getMessage(), 160));
            return recordFailure(node, breakerFailures, requestId,
                    truncate(error.getMessage(), 100), SubTaskResult.ErrorType.FATAL_FAILED);
        }
        if (hasProtocolMetadata(node)) {
            resolvedInput = new LinkedHashMap<>(resolvedInput);
            resolvedInput.putIfAbsent("_taskDescription", node.getDescription());
        }
        String enrichedDescription = enrich(node, completed, originalQuestion);
        int maxRetries = 3;
        int corrections = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                checkCancellation(requestId, userId);
                if (isFallbackTarget(targetAgent)) {
                    String text = fallbackAgentService.execute(enrichedDescription, userId);
                    if (text != null && !text.isBlank()) {
                        return new SubTaskResult(node.getId(), node.getDescription(),
                                RouterFallbackAgentService.AGENT_NAME, text, true, List.of(), Map.of());
                    }
                    return recordFailure(node, breakerFailures, requestId,
                            "Router fallback returned an empty response",
                            SubTaskResult.ErrorType.FATAL_FAILED);
                }
                AgentCallResult response;
                if (hasProtocolMetadata(node)) {
                    String requestQuestion = corrections > 0
                            ? enrichedDescription
                            : plainUserQuestion(originalQuestion, node.getDescription());
                    AgentExecutionRequest executionRequest = new AgentExecutionRequest(
                            AgentExecutionRequest.CURRENT_VERSION,
                            requestId, node.getId(), userId != null ? String.valueOf(userId) : null,
                            node.getOperation(), requestQuestion, resolvedInput, node.getDependsOn(),
                            node.getConstraints(), System.currentTimeMillis() + 60_000L,
                            node.getIdempotencyKey(), predecessorOutputs(node, completed),
                            workflowKey, workflowVersion, workflowChecksum, attempt, requestId);
                    response = agentMessageDispatcher != null
                            ? agentMessageDispatcher.dispatch(
                                    targetAgent, executionRequest, node.getAccessMode())
                            : agentCallerService.callAgentAndExtractTitles(targetAgent, executionRequest);
                } else {
                    response = agentCallerService.callAgentAndExtractTitles(
                            targetAgent, enrichedDescription, userId, requestId);
                }
                String text = response.getResponse();
                if (text == null || text.isBlank()) {
                    if (attempt < maxRetries) {
                        backoff(attempt);
                        continue;
                    }
                    return recordFailure(node, breakerFailures, requestId,
                            "节点返回空结果", SubTaskResult.ErrorType.FATAL_FAILED);
                }

                // The downstream request may still be running after this client times out.
                // Retrying it as a semantic quality correction duplicates an expensive model call
                // and amplifies load, so return control to the graph immediately.
                if (Boolean.TRUE.equals(response.getData().get(
                        AgentCallResult.TRANSPORT_FAILURE_KEY))) {
                    progress(eventsKey, "node_transport_failed",
                            "节点[" + node.getDescription() + "]下游调用超时或中断",
                            targetAgent);
                    return recordFailure(node, breakerFailures, requestId,
                            truncate(text, 100), SubTaskResult.ErrorType.RETRYABLE_FAILED);
                }
                if (Boolean.TRUE.equals(response.getData().get(
                        AgentCallResult.PROTOCOL_RETRYABLE_FAILURE_KEY))) {
                    progress(eventsKey, "node_protocol_retryable_failed",
                            "节点[" + node.getDescription() + "]返回可重试协议失败",
                            targetAgent);
                    return recordFailure(node, breakerFailures, requestId,
                            truncate(text, 100), SubTaskResult.ErrorType.RETRYABLE_FAILED);
                }

                var domainQuality = response.getDomainQuality();
                boolean evidenceLimited = domainQuality.getReasonCodes().stream()
                        .anyMatch(code -> code.endsWith("_EVIDENCE_LIMITED")
                                || "PRODUCT_EVIDENCE_UNAVAILABLE".equals(code));
                if (evidenceLimited) {
                    progress(eventsKey, "node_evidence_limited",
                            "节点[" + node.getDescription() + "]已返回可验证的证据边界",
                            targetAgent);
                }
                boolean structuredVerified = !domainQuality.isUnknown()
                        && Boolean.TRUE.equals(response.getData().get("verified"))
                        && response.getData().containsKey("criteriaSatisfied");
                SubTaskResult.ErrorType criteria;
                if (domainQuality.isFail()) {
                    criteria = SubTaskResult.ErrorType.FATAL_FAILED;
                    log.warn("[GraphNode] 领域验收拒绝结果: nodeId={}, operation={}, reasons={}",
                            node.getId(), node.getOperation(), domainQuality.getReasonCodes());
                } else if (evidenceLimited) {
                    criteria = SubTaskResult.ErrorType.NONE;
                } else if (structuredVerified) {
                    boolean satisfied = Boolean.TRUE.equals(
                            response.getData().get("criteriaSatisfied"));
                    criteria = satisfied
                            ? SubTaskResult.ErrorType.NONE
                            : SubTaskResult.ErrorType.FATAL_FAILED;
                    log.info("[GraphNode] 使用领域结构化结果验收: nodeId={}, operation={}, satisfied={}",
                            node.getId(), node.getOperation(), satisfied);
                } else if (domainQuality.isPass() || domainQuality.isWarn()) {
                    // PASS and WARN are both completed domain decisions. WARN means the
                    // answer is usable with an explicit evidence boundary; asking a generic
                    // Router reflection model to rewrite it can duplicate expensive Agent
                    // calls and even replace a factually safe answer with a model failure.
                    criteria = SubTaskResult.ErrorType.NONE;
                } else {
                    criteria = reflectionService.checkCriteria(text, node.getSuccessCriteria());
                }
                if (criteria == SubTaskResult.ErrorType.FATAL_FAILED) {
                    SubTaskResult rejected = new SubTaskResult(
                            node.getId(), node.getDescription(), targetAgent, text, false,
                            SubTaskResult.ErrorType.FATAL_FAILED,
                            response.getRealTitles(), response.getTagsByTitle());
                    rejected.setDomainQuality(response.getDomainQuality());
                    rejected.setStructuredData(response.getData());
                    progress(eventsKey, "node_criteria_rejected",
                            "节点[" + node.getDescription() + "]结构化验收未满足", targetAgent);
                    return rejected;
                }
                if (criteria == SubTaskResult.ErrorType.NEED_REPLAN
                        && corrections < maxCriteriaCorrections) {
                    corrections++;
                    enrichedDescription = correctionPrompt(
                            node.getDescription(), node.getSuccessCriteria(), text);
                    progress(eventsKey, "node_correcting",
                            "节点[" + node.getDescription() + "]正在定向修正", targetAgent);
                    continue;
                }
                if (criteria == SubTaskResult.ErrorType.RETRYABLE_FAILED && attempt < maxRetries) {
                    backoff(attempt);
                    continue;
                }

                breakerFailures.put(targetAgent, 0);
                degradationService.recordCall(true);
                if (requestId != null && targetAgent != null) {
                    heartbeatService.markCompleted(requestId, targetAgent);
                }
                SubTaskResult result = new SubTaskResult(node.getId(), node.getDescription(),
                        targetAgent, text, true, response.getRealTitles(), response.getTagsByTitle());
                result.setDomainQuality(response.getDomainQuality());
                result.setStructuredData(response.getData());
                progress(eventsKey, criteria == SubTaskResult.ErrorType.NEED_REPLAN
                                || domainQuality.isWarn()
                                ? "node_quality_degraded" : "node_completed",
                        "节点[" + node.getDescription() + "]执行完成", targetAgent);
                return result;
            } catch (WorkflowCancelledException cancelled) {
                throw cancelled;
            } catch (Exception ex) {
                checkCancellation(requestId, userId);
                SubTaskResult.ErrorType type = classifyException(ex);
                if (type == SubTaskResult.ErrorType.RETRYABLE_FAILED && attempt < maxRetries) {
                    backoff(attempt);
                    continue;
                }
                return recordFailure(node, breakerFailures, requestId,
                        truncate(ex.getMessage(), 100), type);
            }
        }
        return recordFailure(node, breakerFailures, requestId,
                "节点重试次数已耗尽", SubTaskResult.ErrorType.FATAL_FAILED);
    }

    private static boolean isFallbackTarget(String agentName) {
        return "general".equalsIgnoreCase(agentName)
                || "general_agent".equalsIgnoreCase(agentName)
                || RouterFallbackAgentService.AGENT_NAME.equalsIgnoreCase(agentName);
    }

    public SubTaskResult executeHandoff(HandoffCommand command,
                                 ConcurrentHashMap<String, Integer> breakerFailures,
                                 Long userId, String eventsKey, String requestId) {
        String target = command.targetAgent();
        String taskId = "handoff_" + target + "_" + System.nanoTime();
        IntentNode node = new IntentNode(taskId,
                command.question() + (command.contextPayload() == null || command.contextPayload().isBlank()
                        ? "" : "\n\n[Handoff 上下文]\n" + command.contextPayload()),
                target, List.of());
        return execute(node, Map.of(), breakerFailures, userId, eventsKey, requestId);
    }

    public static SubTaskResult.ErrorType classifyException(Throwable error) {
        if (error == null) return SubTaskResult.ErrorType.FATAL_FAILED;
        if (error instanceof TimeoutException || error instanceof SocketTimeoutException) {
            return SubTaskResult.ErrorType.RETRYABLE_FAILED;
        }
        if (error instanceof IOException) {
            String message = error.getMessage();
            if (message != null && (message.contains("timeout") || message.contains("connect")
                    || message.contains("refused") || message.contains("reset"))) {
                return SubTaskResult.ErrorType.RETRYABLE_FAILED;
            }
        }
        Throwable cause = error.getCause();
        return cause != null && cause != error
                ? classifyException(cause) : SubTaskResult.ErrorType.FATAL_FAILED;
    }

    void setAgentMessageDispatcher(AgentMessageDispatcher agentMessageDispatcher) {
        this.agentMessageDispatcher = agentMessageDispatcher;
    }

    void setCancellationService(WorkflowCancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    private void checkCancellation(String requestId, Long userId) {
        if (cancellationService != null) {
            cancellationService.throwIfCancellationRequested(requestId, userId);
        }
    }

    private SubTaskResult recordFailure(IntentNode node,
                                        ConcurrentHashMap<String, Integer> breakerFailures,
                                        String requestId, String reason,
                                        SubTaskResult.ErrorType type) {
        String target = node.getTargetAgent();
        breakerFailures.merge(target, 1, Integer::sum);
        degradationService.recordCall(false);
        if (requestId != null && target != null) heartbeatService.markFailed(requestId, target, reason);
        return failed(node, type);
    }

    private static SubTaskResult failed(IntentNode node, SubTaskResult.ErrorType type) {
        return new SubTaskResult(node.getId(), node.getDescription(),
                node.getTargetAgent(), "", false, type);
    }

    private static String enrich(IntentNode node, Map<String, SubTaskResult> completed,
                                 String originalQuestion) {
        StringBuilder context = new StringBuilder();
        for (String dependency : node.getDependsOn()) {
            SubTaskResult result = completed.get(dependency);
            if (result != null && result.isSuccess() && result.getResult() != null) {
                context.append('[').append(result.getAgentName() != null
                                ? result.getAgentName() : dependency)
                        .append("] ").append(result.getResult()).append("\n\n");
            }
        }
        StringBuilder prompt = new StringBuilder();
        if (originalQuestion != null && !originalQuestion.isBlank()) {
            prompt.append("[用户原始请求]\n").append(originalQuestion.trim())
                    .append("\n\n[当前节点任务]\n");
        }
        prompt.append(node.getDescription());
        if (!context.isEmpty()) {
            prompt.append("\n\n[已知信息]\n").append(context);
        }
        return prompt.toString();
    }

    private static String plainUserQuestion(String originalQuestion, String nodeDescription) {
        if (originalQuestion != null && !originalQuestion.isBlank()) {
            return originalQuestion.trim();
        }
        return nodeDescription;
    }

    private static boolean hasProtocolMetadata(IntentNode node) {
        return node.getIdempotencyKey() != null || !node.getInput().isEmpty()
                || !node.getConstraints().isEmpty()
                || !node.getDependsOn().isEmpty()
                || !"ANSWER".equalsIgnoreCase(node.getOperation());
    }

    private static Map<String, AgentNodeOutput> predecessorOutputs(
            IntentNode node, Map<String, SubTaskResult> completed) {
        Map<String, AgentNodeOutput> outputs = new java.util.LinkedHashMap<>();
        for (String dependency : node.getDependsOn()) {
            SubTaskResult result = completed.get(dependency);
            if (result == null) continue;
            outputs.put(dependency, new AgentNodeOutput(
                    result.getTaskId(), result.getAgentName(),
                    result.isSuccess() ? "SUCCEEDED" : result.getErrorType().name(),
                    result.getResult(), result.getStructuredData()));
        }
        return outputs;
    }

    /**
     * Resolves explicit node input bindings from declared predecessor outputs.
     * Supported paths are {@code $.nodes.<nodeId>.data.<field>},
     * {@code <nodeId>.data}, {@code <nodeId>.data.<field>} and {@code <nodeId>.answer}.
     */
    static Map<String, Object> resolveInput(
            IntentNode node, Map<String, SubTaskResult> completed) {
        Map<String, Object> resolved = new LinkedHashMap<>(node.getInput());
        for (Map.Entry<String, String> binding : node.getInputBindings().entrySet()) {
            String target = binding.getKey();
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("input binding target is blank");
            }
            Object value = resolveBinding(node, completed, binding.getValue());
            if (value == null) {
                Object explicitValue = resolved.get(target);
                if (hasUsableExplicitInput(explicitValue)) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "input binding cannot be resolved: " + binding.getValue());
            }
            resolved.put(target, value);
        }
        return resolved;
    }

    private static boolean hasUsableExplicitInput(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof java.util.Collection<?> collection) return !collection.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private static Object resolveBinding(IntentNode node,
                                         Map<String, SubTaskResult> completed,
                                         String expression) {
        InputBindingExpression.Parsed binding = InputBindingExpression.parse(expression);
        if (!node.getDependsOn().contains(binding.sourceNodeId())) return null;
        SubTaskResult source = completed.get(binding.sourceNodeId());
        if (source == null) return null;

        if (binding.section() == InputBindingExpression.Section.STATUS) {
            return source.getErrorType().name();
        }
        if (binding.section() == InputBindingExpression.Section.AGENT_NAME) {
            return source.getAgentName();
        }
        if (!source.isSuccess()) return null;
        if (binding.section() == InputBindingExpression.Section.ANSWER) {
            return source.getResult();
        }

        return resolveDataPath(source.getStructuredData(), binding.dataPath());
    }

    /**
     * Resolves the constrained workflow data path while tolerating the two shapes emitted by
     * domain contracts: Java/JSON camelCase fields and planner-generated snake_case fields.
     * List selectors are deliberately limited to a non-negative numeric index (for example
     * {@code orders[0]}); arbitrary JSONPath evaluation is not supported.
     */
    private static Object resolveDataPath(Object root, List<String> path) {
        Object value = root;
        for (String segment : path) {
            value = resolveDataPathSegment(value, segment);
            if (value == null) return null;
        }
        return value;
    }

    private static Object resolveDataPathSegment(Object current, String segment) {
        if (segment == null || segment.isBlank()) return null;
        int bracket = segment.indexOf('[');
        String field = bracket >= 0 ? segment.substring(0, bracket) : segment;
        Object value = current;
        if (!field.isEmpty()) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = compatibleMapValue(map, field);
            if (value == null && "product_ids".equals(field)) {
                value = productIdsFromCatalog(compatibleMapValue(map, "products"));
            }
        }

        int cursor = bracket;
        while (cursor >= 0) {
            int close = segment.indexOf(']', cursor + 1);
            if (close < 0 || close == cursor + 1) return null;
            String rawIndex = segment.substring(cursor + 1, close);
            int index;
            try {
                index = Integer.parseInt(rawIndex);
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (index < 0) return null;
            if (value instanceof List<?> list) {
                if (index >= list.size()) return null;
                value = list.get(index);
            } else if (value != null && value.getClass().isArray()) {
                if (index >= java.lang.reflect.Array.getLength(value)) return null;
                value = java.lang.reflect.Array.get(value, index);
            } else {
                return null;
            }
            cursor = close + 1 < segment.length() ? segment.indexOf('[', close + 1) : -1;
            if (cursor < 0 && close + 1 != segment.length()) return null;
        }
        return value;
    }

    private static Object compatibleMapValue(Map<?, ?> map, String requestedField) {
        Object direct = map.get(requestedField);
        if (direct != null || map.containsKey(requestedField)) return direct;
        String normalized = normalizeContractField(requestedField);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null
                    && normalized.equals(normalizeContractField(entry.getKey().toString()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalizeContractField(String field) {
        return field == null ? "" : field.replace("_", "")
                .replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Keeps dynamically generated plans compatible with the product protocol. The canonical
     * discovery output is {@code data.products}; older/model-generated plans sometimes ask for
     * {@code data.product_ids}. Derive the requested IDs without discarding the richer catalog
     * that is still delivered through predecessorOutputs.
     */
    private static Object productIdsFromCatalog(Object products) {
        if (!(products instanceof java.util.Collection<?> catalog) || catalog.isEmpty()) {
            return null;
        }
        java.util.List<Object> ids = new java.util.ArrayList<>();
        for (Object item : catalog) {
            if (!(item instanceof Map<?, ?> product)) return null;
            Object code = product.get("code");
            if (code == null || code.toString().isBlank()) return null;
            ids.add(code);
        }
        return java.util.List.copyOf(ids);
    }

    private static String correctionPrompt(String description, String criteria, String previous) {
        return description + "\n\n上一轮结果未满足验收标准，请修正。\n验收标准："
                + (criteria != null ? criteria : "未提供") + "\n上一轮结果：\n"
                + truncate(previous, 1_200);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(1_000L * (1L << Math.min(attempt, 3)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void progress(String eventsKey, String type, String content, String agent) {
        if (eventsKey == null || redisTemplate == null) return;
        try {
            String payload = "{\"type\":\"" + type + "\",\"content\":\""
                    + escape(content) + "\",\"agent\":\"" + escape(agent) + "\"}";
            redisTemplate.opsForList().rightPush(eventsKey, payload);
            redisTemplate.expire(eventsKey, SSE_TTL_SECONDS, TimeUnit.SECONDS);
        if (eventsKey.startsWith(RoutingKeys.SSE_EVENTS_PREFIX)) {
            String streamKey = RoutingKeys.SSE_STREAM_PREFIX
                    + eventsKey.substring(RoutingKeys.SSE_EVENTS_PREFIX.length());
                redisTemplate.opsForStream().add(streamKey, Map.of("payload", payload));
                redisTemplate.opsForStream().trim(streamKey, MAX_STREAM_EVENTS, true);
                redisTemplate.expire(streamKey, SSE_TTL_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.debug("[GraphNode] progress event failed: {}", e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
