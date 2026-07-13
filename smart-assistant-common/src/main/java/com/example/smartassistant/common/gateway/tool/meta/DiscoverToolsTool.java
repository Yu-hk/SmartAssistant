package com.example.smartassistant.common.gateway.tool.meta;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.mcp.McpRegistryDiscoveryClient;
import com.example.smartassistant.common.gateway.tool.mcp.McpToolCallbackFactory;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * CORE 常驻元工具 — {@code discover_tools}（T2d）。
 * <p>
 * 所有 Agent 始终拥有此工具。当 Agent 需要预载集之外的能力时，调用
 * {@code discover_tools(capabilityQuery=能力名)} 发现并动态加载工具。
 * </p>
 *
 * <h3>护栏</h3>
 * <ul>
 *   <li>会话级已发现缓存去重（同一 capabilityQuery 不重复发现）</li>
 *   <li>{@code maxDiscoveriesPerTurn}（默认 1）：每轮最多发现一次</li>
 *   <li>{@code maxDiscoveriesPerSession}（默认 10）：每会话最多发现十次</li>
 *   <li>{@code maxDynamicTools}（默认 15）：动态工具总数上限</li>
 *   <li>超限拒绝：「已达到发现上限，请基于已有工具继续或升级人工」</li>
 * </ul>
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>护栏检查（去重 + 限频）</li>
 *   <li>调 {@link McpRegistryDiscoveryClient#searchTools} 发现候选工具</li>
 *   <li>用 {@link McpToolCallbackFactory#create(ToolDefinition, ToolCallback)} 组装经
 *       {@link com.example.smartassistant.common.gateway.tool.ToolGatewayToolCallback}
 *       （def 感知重载）包裹的回调</li>
 *   <li>通过注册回调 {@code toolRegistrar} 注入到 {@link com.example.smartassistant.common.agent.SmartReActAgent}
 *       （{@code registerDiscoveredTool}）</li>
 *   <li>记录 {@link DiscoveryEvent}</li>
 *   <li>返回 JSON 结果：{query, matched:[...], injectedTools:[...], source, latencyMs}</li>
 * </ol>
 *
 * <h3>降级</h3>
 * <p>Registry/MCP 不可用时，返回「仅预载可用，无法发现新工具」，不阻断对话。</p>
 */
@Component
public class DiscoverToolsTool {

    private static final Logger log = LoggerFactory.getLogger(DiscoverToolsTool.class);

    private final McpRegistryDiscoveryClient discoveryClient;
    private final McpToolCallbackFactory callbackFactory;
    private final ToolRegistryProperties properties;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    /**
     * 工具回调注册器 — 由 {@link com.example.smartassistant.common.agent.SmartReActAgent}
     * 在创建时通过 {@link #setToolRegistrar(Consumer)} 注入。
     */
    private Consumer<List<ToolCallback>> toolRegistrar;

    /** 会话级已发现能力缓存（capabilityQuery → true），用于会话内去重 */
    private final ConcurrentHashMap<String, Boolean> discoveredCapabilities = new ConcurrentHashMap<>();

    /** 当前会话发现计数器（重置手段：构造新实例或外部调用 reset） */
    private int discoveryCount = 0;

    public DiscoverToolsTool(McpRegistryDiscoveryClient discoveryClient,
                             McpToolCallbackFactory callbackFactory,
                             ToolRegistryProperties properties,
                             ObjectMapper objectMapper,
                             @Autowired(required = false) ObservationRegistry observationRegistry) {
        this.discoveryClient = discoveryClient;
        this.callbackFactory = callbackFactory;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
    }

    /**
     * 设置工具注册器（由 SmartReActAgent 在 AgentConfig 编排时注入）。
     */
    public void setToolRegistrar(Consumer<List<ToolCallback>> toolRegistrar) {
        this.toolRegistrar = toolRegistrar;
    }

    // ==================== @Tool 方法 ====================

    /**
     * 发现并动态加载工具（CORE 常驻元工具，所有 Agent 始终拥有）。
     * <p>
     * 当需要预载集之外的能力时调用此工具。不要臆造不存在的工具名。
     * </p>
     *
     * @param capabilityQuery 能力名或关键词（必填），如 "sql-query"、"refund"
     * @param keywords        辅助关键词（可选），参与 name/description 文本匹配
     * @param matchMode       匹配模式，OR（任一匹配）/ AND（全部匹配），默认 OR
     * @param limit           返回上限（默认 20）
     * @return JSON 字符串：{query, matched:[...], injectedTools:[...], source, latencyMs}
     */
    @Tool(name = "discover_tools", description = """
            发现并动态加载工具。当需要预载集之外的能力时调用此工具。
            入参: capabilityQuery(能力名,必填), keywords(可选关键词), matchMode(OR/AND,默认OR), limit(默认20)
            返回: 匹配的工具列表和已注入的工具名。不要臆造不存在的工具名。""")
    public String discoverTools(String capabilityQuery,
                                String[] keywords,
                                String matchMode,
                                int limit) {
        long startTime = System.currentTimeMillis();
        String effectiveMatchMode = (matchMode != null && !matchMode.isBlank()) ? matchMode.toUpperCase() : "OR";
        int effectiveLimit = limit > 0 ? Math.min(limit, properties.getMaxDynamicTools()) : 20;

        // ═══════════════════════════════════════════════════════════════
        // 护栏 1：已发现能力去重
        // ═══════════════════════════════════════════════════════════════
        if (discoveredCapabilities.putIfAbsent(capabilityQuery, Boolean.TRUE) != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[DiscoverToolsTool] 能力已发现过，返回缓存: capabilityQuery={}", capabilityQuery);
            return buildResultJson(capabilityQuery, List.of(), List.of(), "cache", elapsed);
        }

        // ═══════════════════════════════════════════════════════════════
        // 护栏 2：每会话最大发现次数
        // ═══════════════════════════════════════════════════════════════
        if (discoveryCount >= properties.getMaxDiscoveriesPerSession()) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("[DiscoverToolsTool] 已达每会话发现上限 ({}), 能力={}",
                    properties.getMaxDiscoveriesPerSession(), capabilityQuery);
            return "{\"error\":\"已达到每会话发现上限(" + properties.getMaxDiscoveriesPerSession()
                    + ")，请基于已有工具继续或升级人工\", \"query\":\"" + capabilityQuery
                    + "\", \"source\":\"limit\", \"latencyMs\":" + elapsed + "}";
        }

        discoveryCount++;

        // ═══════════════════════════════════════════════════════════════
        // 执行发现
        // ═══════════════════════════════════════════════════════════════
        List<ToolDefinition> candidates;
        try {
            McpRegistryDiscoveryClient.McpSearchFilter filter =
                    new McpRegistryDiscoveryClient.McpSearchFilter()
                            .functionalCapabilities(List.of(capabilityQuery))
                            .keyword(keywords != null && keywords.length > 0 ? keywords[0] : null)
                            .matchMode(effectiveMatchMode)
                            .limit(effectiveLimit);
            candidates = discoveryClient.searchTools(filter);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("[DiscoverToolsTool] 发现失败（降级返回仅预载可用）: capabilityQuery={}, error={}",
                    capabilityQuery, e.getMessage());
            // ⭐ T2f：需求侧缺口（Registry 不可用，能力无法发现）
            recordGap("DISCOVER_MISS", capabilityQuery, null,
                    "Registry 不可用，无法发现能力: " + capabilityQuery, "degraded");
            // 降级：返回「仅预载可用」，不阻断对话
            return buildResultJson(capabilityQuery, List.of(), List.of(), "registry-unavailable", elapsed);
        }

        // ═══════════════════════════════════════════════════════════════
        // 组装 ToolCallback（经 ToolGatewayToolCallback def 感知重载包裹）
        // ═══════════════════════════════════════════════════════════════
        List<ToolCallback> discoveredCallbacks = new ArrayList<>();
        List<String> matchedNames = new ArrayList<>();
        for (ToolDefinition def : candidates) {
            if (def == null) continue;
            matchedNames.add(def.getName());
            // 护栏 3：动态工具总数上限
            if (discoveredCallbacks.size() >= properties.getMaxDynamicTools()) {
                log.warn("[DiscoverToolsTool] 动态工具数已达上限 ({}), 截断",
                        properties.getMaxDynamicTools());
                break;
            }
            try {
                ToolCallback cb = callbackFactory.create(def, null);
                discoveredCallbacks.add(cb);
            } catch (Exception e) {
                log.warn("[DiscoverToolsTool] 组装 ToolCallback 失败: name={}, error={}",
                        def.getName(), e.getMessage());
                // 单工具失败不影响其他工具
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 通过注册器注入到 SmartReActAgent
        // ═══════════════════════════════════════════════════════════════
        if (!discoveredCallbacks.isEmpty() && toolRegistrar != null) {
            try {
                toolRegistrar.accept(discoveredCallbacks);
            } catch (Exception e) {
                log.warn("[DiscoverToolsTool] 注册已发现工具失败: {}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // ⭐ T2f：需求侧缺口（Registry 中无匹配能力）
        if (matchedNames.isEmpty()) {
            recordGap("DISCOVER_MISS", capabilityQuery, null,
                    "Registry 中无匹配能力: " + capabilityQuery, "logged");
        }

        // ═══════════════════════════════════════════════════════════════
        // 记录 DiscoveryEvent
        // ═══════════════════════════════════════════════════════════════
        List<String> injectedNames = discoveredCallbacks.stream()
                .map(tc -> tc.getToolDefinition().name())
                .collect(Collectors.toList());

        try {
            DiscoveryEvent event = DiscoveryEvent.builder()
                    .capabilityQuery(capabilityQuery)
                    .keywords(keywords)
                    .matchedNames(matchedNames)
                    .source("registry")
                    .latencyMs(elapsed)
                    .discoverToolsInjected(injectedNames.size())
                    .timestamp(Instant.now())
                    .build();
            event.record(observationRegistry);
        } catch (Exception e) {
            log.debug("[DiscoverToolsTool] 记录 DiscoveryEvent 失败: {}", e.getMessage());
        }

        return buildResultJson(capabilityQuery, matchedNames, injectedNames, "registry", elapsed);
    }

    // ==================== 内部方法 ====================

    /**
     * ⭐ T2f：记录需求侧工具缺口事件。
     * <p>统一封装 {@link ToolGapEvent} 的构建与记录，observationRegistry 复用本类注入的实例
     * （为 null 时自动回退 SLF4J 结构化日志）。</p>
     *
     * @param gapType   缺口类型（UNKNOWN_TOOL / DISCOVER_MISS / EMPTY_RESULT）
     * @param capability LLM 尝试的能力（工具名或 capabilityQuery）
     * @param args      LLM 实际传入参数（JSON 原文，可为 null）
     * @param reason    派生原因
     * @param resolution 处置方式（logged / asked_user / degraded）
     */
    private void recordGap(String gapType, String capability, String args,
                           String reason, String resolution) {
        try {
            ToolGapEvent.builder()
                    .gapType(gapType)
                    .attemptedCapability(capability)
                    .attemptedArgs(args)
                    .reason(reason)
                    .desiredResultHint("期望能力: " + capability)
                    .resolution(resolution)
                    .timestamp(Instant.now())
                    .build()
                    .record(observationRegistry);
        } catch (Exception e) {
            log.debug("[DiscoverToolsTool] 记录 ToolGapEvent 失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String buildResultJson(String query, List<String> matchedNames,
                                   List<String> injectedNames, String source, long latencyMs) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("matched", matchedNames);
            result.put("injectedTools", injectedNames);
            result.put("source", source);
            result.put("latencyMs", latencyMs);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[DiscoverToolsTool] JSON 序列化失败，回退纯文本: {}", e.getMessage());
            return "{\"query\":\"" + query + "\",\"matched\":[],\"injectedTools\":[],\"source\":\"error\",\"latencyMs\":"
                    + latencyMs + "}";
        }
    }

    // ==================== 会话生命周期管理 ====================

    /** 重置会话级计数器（新会话/Agent 创建时调用）。 */
    public void resetSession() {
        discoveredCapabilities.clear();
        discoveryCount = 0;
    }

    /** 获取已发现的能力历史（供护栏去重检查）。 */
    public Set<String> getDiscoveredCapabilityHistory() {
        return discoveredCapabilities.keySet();
    }

    /** 获取当前会话发现次数。 */
    public int getDiscoveryCount() {
        return discoveryCount;
    }
}
