/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.RouterFallbackAgentService;
import com.example.smartassistant.router.service.guardrail.EmotionCheckResult;
import com.example.smartassistant.router.service.trace.AgentFlowTraceStore;
import com.example.smartassistant.routing.contract.RoutingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多 Agent 协作执行服务。
 * <p>
 * 从 RouterService 拆出，负责图分解 → 并行执行 → 结果合并 → 兜底的全流程。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-13
 */
@Service
public class RouteExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RouteExecutionService.class);

    private static final long SSE_EVENTS_TTL_SECONDS = 120;
    private static final int MAX_SSE_EVENTS_PER_KEY = 5_000;
    static final String BUILTIN_ORDER_PREPARATION_AGENT = "builtin_order_preparation";

    private static final List<String> FALLBACK_MESSAGES = List.of(
            "😅 抱歉让你等了这么久，目前服务似乎遇到了一些临时问题。请稍后再试一下，或者联系技术支持看看。谢谢你的耐心！",
            "🙏 不好意思让你久等了，系统这会儿有点忙不过来，暂时没办法回应你的问题。过一会儿再找我试试吧！",
            "🤗 哎呀，好像出了点小岔子……你先别着急，我这边正在努力恢复中，等一小会儿再来找我聊聊好吗？",
            "😊 真抱歉，刚才没能帮上忙。系统可能在打盹儿，你先去喝杯水，待会儿再来找我试试看？"
    );

    private final AtomicInteger fallbackIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Integer> sseEventCounts = new ConcurrentHashMap<>();

    private final AgentCallerService agentCallerService;
    private final TaskPlannerService taskPlanner;
    private final ResultMerger resultMerger;
    private final RouteFinalizer routeFinalizer;
    private final RouterFallbackAgentService fallbackAgentService;
    private final StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private DegradationService degradationService;

    @Autowired(required = false)
    private AgentFlowTraceStore agentFlowTraceStore;

    private final LangGraphRouteExecutionService langGraphExecutionService;

    public RouteExecutionService(
            AgentCallerService agentCallerService,
            TaskPlannerService taskPlanner,
            LangGraphRouteExecutionService langGraphExecutionService,
            ResultMerger resultMerger,
            RouteFinalizer routeFinalizer,
            RouterFallbackAgentService fallbackAgentService,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.agentCallerService = agentCallerService;
        this.taskPlanner = taskPlanner;
        this.langGraphExecutionService = langGraphExecutionService;
        this.resultMerger = resultMerger;
        this.routeFinalizer = routeFinalizer;
        this.fallbackAgentService = fallbackAgentService;
        this.redisTemplate = redisTemplate;
    }

    // ==================== 公共 API ====================

    /**
     * 多 Agent 协作：图分解 → 图执行 → 结果合并。
     */
    public RoutingResult executeCollaborative(String question, Long userId, String requestId,
                                               EmotionCheckResult emotion) {
        return executeCollaborative(question, userId, requestId, emotion, null);
    }

    /**
     * Executes a collaborative request while reusing the structured task analysis that the
     * fusion stage already produced. Multi-intent analyses can be converted directly into a
     * graph, avoiding a second model planning call on the latency-critical SSE path.
     */
    public RoutingResult executeCollaborative(String question, Long userId, String requestId,
                                               EmotionCheckResult emotion,
                                               TaskAnalysisResult taskAnalysis) {
        return executeCollaborative(question, userId, requestId, emotion, taskAnalysis, requestId);
    }

    /**
     * @param flowLookupId 管理端查询链路使用的稳定 ID，通常为 sessionId
     */
    public RoutingResult executeCollaborative(String question, Long userId, String requestId,
                                               EmotionCheckResult emotion,
                                               TaskAnalysisResult taskAnalysis,
                                               String flowLookupId) {
        long start = System.currentTimeMillis();
        String flowId = flowLookupId != null && !flowLookupId.isBlank()
                ? flowLookupId : requestId;

        if (agentCallerService.getAvailableAgentCount() == 0) {
            log.warn("[Collaborative] 无可用 Agent，降级到内联 ChatClient 兜底");
            return inlineFallback(question, emotion);
        }

        DegradationService.DegradationLevel degLevel = (degradationService != null)
                ? degradationService.getDegradationLevel()
                : DegradationService.DegradationLevel.NORMAL;

        if (degLevel == DegradationService.DegradationLevel.HALF_OPEN) {
            log.info("[Collaborative] 🟡 半开探测: 放行一次请求验证恢复");
            RoutingResult probeResult = inlineFallback(question, emotion);
            boolean success = probeResult != null && probeResult.getResult() != null
                    && !probeResult.getResult().isBlank()
                    && !probeResult.getResult().startsWith("❌");
            if (degradationService != null) {
                degradationService.recordProbeResult(success);
            }
            return probeResult;
        }
        if (degLevel == DegradationService.DegradationLevel.HEAVY) {
            log.warn("[Collaborative] 🔴 重度降级，跳过所有 Agent 调用，回退到内联兜底");
            return inlineFallback(question, emotion);
        }
        if (degLevel == DegradationService.DegradationLevel.LIGHT) {
            log.warn("[Collaborative] 🟡 轻度降级，跳过复杂 DAG");
            String reply = fallbackAgentService.execute(question, userId, null);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Collaborative] 降级单 Agent 完成: elapsed={}ms, replyLen={}", elapsed, reply.length());
            return routeFinalizer.applyEmotion(RoutingResult.builder()
                    .result(reply).agentName(RouterFallbackAgentService.AGENT_NAME).confidence(1.0).build(), emotion);
        }

        IntentGraph graph = buildGraphFromAnalysis(question, taskAnalysis, requestId);
        boolean fallbackPlanned = graph == null;
        if (graph == null) {
            graph = taskPlanner.planToGraph(question);
        } else {
            log.info("[Collaborative] Reusing task analysis as execution graph: nodes={}",
                    graph.getNodeCount());
        }
        if (graph.getNodeCount() == 0) {
            log.warn("[Collaborative] 图分解为空，降级到内联 ChatClient 兜底");
            return inlineFallback(question, emotion);
        }

        if (agentFlowTraceStore != null) {
            agentFlowTraceStore.start(flowId, question, taskAnalysis, graph);
        }
        String eventsKey = requestId != null ? RoutingKeys.sseEvents(requestId) : null;
        List<SubTaskResult> results;
        try {
            results = executeGraph(graph, userId, eventsKey, requestId);
        } catch (RuntimeException e) {
            if (agentFlowTraceStore != null) agentFlowTraceStore.fail(flowId, e.getMessage());
            throw e;
        }

        storeSseEvent(eventsKey, "summarizing", "正在整合多源信息...", null);

        boolean hasBuiltInOrderPreparation = results.stream()
                .anyMatch(result -> BUILTIN_ORDER_PREPARATION_AGENT.equals(result.getAgentName()));
        String merged = hasBuiltInOrderPreparation
                ? mergeOrderPreparationResults(results)
                : fallbackPlanned
                        ? mergeFallbackPlannedResults(results)
                        : resultMerger.merge(question, results, requestId);
        long elapsed = System.currentTimeMillis() - start;

        String resultOwner = determineResultOwner(results);
        if (agentFlowTraceStore != null) {
            agentFlowTraceStore.complete(flowId, results, resultOwner, elapsed);
        }

        boolean allFailed = results.isEmpty() || results.stream().noneMatch(SubTaskResult::isSuccess);
        boolean hasRequiredFailure = !ResultMerger.requiredFailures(results).isEmpty();
        if ((!hasRequiredFailure && allFailed) || merged == null || merged.isBlank()) {
            log.warn("[Collaborative] 所有子任务均失败，降级到内联 ChatClient 兜底");
            return inlineFallback(question, emotion);
        }

        log.info("[Collaborative] 协作完成: {} 个子任务, 耗时={}ms, 结果长度={}",
                results.size(), elapsed, merged.length());

        DomainQualityResult domainQuality = aggregateDomainQuality(results);

        return routeFinalizer.applyEmotion(RoutingResult.builder()
                .result(merged).agentName(resultOwner).confidence(0.8)
                .domainQuality(domainQuality).build(), emotion);
    }

    /**
     * Attributes a multi-node result to its only business Agent when every node
     * belongs to the same domain. The orchestrator owns the result only when it
     * actually combines multiple business domains.
     */
    static String determineResultOwner(List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return "none";
        List<String> businessAgents = results.stream()
                .map(SubTaskResult::getAgentName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(agent -> !agent.isBlank())
                .filter(agent -> !"none".equals(agent))
                .filter(agent -> !BUILTIN_ORDER_PREPARATION_AGENT.equals(agent))
                .distinct()
                .toList();
        if (businessAgents.isEmpty()) return "none";
        return businessAgents.size() == 1 ? businessAgents.getFirst() : "orchestrator";
    }

    /** Uses LangGraph4j as the sole orchestration engine. */
    List<SubTaskResult> executeGraph(IntentGraph graph, Long userId,
                                     String eventsKey, String requestId) {
        log.info("[Collaborative] 使用 LangGraph4j 编排引擎: requestId={}", requestId);
        return langGraphExecutionService.execute(graph, userId, eventsKey, requestId);
    }

    static DomainQualityResult aggregateDomainQuality(List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return DomainQualityResult.unknown();
        List<SubTaskResult> successful = results.stream().filter(SubTaskResult::isSuccess).toList();
        if (successful.isEmpty()) return DomainQualityResult.fail("ALL_SUBTASKS_FAILED");
        DomainQualityResult successfulQuality = successful.stream()
                .map(SubTaskResult::getDomainQuality)
                .filter(Objects::nonNull)
                .reduce(DomainQualityResult::worst)
                .orElse(DomainQualityResult.unknown());
        if (successful.size() < results.size()) {
            return DomainQualityResult.warn(
                    Math.min(0.6, successfulQuality.getScore()), "PARTIAL_AGENT_FAILURE");
        }
        return successfulQuality;
    }

    /**
     * 主结构化分析已经失败时，轻量规划器本身就是降级路径；直接合并已验证的
     * 节点结果，避免再调用一次模型并把端到端耗时推过 SSE 决策预算。
     */
    static String mergeFallbackPlannedResults(List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder merged = new StringBuilder();
        for (SubTaskResult result : results) {
            if (result == null || !result.isSuccess()
                    || result.getResult() == null || result.getResult().isBlank()) {
                continue;
            }
            if (!merged.isEmpty()) merged.append("\n\n");
            String description = result.getDescription();
            if (description != null && !description.isBlank()) {
                merged.append("### ").append(description.trim()).append('\n');
            }
            merged.append(result.getResult().trim());
        }
        return merged.toString();
    }

    static IntentGraph buildGraphFromAnalysis(String question, TaskAnalysisResult analysis) {
        return buildGraphFromAnalysis(question, analysis, null);
    }

    static IntentGraph buildGraphFromAnalysis(String question, TaskAnalysisResult analysis,
                                              String executionId) {
        ExecutionPlan plan = buildExecutionPlan(question, analysis, executionId);
        if (plan == null) return null;
        ExecutionPlanValidator.ValidationResult validation = ExecutionPlanValidator.validate(plan);
        if (!validation.valid()) {
            log.warn("[Collaborative] Rejected invalid preplanned graph: {}", validation.errors());
            return null;
        }
        return plan.toIntentGraph();
    }

    static ExecutionPlan buildExecutionPlan(String question, TaskAnalysisResult analysis,
                                            String executionId) {
        if (analysis == null || !analysis.hasSubIntents()) return null;

        String effectiveExecutionId = executionId != null && !executionId.isBlank()
                ? executionId : "local-" + Integer.toUnsignedString(Objects.hashCode(question), 36);
        List<RawPlanNode> rawNodes = new ArrayList<>();
        Map<String, String> dependencyAliases = new LinkedHashMap<>();
        Set<String> assignedIds = new HashSet<>();
        List<Map<String, Object>> subIntents = analysis.getSubIntents();
        for (int i = 0; i < subIntents.size(); i++) {
            Map<String, Object> subIntent = subIntents.get(i);
            String description = Objects.toString(subIntent.get("description"), "").trim();
            if (description.isEmpty()) continue;

            String requestedId = Objects.toString(subIntent.get("id"), "").trim();
            String nodeId = isValidNodeId(requestedId) && assignedIds.add(requestedId)
                    ? requestedId : nextNodeId(assignedIds);
            assignedIds.add(nodeId);
            String intent = Objects.toString(subIntent.get("intent"), "").trim();
            rawNodes.add(new RawPlanNode(nodeId, intent, description, subIntent));

            registerDependencyAlias(dependencyAliases, nodeId, nodeId);
            registerDependencyAlias(dependencyAliases, requestedId, nodeId);
            registerDependencyAlias(dependencyAliases, intent, nodeId);
            registerDependencyAlias(dependencyAliases,
                    Objects.toString(subIntent.get("order"), ""), nodeId);
            registerDependencyAlias(dependencyAliases, description, nodeId);
        }

        Set<String> recommendationNodeIds = rawNodes.stream()
                .filter(node -> "RECOMMEND_PRODUCT".equals(normalizeDeclaredOperation(
                        node.source().get("operation"), node.source().get("target_agent"),
                        analysis.getIntentCategory())))
                .map(RawPlanNode::nodeId)
                .collect(java.util.stream.Collectors.toSet());
        List<ExecutionPlan.TaskNode> nodes = new ArrayList<>();
        for (RawPlanNode rawNode : rawNodes) {
            Map<String, Object> subIntent = rawNode.source();
            String description = rawNode.description();
            String intent = rawNode.intent();

            String operation = normalizeDeclaredOperation(subIntent.get("operation"),
                    subIntent.get("target_agent"), analysis.getIntentCategory());
            boolean explainOrderPreparation = "EXPLAIN_ORDER_REQUIREMENTS".equals(operation)
                    || (analysis.isNeedsClarification() && "CREATE_ORDER".equals(operation));
            if (explainOrderPreparation) {
                description = "说明后续下单所需信息（只说明，不执行）：明确列出具体商品及金额、"
                        + "收货人姓名、联系电话、收货地址；用户ID由登录态提供，商品类型可选。"
                        + "如果用户尚未选定商品，要求用户从商品查询结果中选择。";
            }
            ExecutionPlan.Domain domain = explainOrderPreparation
                    ? ExecutionPlan.Domain.BUILTIN_ORDER_PREPARATION
                    : resolveDeclaredDomain(subIntent.get("target_agent"), analysis.getIntentCategory());
            String agent = domain.agentName();
            List<String> dependencies = resolveSubIntentDependencies(
                    subIntent.get("depends_on"), dependencyAliases);
            boolean recommendationDerivedOrder = "CREATE_ORDER".equals(operation)
                    && dependencies.stream().anyMatch(recommendationNodeIds::contains);
            String criteria = Objects.toString(subIntent.get("success_criteria"), "").trim();
            if (criteria.isEmpty()) criteria = null;

            String scopedDescription = buildScopedDescription(
                    description, agent, question, analysis.getActionConstraints());
            ExecutionPlan.AccessMode accessMode = !explainOrderPreparation
                    && ("WRITE".equalsIgnoreCase(Objects.toString(subIntent.get("access_mode"), ""))
                    || isWriteOperation(operation))
                    ? ExecutionPlan.AccessMode.WRITE : ExecutionPlan.AccessMode.READ;
            boolean approvalRequired = accessMode == ExecutionPlan.AccessMode.WRITE
                    && (recommendationDerivedOrder
                    || booleanValue(subIntent.get("human_approval_required"))
                    || requiresApproval(operation, analysis.getRiskFlags()));
            String idempotencyKey = accessMode == ExecutionPlan.AccessMode.WRITE
                    ? effectiveExecutionId + ":" + rawNode.nodeId() : null;

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("description", description);
            if (analysis.getEntities() != null) input.putAll(analysis.getEntities());
            ExecutionPlan.MergePolicy mergePolicy = mergePolicy(
                    subIntent.get("merge_policy"), operation);
            boolean required = subIntent.containsKey("required")
                    ? booleanValue(subIntent.get("required"))
                    : !booleanValue(subIntent.get("optional"));
            String outputSchema = Objects.toString(
                    subIntent.get("output_schema"), "").trim();
            if (outputSchema.isEmpty()) outputSchema = null;
            Map<String, String> inputBindings = sanitizeInputBindings(
                    rawNode.nodeId(), dependencies, input,
                    stringMap(subIntent.get("input_bindings")));

            nodes.add(new ExecutionPlan.TaskNode(
                    rawNode.nodeId(), domain, operation, scopedDescription, input,
                    dependencies, accessMode, analysis.getMissingSlots(), idempotencyKey,
                    approvalRequired, criteria, mergePolicy, required,
                    outputSchema, inputBindings));
        }

        return nodes.isEmpty() ? null : new ExecutionPlan(effectiveExecutionId, question,
                analysis.getActionConstraints(), nodes);
    }

    private static String buildScopedDescription(String description, String agent,
                                                 String question, List<String> actionConstraints) {
        // Product is a read-only domain. Its deterministic discovery/RAG layer treats the
        // incoming text as the actual search query, so orchestration prose must never leak in.
        if ("product".equals(agent)) {
            return description;
        }
        StringBuilder scoped = new StringBuilder("仅执行这个子任务：").append(description);
        if ("general".equals(agent)) {
            if (actionConstraints != null && !actionConstraints.isEmpty()) {
                scoped.append("\n\n[操作约束]");
                actionConstraints.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .forEach(value -> scoped.append("\n- ").append(value));
            }
        } else {
            scoped.append("\n\n[完整用户请求与全局约束]\n").append(question);
        }
        return scoped.append("\n不得违反操作约束，也不得替用户补造缺失参数。").toString();
    }

    static String builtInOrderPreparationReply() {
        return """
                下单前还需要确认以下信息：
                - 具体商品及成交金额（请从查询结果中选择一款）
                - 收货人姓名
                - 联系电话
                - 收货地址

                用户 ID 由当前登录账号自动获取，商品类型为可选信息。信息补齐并经你确认后才能创建订单；本次只做查询和说明，未创建订单，也未执行支付、退款或取消。""";
    }

    static String mergeOrderPreparationResults(List<SubTaskResult> results) {
        return results.stream()
                .filter(SubTaskResult::isSuccess)
                .map(SubTaskResult::getResult)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private static ExecutionPlan.Domain resolveDeclaredDomain(Object targetAgent,
                                                               String intentCategory) {
        String declared = Objects.toString(targetAgent, "").trim().toLowerCase(Locale.ROOT);
        return switch (declared) {
            case "product" -> ExecutionPlan.Domain.PRODUCT;
            case "order" -> ExecutionPlan.Domain.ORDER;
            case "general" -> ExecutionPlan.Domain.GENERAL;
            default -> switch (Objects.toString(intentCategory, "").trim().toUpperCase(Locale.ROOT)) {
                case "PRODUCT" -> ExecutionPlan.Domain.PRODUCT;
                case "ORDER" -> ExecutionPlan.Domain.ORDER;
                default -> ExecutionPlan.Domain.GENERAL;
            };
        };
    }

    private static List<String> resolveSubIntentDependencies(
            Object rawDependencies, Map<String, String> aliases) {
        if (rawDependencies == null) return List.of();
        List<Object> values = rawDependencies instanceof Collection<?> collection
                ? new ArrayList<>(collection) : List.of(rawDependencies);
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (Object rawValue : values) {
            if (rawValue == null) continue;
            if (rawValue instanceof Map<?, ?> map) {
                Object nested = map.containsKey("id") ? map.get("id")
                        : map.containsKey("intent") ? map.get("intent") : map.get("order");
                if (nested != null) resolveDependencyToken(nested.toString(), aliases, resolved);
                continue;
            }
            for (String token : rawValue.toString().split("[,，;；]")) {
                resolveDependencyToken(token, aliases, resolved);
            }
        }
        return List.copyOf(resolved);
    }

    private static void resolveDependencyToken(String rawToken, Map<String, String> aliases,
                                               Set<String> resolved) {
        String token = rawToken != null ? rawToken.trim() : "";
        if (token.isEmpty() || "none".equalsIgnoreCase(token) || "null".equalsIgnoreCase(token)) return;
        String exact = aliases.get(normalizeAlias(token));
        if (exact != null) {
            resolved.add(exact);
            return;
        }
        String normalized = normalizeAlias(token);
        List<String> fuzzyMatches = aliases.entrySet().stream()
                .filter(entry -> normalized.contains(entry.getKey()) || entry.getKey().contains(normalized))
                .map(Map.Entry::getValue).distinct().toList();
        if (fuzzyMatches.size() == 1) {
            resolved.add(fuzzyMatches.get(0));
        } else {
            // Keep an unresolved stable token so validation rejects the unsafe plan and falls
            // back to the planner instead of silently changing execution order.
            resolved.add(token);
        }
    }

    private static String normalizeDeclaredOperation(Object operation, Object targetAgent,
                                                     String intentCategory) {
        String declared = Objects.toString(operation, "").trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = Set.of("DISCOVER_PRODUCTS", "QUERY_PRODUCT",
                "ANALYZE_PRODUCT_DATA", "RECOMMEND_PRODUCT", "QUERY_ORDER", "QUERY_PAYMENT_PENDING", "CREATE_ORDER",
                "CANCEL_ORDER", "REFUND_ORDER", "PAY_ORDER", "SHIP_ORDER",
                "TRACK_LOGISTICS", "CONFIRM_DELIVERY", "ANSWER",
                "EXPLAIN_ORDER_REQUIREMENTS", "EXPLAIN_ORDER_LIFECYCLE");
        if (allowed.contains(declared)) return declared;
        return switch (resolveDeclaredDomain(targetAgent, intentCategory)) {
            case PRODUCT -> "QUERY_PRODUCT";
            case ORDER -> "QUERY_ORDER";
            case GENERAL -> "ANSWER";
            case BUILTIN_ORDER_PREPARATION -> "EXPLAIN_ORDER_REQUIREMENTS";
        };
    }

    private static boolean isWriteOperation(String operation) {
        return Set.of("CREATE_ORDER", "CANCEL_ORDER", "REFUND_ORDER", "PAY_ORDER",
                        "SHIP_ORDER", "CONFIRM_DELIVERY")
                .contains(operation);
    }

    private static boolean requiresApproval(String operation, List<String> riskFlags) {
        if (Set.of("PAY_ORDER", "REFUND_ORDER", "CANCEL_ORDER", "SHIP_ORDER",
                "CONFIRM_DELIVERY").contains(operation)) return true;
        return riskFlags != null && riskFlags.stream().filter(Objects::nonNull)
                .anyMatch(flag -> flag.contains("二次确认") || flag.contains("人工审批"));
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool
                : value != null && Boolean.parseBoolean(value.toString());
    }

    private static ExecutionPlan.MergePolicy mergePolicy(Object value, String operation) {
        String declared = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        if (!declared.isEmpty()) {
            try {
                return ExecutionPlan.MergePolicy.valueOf(declared);
            } catch (IllegalArgumentException ignored) {
                // Invalid model output falls through to the deterministic operation default.
            }
        }
        return Set.of("ANSWER", "EXPLAIN_ORDER_REQUIREMENTS", "EXPLAIN_ORDER_LIFECYCLE")
                .contains(operation)
                ? ExecutionPlan.MergePolicy.APPEND
                : ExecutionPlan.MergePolicy.STRUCTURED;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null
                    && !key.toString().isBlank() && !item.toString().isBlank()) {
                result.put(key.toString().trim(), item.toString().trim());
            }
        });
        return result;
    }

    /**
     * Repairs only bindings that are redundant with a trusted entity already extracted from
     * the original request. Models occasionally bind a root node's {@code order_id} to that
     * same node's future output. Rejecting the entire typed plan sends execution to the legacy
     * untyped planner; blindly accepting it would create a cycle. Dropping the redundant
     * binding preserves the explicit entity while every binding without a trusted fallback is
     * left intact so normal validation still rejects unsafe plans.
     */
    private static Map<String, String> sanitizeInputBindings(
            String nodeId, List<String> dependencies, Map<String, Object> input,
            Map<String, String> bindings) {
        if (bindings == null || bindings.isEmpty()) return Map.of();
        Set<String> declaredDependencies = dependencies != null
                ? Set.copyOf(dependencies) : Set.of();
        Map<String, String> sanitized = new LinkedHashMap<>();
        bindings.forEach((target, expression) -> {
            boolean validSource = false;
            try {
                InputBindingExpression.Parsed parsed = InputBindingExpression.parse(expression);
                validSource = declaredDependencies.contains(parsed.sourceNodeId());
            } catch (IllegalArgumentException ignored) {
                // Keep malformed expressions unless the explicit input below makes them redundant.
            }
            Object explicitValue = input != null ? input.get(target) : null;
            if (!validSource && hasUsablePlanInput(explicitValue)) {
                log.warn("[Collaborative] Dropped redundant invalid input binding: "
                                + "node={}, target={}, expression={}",
                        nodeId, target, expression);
                return;
            }
            sanitized.put(target, expression);
        });
        return Map.copyOf(sanitized);
    }

    private static boolean hasUsablePlanInput(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Collection<?> collection) return !collection.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private static boolean isValidNodeId(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}");
    }

    private static String nextNodeId(Set<String> assignedIds) {
        int index = assignedIds.size() + 1;
        while (assignedIds.contains("t" + index)) index++;
        return "t" + index;
    }

    private static void registerDependencyAlias(Map<String, String> aliases,
                                                String alias, String nodeId) {
        String normalized = normalizeAlias(alias);
        if (!normalized.isEmpty()) aliases.putIfAbsent(normalized, nodeId);
    }

    private static String normalizeAlias(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private record RawPlanNode(String nodeId, String intent, String description,
                               Map<String, Object> source) {
    }

    /**
     * 调用 Agent → 构建 RoutingResult → finalizeRouting 后处理。
     */
    public RoutingResult callAgentAndFinalize(String agentName, String agentQuestion,
                                               double confidence, String intentTag,
                                               RouteRequest request, String rawQuestion,
                                               EmotionCheckResult emotion) {
        String agentReply;
        DomainQualityResult domainQuality;
        if (isFallbackAgent(agentName)) {
            agentReply = fallbackAgentService.execute(
                    agentQuestion, request.getUserId(), request.getDeviceLocation());
            agentName = RouterFallbackAgentService.AGENT_NAME;
            domainQuality = DomainQualityResult.unknown();
        } else {
            var agentResult = agentCallerService.callAgentDetailed(
                    agentName, agentQuestion, request.getUserId(), request.getRequestId());
            agentReply = agentResult.getResponse();
            domainQuality = agentResult.getDomainQuality();
        }
        if (agentReply == null || agentReply.isBlank()) {
            return null;
        }
        RoutingResult result = RoutingResult.builder()
                .result(agentReply)
                .agentName(agentName)
                .confidence(confidence)
                .intentTag(intentTag)
                .domainQuality(domainQuality)
                .build();
        return routeFinalizer.finalizeRouting(result, request, rawQuestion, emotion);
    }

    // ==================== 内联兜底 ====================

    /**
     * Tool Registry-backed local fallback agent, then a static safety reply.
     */
    public RoutingResult inlineFallback(String question, EmotionCheckResult emotion) {
        return inlineFallback(question, null, null, emotion);
    }

    public RoutingResult inlineFallback(String question, Long userId,
                                        com.example.smartassistant.common.location.DeviceLocation deviceLocation,
                                        EmotionCheckResult emotion) {
        try {
            String localReply = fallbackAgentService.execute(question, userId, deviceLocation);
            if (localReply != null && !localReply.isBlank()) {
                return routeFinalizer.applyEmotion(RoutingResult.builder()
                        .result(localReply).agentName(RouterFallbackAgentService.AGENT_NAME).confidence(0.2).build(), emotion);
            }
        } catch (Exception e) {
            log.warn("[Exec] 本地推理兜底失败: {}", e.getMessage());
        }

        int idx = fallbackIndex.getAndUpdate(i -> (i + 1) % FALLBACK_MESSAGES.size());
        return routeFinalizer.applyEmotion(RoutingResult.builder()
                .result(FALLBACK_MESSAGES.get(idx)).agentName("none").confidence(0.0).build(), emotion);
    }

    private static boolean isFallbackAgent(String agentName) {
        return agentName == null
                || agentName.isBlank()
                || "general".equalsIgnoreCase(agentName)
                || "general_agent".equalsIgnoreCase(agentName)
                || "builtin_fallback".equalsIgnoreCase(agentName)
                || RouterFallbackAgentService.AGENT_NAME.equalsIgnoreCase(agentName);
    }

    // ==================== SSE 事件 ====================

    private void storeSseEvent(String eventsKey, String type, String content, String agent) {
        if (eventsKey == null || redisTemplate == null) return;
        int count = sseEventCounts.merge(eventsKey, 1, Integer::sum);
        if (count > MAX_SSE_EVENTS_PER_KEY) {
            if (count == MAX_SSE_EVENTS_PER_KEY + 1) {
                log.warn("[Exec] SSE 事件数已达上限 ({}), 停止缓存: key={}", MAX_SSE_EVENTS_PER_KEY, eventsKey);
            }
            return;
        }
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"type\":\"").append(type).append("\"");
            if (content != null) {
                json.append(",\"content\":\"").append(escapeJson(content)).append("\"");
            }
            if (agent != null) {
                json.append(",\"agent\":\"").append(agent).append("\"");
            }
            json.append("}");
            String payload = json.toString();
            redisTemplate.opsForList().rightPush(eventsKey, payload);
            redisTemplate.expire(eventsKey, SSE_EVENTS_TTL_SECONDS, TimeUnit.SECONDS);
            storeSseStreamEvent(eventsKey, payload);
        } catch (Exception e) {
            log.warn("[Exec] 存储 SSE 事件失败: {}", e.getMessage());
            sseEventCounts.computeIfPresent(eventsKey, (k, v) -> v > 0 ? v - 1 : 0);
        }
    }

    private void storeSseStreamEvent(String eventsKey, String payload) {
        try {
        if (!eventsKey.startsWith(RoutingKeys.SSE_EVENTS_PREFIX)) return;
        String streamKey = RoutingKeys.SSE_STREAM_PREFIX
                + eventsKey.substring(RoutingKeys.SSE_EVENTS_PREFIX.length());
            redisTemplate.opsForStream().add(streamKey, Map.of("payload", payload));
            redisTemplate.opsForStream().trim(streamKey, MAX_SSE_EVENTS_PER_KEY, true);
            redisTemplate.expire(streamKey, SSE_EVENTS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // List 是滚动部署兼容通道；Stream 写入失败不能使主执行链路失败。
            log.debug("[Exec] 写入 SSE Stream 失败: key={}, error={}", eventsKey, e.getMessage());
        }
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
