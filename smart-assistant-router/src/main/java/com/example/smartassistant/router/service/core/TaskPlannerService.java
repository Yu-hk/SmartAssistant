package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.gateway.llm.AgentLLMGateway;
import com.example.smartassistant.common.gateway.llm.LLMCallConfig;
import com.example.smartassistant.common.gateway.llm.LLMCallResult;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTask;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.taskanalysis.IntentDef;
import com.example.smartassistant.router.service.taskanalysis.IntentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 任务分解服务。
 * 通过 AgentDiscoveryService 动态发现可用 Agent，无需硬编码。
 * <p>
 * 支持两种分解模式：
 * <ul>
 *   <li>{@link #plan(String)} — 旧版扁平列表（已弃用）</li>
 *   <li>{@link #planToGraph(String)} — 新版图结构，含依赖关系</li>
 * </ul>
 * </p>
 */
@Component
public class TaskPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlannerService.class);
    private static final Pattern STANDARD_PATTERN = Pattern.compile("(\\w+)\\|([^|]+)\\|([^|\\n]+)");
    private static final Pattern GRAPH_PATTERN = Pattern.compile("(\\w+)\\|([^|]+)\\|([^|\\n]+)\\|([^|\\n]+)");
    private static final Pattern FLEXIBLE_PATTERN = Pattern.compile("^(.+?)\\|(.+)\\|(.+)$", Pattern.MULTILINE);

    private final AgentDiscoveryService agentDiscovery;
    private final AgentLLMGateway llmGateway;
    private final DeepSeekPlanningClient planningClient;
    private final IntentRetriever intentRetriever;

    @Value("${router.task-planner.timeout-ms:35000}")
    private long plannerTimeoutMs = 35_000L;

    @Value("${router.task-planner.max-tokens:2048}")
    private int plannerMaxTokens = 2048;

    public TaskPlannerService(AgentDiscoveryService agentDiscovery,
                               AgentLLMGateway llmGateway,
                               DeepSeekPlanningClient planningClient,
                               IntentRetriever intentRetriever) {
        this.agentDiscovery = agentDiscovery;
        this.llmGateway = llmGateway;
        this.planningClient = planningClient;
        this.intentRetriever = intentRetriever;
    }

    /**
     * ⭐ 将问题分解为带依赖关系的意图图（DAG）。
     * <p>
     * LLM 输出格式：{@code 子任务ID|描述|助理名|依赖ID列表(逗号分隔,无依赖填none)}
     * 示例：
     * <pre>
     * t1|查询热门商品|product|none
     * t2|查询候选商品库存|product|t1
     * </pre>
     * t2 依赖 t1 的结果（获得候选商品后查询库存）。
     * 无依赖的任务可以并行执行。
     *
     * @param question 用户原始问题
     * @return 意图图（DAG），空节点时返回仅含原始问题的单节点图
     */
    public IntentGraph planToGraph(String question) {
        String agentList = buildCompactAgentList();
        if (agentList.isEmpty()) {
            log.warn("[TaskPlanner] 无可用 Agent，使用整句");
            return createSingleNodeGraph(question, findFallbackAgent(question));
        }

        String fallback = findFallbackAgent(question);
        String prompt = String.format("""
                直接输出任务分配结果，不要解释、不要展示思考过程。
                可用助理（只能从中选择）：
                %s

                每行严格使用：t编号|简洁任务描述|助理名|依赖ID（无依赖填none）|验收标准。
                最多6行；每行只表达一个原子目标；按助理能力做语义分配。
                保留“不要创建/不要支付”等否定约束；只问流程时只能规划说明，不得规划写操作。
                依赖只能引用前面已定义的节点；不匹配时使用兜底助理：%s。

                用户请求：%s
                """, agentList, fallback, question);

        try {
            String response = callPlanner(prompt);
            List<IntentGraph.IntentNode> nodes = parseGraphTasks(response);
            if (nodes.isEmpty()) {
                log.warn("[TaskPlanner] LLM 返回格式异常，使用整句。响应: {}", response);
                return createSingleNodeGraph(question, fallback);
            }
            Set<String> allowedAgents = getHealthyAgentNames();
            var validation = ExecutionPlanValidator.validateGraphNodes(nodes, allowedAgents);
            if (!validation.valid()) {
                log.warn("[TaskPlanner] 拒绝不安全的 LLM 计划，降级为单节点: {}", validation.errors());
                return createSingleNodeGraph(question, fallback);
            }
            nodes = preserveProductContext(nodes, question);
            log.info("[TaskPlanner] 图分解完成: {} 个节点, hasDeps={}",
                    nodes.size(), nodes.stream().anyMatch(n -> !n.getDependsOn().isEmpty()));
            return new IntentGraph(question, nodes);
        } catch (Exception e) {
            log.warn("[TaskPlanner] LLM 图分解失败: {}", e.getMessage());
            return createSingleNodeGraph(question, fallback);
        }
    }

    static List<IntentGraph.IntentNode> preserveProductContext(
            List<IntentGraph.IntentNode> nodes, String originalQuestion) {
        if (nodes == null || nodes.isEmpty() || originalQuestion == null || originalQuestion.isBlank()) {
            return nodes == null ? List.of() : nodes;
        }
        return nodes.stream().map(node -> {
            String agent = node.getTargetAgent();
            if (agent == null || !agent.toLowerCase(Locale.ROOT).contains("product")) {
                return node;
            }
            String scoped = node.getDescription()
                    + "\n\n[用户原始商品需求与约束]\n" + originalQuestion;
            return new IntentGraph.IntentNode(
                    node.getId(), scoped, node.getTargetAgent(), node.getDependsOn(),
                    node.getSuccessCriteria(), node.getConditionalDeps(),
                    node.isHumanApprovalRequired(), node.getOperation(), node.getInput(),
                    node.getConstraints(), node.getIdempotencyKey());
        }).toList();
    }

    /**
     * 旧版扁平任务分解（已弃用）。
     * <p>
     * 请使用 {@link #planToGraph(String)} 替代，后者返回带依赖关系的图结构。
     *
     * @deprecated 使用 {@link #planToGraph(String)} 替代
     */
    @Deprecated
    public List<SubTask> plan(String question) {
        String agentList = buildAgentList();
        if (agentList.isEmpty()) {
            log.warn("[TaskPlanner] 无可用 Agent，使用整句");
            return List.of(new SubTask("t1", question, findFallbackAgent(question)));
        }

        String fallback = findFallbackAgent(question);
        String prompt = String.format("""
                将用户的问题分配给最合适的助理。

                助理（只能从以下选择）：
                %s

                输出格式（每行一条）：子任务ID|描述|助理名
                示例：t1|查询订单状态|order_agent

                要求：只能从上面的助理名单中选择，不要自创。不匹配时使用兜底：%s

                用户：%s
                """, agentList, fallback, question);

        try {
            String response = callPlanner(prompt);
            List<SubTask> tasks = parseTasks(response);
            if (tasks.isEmpty()) {
                log.warn("[TaskPlanner] LLM 返回格式异常，使用整句。响应: {}", response);
                return List.of(new SubTask("t1", question, fallback));
            }
            return tasks;
        } catch (Exception e) {
            log.warn("[TaskPlanner] LLM 分解失败: {}", e.getMessage());
            return List.of(new SubTask("t1", question, fallback));
        }
    }

    // ==================== 图格式解析 ====================

    /**
     * 解析四段/五段式 LLM 输出 {@code id|desc|agent|deps} 或 {@code id|desc|agent|deps|successCriteria}
     * 为图节点列表。
     * <p>
     * 后向兼容：当 LLM 输出旧格式 {@code id|desc|agent} 时自动降级（deps 和 successCriteria 为空）。
     */
    private List<IntentGraph.IntentNode> parseGraphTasks(String response) {
        List<IntentGraph.IntentNode> nodes = new ArrayList<>();
        if (response == null || response.isBlank()) return nodes;

        // ⭐ 尝试五段格式：id|desc|agent|deps|successCriteria
        // 使用 limit=5 的 split，确保验收标准中的 | 不被错误截断
        for (String line : response.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\|", 5);
            if (parts.length >= 4) {
                String id = parts[0].trim();
                String desc = parts[1].trim();
                String agent = parts[2].trim();
                String depsStr = parts[3].trim();
                String successCriteria = parts.length >= 5 ? parts[4].trim() : null;
                List<String> deps = parseDeps(depsStr);
                nodes.add(new IntentGraph.IntentNode(id, desc, agent, deps, successCriteria));
            }
        }
        if (!nodes.isEmpty()) return nodes;

        // 降级：尝试旧三段格式 id|desc|agent（deps 和 successCriteria 为空）
        Matcher standardMatcher = STANDARD_PATTERN.matcher(response);
        while (standardMatcher.find()) {
            String id = standardMatcher.group(1).trim();
            String desc = standardMatcher.group(2).trim();
            String agent = standardMatcher.group(3).trim();
            nodes.add(new IntentGraph.IntentNode(id, desc, agent, List.of(), null));
        }
        if (!nodes.isEmpty()) return nodes;

        // 降级：灵活解析
        Matcher flexMatcher = FLEXIBLE_PATTERN.matcher(response);
        while (flexMatcher.find()) {
            String id = flexMatcher.group(1).trim();
            String desc = flexMatcher.group(2).trim();
            String agent = flexMatcher.group(3).trim();
            nodes.add(new IntentGraph.IntentNode(id, desc, agent, List.of(), null));
        }

        return nodes;
    }

    /**
     * 解析依赖字段。
     * "none" / "null" / 空 → 空列表
     * "t1,t3" → ["t1", "t3"]
     */
    private List<String> parseDeps(String depsStr) {
        if (depsStr == null || depsStr.isBlank()
                || "none".equalsIgnoreCase(depsStr.trim())
                || "null".equalsIgnoreCase(depsStr.trim())) {
            return List.of();
        }
        return Arrays.stream(depsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 创建单节点意图图（降级方案）。
     */
    private IntentGraph createSingleNodeGraph(String question, String fallbackAgent) {
        IntentGraph.IntentNode node = new IntentGraph.IntentNode(
                "t1", question, fallbackAgent, List.of(), null);
        return new IntentGraph(question, List.of(node));
    }

    /**
     * ⭐ 重规划：基于已完成结果和失败节点信息重新生成子任务图。
     * <p>
     * 与 {@link #planToGraph(String)} 的区别：
     * <ul>
     *   <li>不使用原始用户问题作为 LLM 输入</li>
     *   <li>使用重规划上下文（已完成结果 + 失败原因）引导 LLM </li>
     *   <li>输出格式保持一致（兼容 parseGraphTasks）</li>
     * </ul>
     * </p>
     *
     * @param replanContext 重规划上下文（含原始问题、已完成结果、失败节点信息）
     * @return 新意图图
     */
    public IntentGraph replan(String replanContext) {
        String agentList = buildAgentList();
        if (agentList.isEmpty()) {
            log.warn("[TaskPlanner] 重规划失败：无可用 Agent");
            return null;
        }

        String fallback = findFallbackAgent(replanContext);
        String prompt = String.format("""
                以下是一个多步骤任务执行过程中的状态。某个子任务执行失败，
                需要你重新规划该失败任务及后续未执行的任务。

                可用的助理（只能从以下选择）：
                %s

                %s

                请为失败的任务和后续未完成任务重新规划。
                输出格式（每行一条）：子任务ID|描述|助理名|依赖ID列表(逗号分隔,无依赖填none)|验收标准
                示例：
                t1_r|查询热门商品|product|none|返回至少2个候选商品
                t2_r|查询候选商品库存|product|t1_r|返回各商品库存状态

                规则：
                - ID 格式：原ID后加 _r1, _r2 ...（如 t2_r1, t2_r2）
                - 可以拆分原任务为多个更简单的子任务
                - 可以更换 Agent
                - 只能从上面的助理名单中选择
                - 不匹配时使用兜底：%s
                - 验收标准要具体可检查

                请输出规划结果：
                """, agentList, replanContext, fallback);

        try {
            String response = callPlanner(prompt);
            List<IntentGraph.IntentNode> nodes = parseGraphTasks(response);
            if (nodes.isEmpty()) {
                log.warn("[TaskPlanner] 重规划 LLM 返回格式异常。响应: {}", response);
                return null;
            }
            log.info("[TaskPlanner] 重规划完成: {} 个节点", nodes.size());
            return new IntentGraph("replan", nodes);
        } catch (Exception e) {
            log.warn("[TaskPlanner] 重规划 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 原有方法（保留后向兼容） ====================

    /**
     * 从 AgentDiscoveryService 动态构建 Agent 列表文本。
     * 包含 agentName + keywords + capabilities，使 LLM 能精准分配。
     */
    private String buildAgentList() {
        Collection<DiscoveredAgent> agents = agentDiscovery.getCachedAgents();
        if (agents == null || agents.isEmpty()) return "";

        return agents.stream()
                .filter(a -> a.getAgentName() != null && a.getHealthy())
                .map(a -> {
                    String name = a.getAgentName();
                    String keywords = a.getMetadata() != null ? a.getMetadata().getKeywords() : "";
                    String caps = a.getMetadata() != null ? a.getMetadata().getCapabilities() : "";
                    return String.format("  - %s：关键词=[%s] 能力=[%s]", name,
                            keywords != null ? keywords : "",
                            caps != null ? caps : "");
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 首次规划只携带 Agent 名称和精简能力描述，避免重复关键词元数据诱发
     * 长推理。Agent 仍由模型基于注册中心的能力语义选择。
     */
    private String buildCompactAgentList() {
        Collection<DiscoveredAgent> agents = agentDiscovery.getCachedAgents();
        if (agents == null || agents.isEmpty()) return "";
        return agents.stream()
                .filter(agent -> agent.getAgentName() != null && agent.getHealthy())
                .filter(agent -> agent.getMetadata() != null
                        && agent.getMetadata().getAgentType() != null
                        && !agent.getMetadata().getAgentType().isBlank())
                .map(agent -> {
                    String capabilities = agent.getMetadata() != null
                            ? agent.getMetadata().getCapabilities() : "";
                    if (capabilities == null) capabilities = "";
                    capabilities = capabilities.replaceAll("\\s+", " ").trim();
                    if (capabilities.length() > 120) {
                        capabilities = capabilities.substring(0, 120);
                    }
                    return "- " + agent.getAgentName() + "：" + capabilities;
                })
                .collect(Collectors.joining("\n"));
    }

    private Set<String> getHealthyAgentNames() {
        Collection<DiscoveredAgent> agents = agentDiscovery.getCachedAgents();
        if (agents == null) return Set.of();
        return agents.stream()
                .filter(agent -> agent.getAgentName() != null && agent.getHealthy())
                .map(DiscoveredAgent::getAgentName)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 动态查找兜底 Agent（metadata.priority 最高即数值最大的）。
     * 无兜底时返回 null，由调用方处理。
     */
    private String findFallbackAgent(String question) {
        try {
            List<IntentDef> candidates = intentRetriever.retrieve(question, 1);
            if (!candidates.isEmpty()) {
                String semanticAgent = candidates.getFirst().name();
                String registeredAgent = resolveHealthyAgentName(semanticAgent);
                if (registeredAgent != null) {
                    log.info("[TaskPlanner] 模型不可用时按注册能力语义降级: semantic={}, agent={}",
                            semanticAgent, registeredAgent);
                    return registeredAgent;
                }
            }
        } catch (RuntimeException error) {
            log.warn("[TaskPlanner] 注册能力语义降级失败: {}", error.getMessage());
        }

        DiscoveredAgent fallback = agentDiscovery.getCachedAgents().stream()
                .filter(agent -> agent != null && Boolean.TRUE.equals(agent.getHealthy()))
                .filter(agent -> agent.getAgentName() != null && agent.getMetadata() != null)
                .filter(agent -> agent.getMetadata().getAgentType() != null
                        && !agent.getMetadata().getAgentType().isBlank())
                .max(Comparator.comparingInt(agent -> agent.getMetadata().getPriority()))
                .orElse(null);
        return fallback != null ? fallback.getAgentName() : null;
    }

    private String resolveHealthyAgentName(String candidate) {
        String canonicalCandidate = AgentDiscoveryService.canonicalAgentName(candidate);
        return agentDiscovery.getCachedAgents().stream()
                .filter(agent -> agent != null && Boolean.TRUE.equals(agent.getHealthy()))
                .filter(agent -> agent.getAgentName() != null)
                .filter(agent -> AgentDiscoveryService.canonicalAgentName(agent.getAgentName())
                        .equals(canonicalCandidate))
                .map(DiscoveredAgent::getAgentName)
                .findFirst()
                .orElse(null);
    }

    /**
     * 将任务规划限制在路由总时延预算内，并显式限制生成长度。
     * 兼容模型若不设置 maxTokens，可能生成很长的推理内容，使节点尚未开始
     * Consumer 就已超时。
     */
    private String callPlanner(String prompt) {
        int maxTokens = Math.max(256, plannerMaxTokens);
        LLMCallConfig config = new LLMCallConfig(
                null, maxTokens, Duration.ofMillis(Math.max(1L, plannerTimeoutMs)),
                0, 0.1, false);
        LLMCallResult result = llmGateway.call(
                () -> planningClient.complete(prompt, maxTokens),
                planningClient.modelName() + "-task-planner",
                config);
        if (!result.success()) {
            throw new IllegalStateException("task planner model failed: " + result.errorMessage());
        }
        return result.content();
    }

    private List<SubTask> parseTasks(String response) {
        List<SubTask> tasks = new ArrayList<>();
        if (response == null || response.isBlank()) return tasks;

        // ⭐ 先尝试标准格式解析：id|desc|agent
        Pattern standardPattern = Pattern.compile("(\\w+)\\|([^|]+)\\|([^|\\n]+)");
        Matcher matcher = standardPattern.matcher(response);
        while (matcher.find()) {
            String id = matcher.group(1).trim();
            String desc = matcher.group(2).trim();
            String agent = matcher.group(3).trim();
            tasks.add(new SubTask(id, desc, agent));
        }
        
        // ⭐ 如果标准解析成功，直接返回
        if (!tasks.isEmpty()) return tasks;
        
        // ⭐ 标准解析失败（如 LLM 输出多余字段），尝试灵活解析：
        //   取每行最后一个 | 后的内容作为 agent，第一个 | 前的内容作为 id
        Pattern flexiblePattern = Pattern.compile("^(.+?)\\|(.+)\\|(.+)$", Pattern.MULTILINE);
        Matcher flexMatcher = flexiblePattern.matcher(response);
        while (flexMatcher.find()) {
            String id = flexMatcher.group(1).trim();
            String desc = flexMatcher.group(2).trim();
            String agent = flexMatcher.group(3).trim();
            tasks.add(new SubTask(id, desc, agent));
        }
        
        return tasks;
    }
}
