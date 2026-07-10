package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具定义（不可变基信息部分，含线程安全计数器）。
 * <p>
 * 描述一个工具的元数据，用于 {@link ToolRegistry} 注册和 {@link ToolGateway} 执行前的校验。
 * 所有 {@code @Tool} 方法应在初始化时注册其定义。
 * </p>
 *
 * <p>本类从 {@code record} 重构为 {@code class} + Builder 模式以支持 Phase 0 新增的 9 个字段。
 * 所有静态工厂方法（{@link #read}, {@link #write}, {@link #highRisk}）保持向后兼容。</p>
 *
 * <p>{@code equals} 和 {@code hashCode} 仅基于 {@code name} 字段，因工具名称在系统中唯一标识一个工具。</p>
 *
 * @author Yu-hk
 * @since 2026-07-01
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ToolDefinition {

    // ==================== 常量 ====================

    /** 默认超时时间 10 秒 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** 高风险工具默认超时时间 15 秒 */
    public static final Duration DEFAULT_HIGH_RISK_TIMEOUT = Duration.ofSeconds(15);

    /** 默认最大重试次数 */
    public static final int DEFAULT_MAX_RETRIES = 0;

    /** 默认每秒限流（0 = 不限制） */
    public static final int DEFAULT_RATE_LIMIT = 0;

    // ==================== 字段（8 个原有 + 9 个新增） ====================

    /** 工具名称（唯一标识） */
    @EqualsAndHashCode.Include
    private String name;

    /** 工具描述 */
    private String description;

    /** 风险等级 */
    @Builder.Default
    private ToolRiskLevel riskLevel = ToolRiskLevel.READ;

    /** 执行超时 */
    @Builder.Default
    private Duration timeout = DEFAULT_TIMEOUT;

    /** 是否需要审批（仅 HIGH 风险时需要） */
    private boolean needsApproval;

    /** 最大重试次数 */
    @Builder.Default
    private int maxRetries = DEFAULT_MAX_RETRIES;

    /** 每秒允许调用次数（0 = 不限制） */
    @Builder.Default
    private int rateLimit = DEFAULT_RATE_LIMIT;

    /** 允许访问的 Scope 列表（空 = 所有 Scope 允许） */
    @Builder.Default
    private String[] scopes = new String[0];

    // ==================== 新增字段（Phase 0） ====================

    /** 标签列表，用于域隔离，例如 {@code ["ORDER", "READ_ONLY"]} */
    @Builder.Default
    private String[] tags = new String[0];

    /** 语义版本号，例如 "1.2.0" */
    @Builder.Default
    private String version = "1.0.0";

    /** 生命周期状态，默认 {@link ToolStatus#ACTIVE} */
    @Builder.Default
    private ToolStatus status = ToolStatus.ACTIVE;

    /** 命名空间，例如 "order" */
    private String namespace;

    /** 归属团队 */
    private String ownerTeam;

    /** 工具执行端点（Registry 转发目标） */
    private String endpoint;

    /** 替代工具名（DEPRECATED 时设置） */
    private String deprecatedBy;

    /** 下线日期（DEPRECATED 时设置），格式 yyyy-MM-dd */
    private String sunsetDate;

    /** 调用计数（线程安全，用于内部统计） */
    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicLong useCount = new AtomicLong(0);

    // ==================== 快捷属性方法 ====================

    /**
     * 是否为只读操作。
     *
     * @return 如果风险等级为 {@link ToolRiskLevel#READ} 返回 {@code true}
     */
    public boolean isReadOnly() {
        return riskLevel == ToolRiskLevel.READ;
    }

    /**
     * 是否为高风险操作。
     *
     * @return 如果风险等级为 {@link ToolRiskLevel#HIGH} 返回 {@code true}
     */
    public boolean isHighRisk() {
        return riskLevel == ToolRiskLevel.HIGH;
    }

    /**
     * 获取对应的默认错误码。
     *
     * @return 根据风险等级返回对应的 {@link AgentErrorCode}
     */
    public AgentErrorCode getErrorCode() {
        return switch (riskLevel) {
            case READ -> AgentErrorCode.DATA_NOT_FOUND;
            case LOW, MEDIUM -> AgentErrorCode.TOOL_EXECUTION_FAILED;
            case HIGH -> AgentErrorCode.PERMISSION_DENIED;
        };
    }

    /**
     * 原子递增调用计数。
     *
     * @return 递增后的新值
     */
    public long incrementAndGetUseCount() {
        return useCount.incrementAndGet();
    }

    /**
     * 获取当前调用计数。
     *
     * @return 当前调用计数值
     */
    public long getUseCount() {
        return useCount.get();
    }

    // ==================== 静态工厂方法（向后兼容） ====================

    /**
     * 构建快速定义（只读工具快捷方式）。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @return 只读工具定义
     */
    public static ToolDefinition read(String name, String description) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .riskLevel(ToolRiskLevel.READ)
                .timeout(DEFAULT_TIMEOUT)
                .needsApproval(false)
                .maxRetries(DEFAULT_MAX_RETRIES)
                .rateLimit(DEFAULT_RATE_LIMIT)
                .scopes(new String[0])
                .tags(new String[0])
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（只读工具快捷方式，带标签）。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param tags        标签列表
     * @return 只读工具定义
     */
    public static ToolDefinition read(String name, String description, String[] tags) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .riskLevel(ToolRiskLevel.READ)
                .timeout(DEFAULT_TIMEOUT)
                .needsApproval(false)
                .maxRetries(DEFAULT_MAX_RETRIES)
                .rateLimit(DEFAULT_RATE_LIMIT)
                .scopes(new String[0])
                .tags(tags != null ? tags : new String[0])
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（高风险工具快捷方式）。
     *
     * @param name           工具名称
     * @param description    工具描述
     * @param needsApproval  是否需要审批
     * @return 高风险工具定义
     */
    public static ToolDefinition highRisk(String name, String description, boolean needsApproval) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .riskLevel(ToolRiskLevel.HIGH)
                .timeout(DEFAULT_HIGH_RISK_TIMEOUT)
                .needsApproval(needsApproval)
                .maxRetries(1)
                .rateLimit(10)
                .scopes(new String[0])
                .tags(new String[0])
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（高风险工具快捷方式，带标签）。
     *
     * @param name           工具名称
     * @param description    工具描述
     * @param needsApproval  是否需要审批
     * @param tags           标签列表
     * @return 高风险工具定义
     */
    public static ToolDefinition highRisk(String name, String description, boolean needsApproval,
                                          String[] tags) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .riskLevel(ToolRiskLevel.HIGH)
                .timeout(DEFAULT_HIGH_RISK_TIMEOUT)
                .needsApproval(needsApproval)
                .maxRetries(1)
                .rateLimit(10)
                .scopes(new String[0])
                .tags(tags != null ? tags : new String[0])
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（写入工具快捷方式）。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param riskLevel   风险等级
     * @return 写入工具定义
     */
    public static ToolDefinition write(String name, String description, ToolRiskLevel riskLevel) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .riskLevel(riskLevel)
                .timeout(DEFAULT_TIMEOUT)
                .needsApproval(riskLevel == ToolRiskLevel.HIGH)
                .maxRetries(DEFAULT_MAX_RETRIES)
                .rateLimit(DEFAULT_RATE_LIMIT)
                .scopes(new String[0])
                .tags(new String[0])
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（写入工具快捷方式，带标签）。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param riskLevel   风险等级
     * @param tags        标签列表
     * @return 写入工具定义
     */
    public static ToolDefinition write(String name, String description, ToolRiskLevel riskLevel,
                                       String[] tags) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .riskLevel(riskLevel)
                .timeout(DEFAULT_TIMEOUT)
                .needsApproval(riskLevel == ToolRiskLevel.HIGH)
                .maxRetries(DEFAULT_MAX_RETRIES)
                .rateLimit(DEFAULT_RATE_LIMIT)
                .scopes(new String[0])
                .tags(tags != null ? tags : new String[0])
                .status(ToolStatus.ACTIVE)
                .build();
    }
}
