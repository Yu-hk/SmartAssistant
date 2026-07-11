package com.example.smartassistant.common.tool.client;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolGatewayToolCallback;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tool Registry 客户端 SDK。
 * <p>
 * 供各 Agent 模块通过 HTTP 查询 Registry 服务获取工具定义。
 * 内置本地缓存（可配置 TTL），避免每次会话都远程调用。
 * Registry 不可用时返回本地缓存快照，保证降级可用。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * List<ToolDefinition> tools = registryClient.getToolDefinitions("ORDER");
 * String descriptions = registryClient.getToolDescriptions("ORDER");
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
public class ToolRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryClient.class);

    private final ToolRegistryProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ToolGateway gateway;
    private final ToolRegistry toolRegistry;

    /** 本地缓存：tag → (definitions, timestamp) */
    private final Map<String, CacheEntry<List<ToolDefinition>>> definitionCache = new ConcurrentHashMap<>();

    /** 本地缓存：请求 ID 缓存 */
    private final Map<String, CacheEntry<String>> responseCache = new ConcurrentHashMap<>();

    /**
     * 缓存条目（带过期时间）。
     */
    private static class CacheEntry<T> {
        final T value;
        final Instant createdAt;

        CacheEntry(T value) {
            this.value = value;
            this.createdAt = Instant.now();
        }

        boolean isExpired(long ttlSeconds) {
            return Duration.between(createdAt, Instant.now()).getSeconds() >= ttlSeconds;
        }
    }

    public ToolRegistryClient(ToolRegistryProperties properties, ObjectMapper objectMapper,
                              ToolGateway gateway, ToolRegistry toolRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.gateway = gateway;
        this.toolRegistry = toolRegistry;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    // ==================== 公开 API ====================

    /**
     * 按标签获取可用工具定义列表（带本地缓存 + 降级）。
     *
     * @param tag 域标签，如 "ORDER" / "PRODUCT" / "GENERAL"
     * @return 匹配的工具定义列表，Registry 不可用时返回本地缓存快照
     */
    public List<ToolDefinition> getToolDefinitions(String tag) {
        // 1. 尝试从缓存获取
        CacheEntry<List<ToolDefinition>> cached = definitionCache.get(tag);
        if (cached != null && !cached.isExpired(properties.getCacheTtlSeconds())) {
            log.debug("[ToolRegistryClient] 缓存命中: tag={}, count={}", tag, cached.value.size());
            return cached.value;
        }

        // 2. 远程查询
        try {
            List<ToolDefinition> definitions = fetchFromRegistry(tag);
            definitionCache.put(tag, new CacheEntry<>(definitions));
            log.info("[ToolRegistryClient] 远程查询成功: tag={}, count={}", tag, definitions.size());
            return definitions;
        } catch (Exception e) {
            log.warn("[ToolRegistryClient] 远程查询失败: tag={}, error={}", tag, e.getMessage());

            // 3. 降级：返回过期缓存
            if (cached != null) {
                log.warn("[ToolRegistryClient] 降级使用缓存: tag={}, count={}", tag, cached.value.size());
                return cached.value;
            }

            // 4. 无缓存可用，返回空列表
            log.warn("[ToolRegistryClient] 无可用缓存，返回空列表: tag={}", tag);
            return Collections.emptyList();
        }
    }

    /**
     * 获取格式化的工具描述列表（用于注入 LLM system prompt）。
     * <p>
     * 格式示例：
     * <pre>
     * order.queryOrder: 查询订单详情 (READ_ONLY)
     * order.refundOrder: 退款操作 (DESTRUCTIVE, 需审批)
     * </pre>
     *
     * @param tag 域标签
     * @return 格式化字符串，每行一个工具
     */
    public String getToolDescriptions(String tag) {
        List<ToolDefinition> definitions = getToolDefinitions(tag);
        if (definitions.isEmpty()) {
            return "（当前无可用工具）";
        }

        return definitions.stream()
                .map(def -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(def.getName()).append(": ").append(def.getDescription());
                    sb.append(" (").append(def.getRiskLevel());
                    if (def.isNeedsApproval()) sb.append(", 需审批");
                    sb.append(")");
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 注册工具定义到 Registry 服务。失败时自动降级到本地注册。
     * <p>
     * Phase 4 迁移模式：优先远程注册，Registry 不可用时回退到本地
     * {@link ToolRegistry}，保证启动不因 Registry 不可用而失败。
     * </p>
     *
     * @param definition   工具定义
     * @param localRegistry 本地注册中心（降级目标）
     */
    public void registerWithFallback(ToolDefinition definition, ToolRegistry localRegistry) {
        try {
            register(definition);
        } catch (Exception e) {
            log.warn("[ToolRegistryClient] 远程注册失败，降级本地: name={}, error={}",
                    definition.getName(), e.getMessage());
            localRegistry.register(definition);
        }
    }

    /**
     * 注册工具定义到 Registry 服务。
     *
     * @param definition 工具定义
     */
    public void register(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        try {
            String json = objectMapper.writeValueAsString(definition);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl() + "/api/tools/register"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("[ToolRegistryClient] 注册成功: name={}", definition.getName());
            } else {
                log.warn("[ToolRegistryClient] 注册失败: name={}, status={}, body={}",
                        definition.getName(), response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("[ToolRegistryClient] 注册异常: name={}, error={}",
                    definition.getName(), e.getMessage());
        }
    }

    /**
     * 按标签获取 ToolCallback 列表。
     * <p>
     * 从 Registry 查询已注册的工具定义，然后从提供的工具 Bean 中
     * 组装出匹配的 {@link ToolCallback}。未在 Registry 中注册的工具不会被包含。
     * </p>
     * <p>
     * Registry 不可用时，回退到组装所有提供的 Bean（宽松降级）。
     * </p>
     *
     * @param tag       域标签，如 "ORDER" / "PRODUCT" / "GENERAL"
     * @param toolBeans 工具 Bean（带有 {@code @Tool} 标注方法的 Spring Bean）
     * @return 匹配的 ToolCallback 列表
     */
    public List<ToolCallback> getToolCallbacks(String tag, Object... toolBeans) {
        // 1. 组装所有提供的 Bean 为 ToolCallback
        List<ToolCallback> allCallbacks = assembleCallbacks(toolBeans);

        // 2. 从 Registry 获取已注册的 SHARED/EXTENSION 工具名集合；中心不可用则空集
        final Set<String> registeredNames = resolveRegisteredNames(tag);

        // 3. 三层 merge：CORE 常驻，SHARED/EXTENSION 需中心 allowlist；
        //    所有回调经 ToolGatewayToolCallback 包裹，接入统一治理链（P0 接线）
        List<ToolCallback> merged = allCallbacks.stream()
                .filter(tc -> {
                    String name = tc.getToolDefinition().name();
                    // 本地注册表持有项目级 ToolDefinition（含分层 tier）；
                    // 未注册的本地 @Tool 方法视为 CORE 层（常驻、不依赖中心）。
                    ToolDefinition def = toolRegistry.get(name);
                    ToolTier tier = (def != null && def.getToolTier() != null)
                            ? def.getToolTier() : ToolTier.CORE;
                    return tier == ToolTier.CORE || registeredNames.contains(name);
                })
                .map(tc -> new ToolGatewayToolCallback(tc, gateway, null))
                .collect(Collectors.toList());

        log.info("[ToolRegistryClient] getToolCallbacks({}): {} beans → {} merged (gateway-wrapped)",
                tag, toolBeans.length, merged.size());
        return merged;
    }

    /**
     * 将 Spring Bean 中的 {@code @Tool} 方法组装为 ToolCallback 列表。
     */
    private List<ToolCallback> assembleCallbacks(Object... toolBeans) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (toolBeans == null) return callbacks;
        for (Object bean : toolBeans) {
            if (bean == null) continue;
            ToolCallback[] beans = MethodToolCallbackProvider.builder()
                    .toolObjects(bean).build().getToolCallbacks();
            callbacks.addAll(Arrays.asList(beans));
        }
        return callbacks;
    }

    /**
     * 从 Registry 查询已注册的工具名集合（SHARED/EXTENSION 的 allowlist）。
     * <p>Registry 不可用时返回空集：SHARED/EXTENSION 工具的治理依赖中心，
     * 中心宕机即降级不可用；CORE 工具由上层 merge 逻辑保证始终可用，不受影响。</p>
     */
    private Set<String> resolveRegisteredNames(String tag) {
        try {
            List<ToolDefinition> registeredDefs = fetchFromRegistry(tag);
            return registeredDefs.stream()
                    .map(ToolDefinition::getName)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[ToolRegistryClient] getToolCallbacks({}) Registry 不可用，仅 CORE 工具可用: {}",
                    tag, e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 刷新所有标签的本地缓存。
     */
    public void refresh() {
        definitionCache.clear();
        responseCache.clear();
        log.info("[ToolRegistryClient] 缓存已刷新");
    }

    /**
     * 获取缓存状态。
     *
     * @return 每个标签的缓存条目数和剩余 TTL 秒数
     */
    public Map<String, String> getCacheStats() {
        Map<String, String> stats = new LinkedHashMap<>();
        definitionCache.forEach((tag, entry) -> {
            long remaining = properties.getCacheTtlSeconds()
                    - Duration.between(entry.createdAt, Instant.now()).getSeconds();
            stats.put(tag, entry.value.size() + " tools, " + Math.max(0, remaining) + "s remaining");
        });
        return stats;
    }

    // ==================== 内部方法 ====================

    /**
     * 远程查询 Registry 服务。
     */
    private List<ToolDefinition> fetchFromRegistry(String tag) throws Exception {
        String url = properties.getUrl() + "/api/tools?tags=" + tag + "&status=ACTIVE";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Registry 返回非 200: " + response.statusCode());
        }

        // 解析 ApiResponse 结构
        Map<String, Object> root = objectMapper.readValue(response.body(),
                new TypeReference<Map<String, Object>>() {});
        if (root.containsKey("data")) {
            Object data = root.get("data");
            if (data instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawList = (List<Map<String, Object>>) data;
                return rawList.stream()
                        .map(m -> objectMapper.convertValue(m, ToolDefinition.class))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
