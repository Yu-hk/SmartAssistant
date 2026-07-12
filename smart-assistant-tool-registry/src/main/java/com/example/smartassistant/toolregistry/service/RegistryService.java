package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.common.gateway.tool.compat.CompatibilityResult;
import com.example.smartassistant.common.gateway.tool.compat.IncompatibleVersionException;
import com.example.smartassistant.common.gateway.tool.compat.ToolCompatibilityChecker;
import com.example.smartassistant.toolregistry.config.RequestIdHolder;
import com.example.smartassistant.toolregistry.model.HealthResult;
import com.example.smartassistant.toolregistry.model.ToolDependRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 兼容性检查器 */
    private final ToolCompatibilityChecker compatibilityChecker;

    /** Manifest 校验器 */
    private final ToolManifestValidator manifestValidator;

    /**
     * 规范目录（中心工具权威来源，由各 Agent 模块经 {@link ToolRegistry#registerAll} 注册）。
     * <p>可选注入：单元测试中通常为 {@code null}；运行时由 Spring 注入。
     * search / listActiveTools 会将其与本存储（{@code definitions}）取并集，
     * 以保证「发现视图」同时覆盖中心目录工具与管理面注册工具。</p>
     */
    @Autowired(required = false)
    private ToolRegistry toolRegistry;

    public RegistryService(ToolCompatibilityChecker compatibilityChecker,
                           ToolManifestValidator manifestValidator) {
        this.compatibilityChecker = compatibilityChecker;
        this.manifestValidator = manifestValidator;
    }

    @PostConstruct
    public void init() {
        log.info("[RegistryService] 工具注册中心已启动，存储模式: ConcurrentHashMap");
    }

    // ==================== 注册 ====================

    /**
     * 注册工具定义。如果已存在同名工具，进行兼容性检查后更新定义并记录日志。
     * <p>
     * 注册流程：
     * <ol>
     *   <li>Manifest 校验（capabilities/outputSchema）—— 仅 WARN 不阻断</li>
     *   <li>兼容性检查（若已存在同名工具）—— BREAKING 且 major 版本未升级则拒绝</li>
     *   <li>写入存储 + 记录日志</li>
     * </ol>
     * </p>
     *
     * @param definition 工具定义
     * @throws IncompatibleVersionException 如果 BREAKING 变更且 major 版本未升级
     */
    public void register(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(definition.getName(), "definition.name must not be null");

        // 1. Manifest 校验（就地修正 capabilities，其他仅 WARN）
        manifestValidator.validate(definition);

        // 2. 兼容性检查
        ToolDefinition existing = definitions.get(definition.getName());
        String compatibility = "NONE";

        if (existing != null) {
            CompatibilityResult result = compatibilityChecker.check(existing, definition);
            compatibility = result.name();

            if (result == CompatibilityResult.BREAKING) {
                int oldMajor = parseMajorVersion(existing.getVersion());
                int newMajor = parseMajorVersion(definition.getVersion());

                if (oldMajor == newMajor) {
                    log.warn("[RegistryService][{}] 版本不兼容: tool={}, {}→{}, reason={}, major未升级({}={})",
                            RequestIdHolder.get(), definition.getName(),
                            existing.getVersion(), definition.getVersion(),
                            compatibilityChecker.getReason(), oldMajor, newMajor);
                    throw new IncompatibleVersionException(
                            definition.getName(),
                            existing.getVersion(),
                            definition.getVersion(),
                            compatibilityChecker.getReason());
                }
            }
        }

        // 3. 写入存储
        definitions.put(definition.getName(), definition);

        if (existing != null) {
            log.info("[RegistryService][{}] 更新工具: name={}, version={}→{}, status={}→{}, compatibility={}",
                    RequestIdHolder.get(), definition.getName(), existing.getVersion(),
                    definition.getVersion(), existing.getStatus(), definition.getStatus(),
                    compatibility);
        } else {
            log.info("[RegistryService][{}] 注册工具: name={}, version={}, tags={}, namespace={}, capabilities={}",
                    RequestIdHolder.get(), definition.getName(), definition.getVersion(),
                    Arrays.toString(definition.getTags()), definition.getNamespace(),
                    Arrays.toString(definition.getCapabilities()));
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
        return query(tags, status, namespace, null, null, "OR");
    }

    /**
     * 查询工具列表，支持按标签、状态、命名空间、能力标签过滤。
     * <p>capabilities 过滤为 OR 语义：工具包含任一指定能力即匹配。</p>
     *
     * @param tags         标签列表（可选，多个标签取交集匹配）
     * @param status       状态过滤（可选）
     * @param namespace    命名空间过滤（可选）
     * @param capabilities 能力标签列表（可选，OR 语义过滤）
     * @return 匹配的工具定义列表
     */
    public List<ToolDefinition> query(String[] tags, ToolStatus status,
                                       String namespace, String[] capabilities) {
        return query(tags, status, namespace, capabilities, null, "OR");
    }

    /**
     * 查询工具列表，支持按标签、状态、命名空间、风险能力标签、功能性能力标签过滤。
     * <p>capabilities 与 functionalCapabilities 均为 OR 语义（默认）：工具包含任一指定能力即匹配。
     * 可经 {@code matchMode} 切换为 AND 语义（功能性能力需全部命中）。</p>
     *
     * @param tags                   标签列表（可选，多个标签取交集匹配）
     * @param status                 状态过滤（可选）
     * @param namespace              命名空间过滤（可选）
     * @param capabilities           风险能力标签列表（可选，OR 语义过滤）
     * @param functionalCapabilities 功能性能力标签列表（可选，OR/AND 语义过滤，见 matchMode）
     * @param matchMode              功能性能力匹配模式：{@code "OR"}（默认）或 {@code "AND"}
     * @return 匹配的工具定义列表
     */
    public List<ToolDefinition> query(String[] tags, ToolStatus status, String namespace,
                                       String[] capabilities, String[] functionalCapabilities,
                                       String matchMode) {
        return definitions.values().stream()
                .filter(def -> status == null || def.getStatus() == status)
                .filter(def -> namespace == null || namespace.equals(def.getNamespace()))
                .filter(def -> tags == null || tags.length == 0 || hasAnyTag(def, tags))
                .filter(def -> capabilities == null || capabilities.length == 0
                        || hasAnyCapability(def, capabilities))
                .filter(def -> functionalCapabilities == null || functionalCapabilities.length == 0
                        || matchFunctionalCapabilities(def, functionalCapabilities, matchMode))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已注册的工具。
     */
    public Collection<ToolDefinition> getAll() {
        return definitions.values();
    }

    /**
     * 服务端能力检索（等价于原 REST {@code /api/tools/search}），供 MCP {@code search_tools} 调用。
     * <p>过滤维度：
     * <ul>
     *   <li>{@code functionalCapabilities}：OR（默认）/ AND（见 matchMode）匹配
     *       {@link ToolDefinition#getFunctionalCapabilities()}，复用 contains-any 匹配；</li>
     *   <li>{@code keyword}（可选）：在 name/description 上大小写不敏感包含匹配；</li>
     *   <li>{@code tier}（可选）：按 {@link ToolTier} 精确匹配；</li>
     *   <li>{@code status}（可选）：按 {@link ToolStatus} 精确匹配（null/blank 表示不限制）；</li>
     *   <li>{@code limit}（&gt;0）：截断返回数量。</li>
     * </ul>
     *
     * @param functionalCapabilities 功能性能力令牌（可选）
     * @param keyword                 关键词（可选）
     * @param matchMode               功能性能力匹配模式：{@code "OR"}（默认）或 {@code "AND"}
     * @param tier                    工具分层（可选）
     * @param status                  状态（可选）
     * @param limit                   返回上限（&gt;0 截断）
     * @return 匹配的工具定义列表
     */
    public List<ToolDefinition> search(String[] functionalCapabilities, String keyword,
                                       String matchMode, String tier, String status, int limit) {
        // 1. 状态过滤（null/blank 视为不限制；非法值忽略并告警）
        final ToolStatus statusFilter;
        if (status != null && !status.isBlank()) {
            ToolStatus parsed;
            try {
                parsed = ToolStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[RegistryService] 非法的 status 过滤值: {}, 忽略该过滤", status);
                parsed = null;
            }
            statusFilter = parsed;
        } else {
            statusFilter = null;
        }

        // 2. 基础候选集 = 发现视图（规范目录 ToolRegistry + 本存储 definitions 并集）
        List<ToolDefinition> candidates = new ArrayList<>(catalogView());

        // 3. 功能性能力 OR/AND 过滤
        if (functionalCapabilities != null && functionalCapabilities.length > 0) {
            candidates = candidates.stream()
                    .filter(def -> matchFunctionalCapabilities(def, functionalCapabilities, matchMode))
                    .collect(Collectors.toList());
        }

        // 4. 状态过滤
        if (statusFilter != null) {
            candidates = candidates.stream()
                    .filter(def -> def.getStatus() == statusFilter)
                    .collect(Collectors.toList());
        }

        // 5. 关键词过滤（name/description 大小写不敏感包含匹配）
        String kw = (keyword == null) ? null : keyword.trim();
        java.util.stream.Stream<ToolDefinition> stream = candidates.stream();
        if (kw != null && !kw.isEmpty()) {
            String lowerKw = kw.toLowerCase();
            stream = stream.filter(def -> {
                if (def.getName() != null && def.getName().toLowerCase().contains(lowerKw)) {
                    return true;
                }
                return def.getDescription() != null
                        && def.getDescription().toLowerCase().contains(lowerKw);
            });
        }

        // 6. 分层过滤
        if (tier != null && !tier.isBlank()) {
            String t = tier.trim().toUpperCase();
            stream = stream.filter(def -> def.getToolTier() != null
                    && def.getToolTier().name().equalsIgnoreCase(t));
        }

        // 7. 截断
        List<ToolDefinition> result = stream.collect(Collectors.toList());
        if (limit > 0 && result.size() > limit) {
            result = new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    /**
     * 返回所有 {@link ToolStatus#ACTIVE} 工具（发现视图中 status == ACTIVE）。
     * <p>供 MCP {@code tools/list} 暴露中心目录全量。</p>
     *
     * @return ACTIVE 工具定义列表
     */
    public List<ToolDefinition> listActiveTools() {
        return catalogView().stream()
                .filter(def -> def.getStatus() == ToolStatus.ACTIVE)
                .collect(Collectors.toList());
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
                .capabilities(def.getCapabilities())
                .outputSchema(def.getOutputSchema())
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
                .capabilities(def.getCapabilities())
                .outputSchema(def.getOutputSchema())
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
     * 构建「发现视图」：返回中心目录工具定义的全量并集，供 discovery / search 使用。
     * <p>视图 = 本存储 {@code definitions.values()} ∪ 规范目录 {@link ToolRegistry#getAll()}，
     * 按 {@code name} 去重（本存储优先）。这样即使某工具仅注册在规范目录（由各业务模块经
     * {@code @PostConstruct registerAll} 写入）而未走管理面 {@link #register}，也能被
     * MCP 发现层与 {@code search_tools} 覆盖到，保证「发现」面向真实目录。</p>
     *
     * @return 全量工具定义列表（去重并集）
     */
    private List<ToolDefinition> catalogView() {
        List<ToolDefinition> view = new ArrayList<>(definitions.values());
        if (toolRegistry != null) {
            Set<String> seen = new HashSet<>();
            for (ToolDefinition def : view) {
                if (def.getName() != null) {
                    seen.add(def.getName());
                }
            }
            for (ToolDefinition def : toolRegistry.getAll()) {
                if (def.getName() != null && seen.add(def.getName())) {
                    view.add(def);
                }
            }
        }
        return view;
    }

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

    /**
     * 检查工具定义是否包含任意指定能力标签（OR 语义）。
     *
     * @param def          工具定义
     * @param capabilities 能力标签列表
     * @return 如果包含任一指定能力返回 {@code true}
     */
    private boolean hasAnyCapability(ToolDefinition def, String[] capabilities) {
        if (def.getCapabilities() == null || def.getCapabilities().length == 0) {
            return false;
        }
        Set<String> defCapSet = new HashSet<>(Arrays.asList(def.getCapabilities()));
        for (String cap : capabilities) {
            if (defCapSet.contains(cap)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 {@code matchMode} 匹配工具的功能性能力（{@link ToolDefinition#getFunctionalCapabilities()}）。
     * <ul>
     *   <li>{@code AND}：工具必须包含全部指定功能性能力；</li>
     *   <li>{@code OR}（默认）：工具包含任一指定功能性能力即匹配。</li>
     * </ul>
     *
     * @param def                   工具定义
     * @param functionalCapabilities 功能性能力令牌
     * @param matchMode             匹配模式（{@code "OR"}/{@code "AND"}，默认 OR）
     * @return 是否匹配
     */
    private boolean matchFunctionalCapabilities(ToolDefinition def, String[] functionalCapabilities,
                                                 String matchMode) {
        List<String> defFcs = def.getFunctionalCapabilities();
        if (defFcs == null || defFcs.isEmpty()) {
            return false;
        }
        Set<String> defFcSet = new HashSet<>(defFcs);
        boolean and = "AND".equalsIgnoreCase(matchMode == null ? "OR" : matchMode);
        if (and) {
            for (String fc : functionalCapabilities) {
                if (!defFcSet.contains(fc)) {
                    return false;
                }
            }
            return true;
        }
        for (String fc : functionalCapabilities) {
            if (defFcSet.contains(fc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析版本号的 MAJOR 部分。
     * <p>版本号格式：MAJOR.MINOR.PATCH（如 "1.2.0"）。
     * 非法格式返回 0。</p>
     *
     * @param version 版本号字符串
     * @return MAJOR 版本号，非法时返回 0
     */
    private int parseMajorVersion(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        String[] parts = version.split("\\.");
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            log.warn("[RegistryService] 版本号非法: {}, 视为 0.0.0", version);
            return 0;
        }
    }
}
