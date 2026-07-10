package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.toolregistry.config.RequestIdHolder;
import com.example.smartassistant.toolregistry.model.HealthResult;
import com.example.smartassistant.toolregistry.model.ToolDependRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 工具注册中心服务。
 * <p>
 * 基于内存 {@link ConcurrentHashMap} 的轻量级注册中心，提供工具注册、查询、废弃、
 * 健康检查聚合和依赖追踪功能。后续可迁移到数据库存储。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@Service
public class RegistryService {

    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);

    /** 工具定义存储：name → ToolDefinition */
    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();

    /** 依赖追踪：toolName → List<DependRecord> */
    private final Map<String, List<ToolDependRecord>> dependencies = new ConcurrentHashMap<>();

    /** 错误计数：toolName → errorCount */
    private final Map<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();

    /** 延迟累计：toolName → totalLatencyMs */
    private final Map<String, AtomicLong> totalLatency = new ConcurrentHashMap<>();

    /** 调用次数（Registry 视角）：toolName → callCount */
    private final Map<String, AtomicLong> callCount = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[RegistryService] 工具注册中心已启动，存储模式: ConcurrentHashMap");
    }

    // ==================== 注册 ====================

    /**
     * 注册工具定义。如果已存在同名工具，更新定义并记录日志。
     *
     * @param definition 工具定义
     */
    public void register(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(definition.getName(), "definition.name must not be null");

        ToolDefinition existing = definitions.put(definition.getName(), definition);
        if (existing != null) {
            log.info("[RegistryService][{}] 更新工具: name={}, version={}→{}, status={}→{}",
                    RequestIdHolder.get(), definition.getName(), existing.getVersion(),
                    definition.getVersion(), existing.getStatus(), definition.getStatus());
        } else {
            log.info("[RegistryService][{}] 注册工具: name={}, version={}, tags={}, namespace={}",
                    RequestIdHolder.get(), definition.getName(), definition.getVersion(),
                    Arrays.toString(definition.getTags()), definition.getNamespace());
        }
    }

    /**
     * 批量注册工具。
     */
    public void registerAll(Collection<ToolDefinition> toolDefinitions) {
        toolDefinitions.forEach(this::register);
    }

    // ==================== 查询 ====================

    /**
     * 按名称获取工具定义。
     */
    public Optional<ToolDefinition> get(String toolName) {
        return Optional.ofNullable(definitions.get(toolName));
    }

    /**
     * 查询工具列表，支持按标签、状态、命名空间过滤。
     *
     * @param tags      标签列表（可选，多个标签取交集匹配）
     * @param status    状态过滤（可选）
     * @param namespace 命名空间过滤（可选）
     * @return 匹配的工具定义列表
     */
    public List<ToolDefinition> query(String[] tags, ToolStatus status, String namespace) {
        return definitions.values().stream()
                .filter(def -> status == null || def.getStatus() == status)
                .filter(def -> namespace == null || namespace.equals(def.getNamespace()))
                .filter(def -> tags == null || tags.length == 0 || hasAnyTag(def, tags))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已注册的工具。
     */
    public Collection<ToolDefinition> getAll() {
        return definitions.values();
    }

    /**
     * 获取已注册的工具数量。
     */
    public int size() {
        return definitions.size();
    }

    /**
     * 检查工具是否已注册。
     */
    public boolean isRegistered(String toolName) {
        return definitions.containsKey(toolName);
    }

    // ==================== 废弃 ====================

    /**
     * 废弃工具。将工具状态设为 DEPRECATED，设置替代工具和下线日期。
     *
     * @param toolName     工具名称
     * @param deprecatedBy 替代工具名
     * @param sunsetDate   下线日期（格式 yyyy-MM-dd）
     * @param reason       废弃原因
     * @return 如果废弃成功返回 true
     */
    public boolean deprecate(String toolName, String deprecatedBy, String sunsetDate, String reason) {
        ToolDefinition def = definitions.get(toolName);
        if (def == null) {
            log.warn("[RegistryService] 废弃失败: 工具未注册 name={}", toolName);
            return false;
        }

        ToolDefinition updated = ToolDefinition.builder()
                .name(def.getName())
                .description(def.getDescription())
                .riskLevel(def.getRiskLevel())
                .timeout(def.getTimeout())
                .needsApproval(def.isNeedsApproval())
                .maxRetries(def.getMaxRetries())
                .rateLimit(def.getRateLimit())
                .scopes(def.getScopes())
                .tags(def.getTags())
                .version(def.getVersion())
                .status(ToolStatus.DEPRECATED)
                .namespace(def.getNamespace())
                .ownerTeam(def.getOwnerTeam())
                .endpoint(def.getEndpoint())
                .deprecatedBy(deprecatedBy)
                .sunsetDate(sunsetDate)
                .build();

        definitions.put(toolName, updated);
        log.info("[RegistryService][{}] 废弃工具: name={}, deprecatedBy={}, sunset={}, reason={}",
                RequestIdHolder.get(), toolName, deprecatedBy, sunsetDate, reason);
        return true;
    }

    /**
     * 启用工具。将 DEPRECATED/DISABLED 状态的工具设为 ACTIVE。
     *
     * @param toolName 工具名称
     * @return 如果启用成功返回 true
     */
    public boolean activate(String toolName) {
        ToolDefinition def = definitions.get(toolName);
        if (def == null) return false;

        ToolDefinition updated = ToolDefinition.builder()
                .name(def.getName())
                .description(def.getDescription())
                .riskLevel(def.getRiskLevel())
                .timeout(def.getTimeout())
                .needsApproval(def.isNeedsApproval())
                .maxRetries(def.getMaxRetries())
                .rateLimit(def.getRateLimit())
                .scopes(def.getScopes())
                .tags(def.getTags())
                .version(def.getVersion())
                .status(ToolStatus.ACTIVE)
                .namespace(def.getNamespace())
                .ownerTeam(def.getOwnerTeam())
                .endpoint(def.getEndpoint())
                .deprecatedBy(null)
                .sunsetDate(null)
                .build();

        definitions.put(toolName, updated);
        log.info("[RegistryService] 启用工具: name={}", toolName);
        return true;
    }

    // ==================== 健康检查 ====================

    /**
     * 聚合所有工具的健康状态。
     */
    public HealthResult getHealth() {
        List<ToolDefinition> allTools = new ArrayList<>(definitions.values());

        int total = allTools.size();
        int healthy = 0;
        int degraded = 0;
        int deprecated = 0;
        List<HealthResult.ToolHealthItem> items = new ArrayList<>();

        for (ToolDefinition def : allTools) {
            long ec = errorCounts.getOrDefault(def.getName(), new AtomicLong(0)).get();
            long cc = callCount.getOrDefault(def.getName(), new AtomicLong(0)).get();
            double errorRate = cc > 0 ? (double) ec / cc : 0.0;
            long avgLatency = cc > 0
                    ? totalLatency.getOrDefault(def.getName(), new AtomicLong(0)).get() / cc
                    : 0;

            String healthStatus;
            if (def.getStatus() == ToolStatus.DISABLED || def.getStatus() == ToolStatus.REMOVED) {
                healthStatus = "DOWN";
            } else if (def.getStatus() == ToolStatus.DEPRECATED) {
                healthStatus = "DEGRADED";
                degraded++;
            } else if (errorRate > 0.05) {
                healthStatus = "DEGRADED";
                degraded++;
            } else {
                healthStatus = "OK";
                healthy++;
            }

            if (def.getStatus() == ToolStatus.DEPRECATED) {
                deprecated++;
            }

            items.add(HealthResult.ToolHealthItem.builder()
                    .name(def.getName())
                    .status(healthStatus)
                    .errorRate(Math.round(errorRate * 100.0) / 100.0)
                    .avgLatencyMs(avgLatency)
                    .build());
        }

        return HealthResult.builder()
                .total(total)
                .healthy(healthy)
                .degraded(degraded)
                .deprecated(deprecated)
                .tools(items)
                .build();
    }

    // ==================== 依赖追踪 ====================

    /**
     * 记录工具调用。
     *
     * @param toolName 工具名称
     * @param agentId  调用方 Agent ID
     * @param latencyMs 执行耗时（毫秒）
     * @param success  是否成功
     */
    public void recordCall(String toolName, String agentId, long latencyMs, boolean success) {
        // 记录依赖
        dependencies.computeIfAbsent(toolName, k -> Collections.synchronizedList(new ArrayList<>()));
        List<ToolDependRecord> records = dependencies.get(toolName);

        synchronized (records) {
            Optional<ToolDependRecord> existing = records.stream()
                    .filter(r -> r.getAgentId().equals(agentId))
                    .findFirst();

            if (existing.isPresent()) {
                ToolDependRecord record = existing.get();
                records.remove(record);
                records.add(ToolDependRecord.builder()
                        .agentId(agentId)
                        .toolName(toolName)
                        .lastCalledAt(LocalDateTime.now())
                        .callCount30d(record.getCallCount30d() + 1)
                        .build());
            } else {
                records.add(ToolDependRecord.builder()
                        .agentId(agentId)
                        .toolName(toolName)
                        .lastCalledAt(LocalDateTime.now())
                        .callCount30d(1)
                        .build());
            }
        }

        // 统计
        callCount.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        totalLatency.computeIfAbsent(toolName, k -> new AtomicLong(0)).addAndGet(latencyMs);
        if (!success) {
            errorCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * 获取工具的依赖者列表。
     *
     * @param toolName 工具名称
     * @return 依赖者列表
     */
    public List<ToolDependRecord> getDependents(String toolName) {
        return dependencies.getOrDefault(toolName, Collections.emptyList());
    }

    /**
     * 获取所有依赖关系。
     */
    public Map<String, List<ToolDependRecord>> getAllDependencies() {
        return Collections.unmodifiableMap(dependencies);
    }

    // ==================== 内部方法 ====================

    /**
     * 检查工具定义是否包含任意指定标签。
     */
    private boolean hasAnyTag(ToolDefinition def, String[] tags) {
        if (def.getTags() == null || def.getTags().length == 0) return false;
        Set<String> defTagSet = new HashSet<>(Arrays.asList(def.getTags()));
        for (String tag : tags) {
            if (defTagSet.contains(tag)) return true;
        }
        return false;
    }
}
