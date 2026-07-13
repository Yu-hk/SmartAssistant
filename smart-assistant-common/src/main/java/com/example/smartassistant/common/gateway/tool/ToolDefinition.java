package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
@Builder(toBuilder = true)
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

    /** 能力标签列表，默认 ["unknown"]（向后兼容） */
    @Builder.Default
    private String[] capabilities = new String[]{"unknown"};

    /** 输出 Schema（JSON Schema 字符串），默认 null */
    private String outputSchema;

    /**
     * MCP-backed 工具源的工具入参 JSON Schema（同步自后端 MCP server）；中心 @Tool 工具为 null。
     * <p>
     * 仅用于后续 T2c 的 {@code McpBackendToolExecutor} 转发后端 MCP 工具入参 schema。
     * 默认 null，向后兼容；不纳入 {@code equals}/{@code hashCode}（与既有字段风格一致）。
     * </p>
     */
    private String inputSchema;

    /** 工具分层（CORE / SHARED / EXTENSION），默认 {@link ToolTier#CORE} */
    @Builder.Default
    private ToolTier toolTier = ToolTier.CORE;

    /** 调用计数（线程安全，用于内部统计） */
    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicLong useCount = new AtomicLong(0);

    // ==================== 新增字段（T1 功能性能力） ====================

    /**
     * 功能性能力标签列表（业务动作语义），如 {@code ["order-query", "order-refund"]}。
     * <p>
     * 与风险能力 {@code capabilities}（由 {@code riskLevel} 推导）正交，描述"工具能做什么业务动作"，
     * 用于 T1 能力作用域预载与 T2 自主发现匹配。受控词表见 {@link ToolFunctionalCapability}。
     * </p>
     * <p>默认空列表（迁移期向后兼容，绝不为 {@code null}）。</p>
     */
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private List<String> functionalCapabilities = new ArrayList<>();

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
     * 根据风险等级推导默认能力标签。
     * <ul>
     *   <li>READ → ["read-only"]</li>
     *   <li>LOW / MEDIUM → ["mutate-state"]</li>
     *   <li>HIGH → ["mutate-state", "payment"]</li>
     * </ul>
     *
     * @param level 风险等级
     * @return 能力标签数组
     */
    private static String[] capabilitiesForRiskLevel(ToolRiskLevel level) {
        if (level == ToolRiskLevel.READ) {
            return new String[]{ToolCapability.READ_ONLY.getValue()};
        }
        if (level == ToolRiskLevel.HIGH) {
            return new String[]{ToolCapability.MUTATE_STATE.getValue(),
                    ToolCapability.PAYMENT.getValue()};
        }
        return new String[]{ToolCapability.MUTATE_STATE.getValue()};
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

    // ==================== 功能性能力（functionalCapabilities） ====================

    /**
     * 设置功能性能力标签列表（业务动作语义）。
     * <p>会执行规范化：忽略 {@code null} 元素并去重（保留首次出现顺序）；
     * 入参为 {@code null} 或空时设为空列表（绝不为 {@code null}）。</p>
     *
     * @param functionalCapabilities 功能性能力标签列表（可为 {@code null} 或含 {@code null} 元素）
     */
    public void setFunctionalCapabilities(List<String> functionalCapabilities) {
        this.functionalCapabilities = normalizeFunctionalCapabilities(functionalCapabilities);
    }

    /**
     * 将原始功能性能力列表规范化为「不含 null、已去重、保留顺序」的列表。
     * 入参为 {@code null} 或空时返回空列表。
     *
     * @param source 原始功能性能力列表（可为 {@code null}）
     * @return 规范化后的新列表（绝不为 {@code null}）
     */
    private static List<String> normalizeFunctionalCapabilities(List<String> source) {
        List<String> result = new ArrayList<>();
        if (source != null) {
            for (String item : source) {
                if (item != null && !result.contains(item)) {
                    result.add(item);
                }
            }
        }
        return result;
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
                .capabilities(new String[]{ToolCapability.READ_ONLY.getValue()})
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
                .capabilities(new String[]{ToolCapability.READ_ONLY.getValue()})
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
                .capabilities(new String[]{ToolCapability.MUTATE_STATE.getValue(),
                        ToolCapability.PAYMENT.getValue()})
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
                .capabilities(new String[]{ToolCapability.MUTATE_STATE.getValue(),
                        ToolCapability.PAYMENT.getValue()})
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
                .capabilities(capabilitiesForRiskLevel(riskLevel))
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
                .capabilities(capabilitiesForRiskLevel(riskLevel))
                .status(ToolStatus.ACTIVE)
                .build();
    }

    // ==================== 静态工厂重载（T1：支持 functionalCapabilities） ====================
    // 注：为避免与既有 read/write/highRisk 的 String[] tags 重载发生方法签名擦除冲突，
    // 新增重载统一使用 List<String> 接收功能性能力，提供与现有工厂一一对应的变体。

    /**
     * 构建快速定义（只读工具快捷方式，带标签与功能性能力）。
     * <p>为避免与既有 {@code read(name, description, String[] tags)} 在 {@code null} 实参下产生重载歧义，
     * 不提供 3 参 {@code (name, description, List)} 变体；仅需功能性能力时可改用
     * {@link #builder()} 或本 4 参重载（tags 传 {@code null} 等价空标签）。</p>
     *
     * @param name                    工具名称
     * @param description             工具描述
     * @param tags                    标签列表（可为 {@code null}）
     * @param functionalCapabilities  功能性能力标签列表（业务动作语义，可为 {@code null}）
     * @return 只读工具定义
     */
    public static ToolDefinition read(String name, String description, String[] tags,
                                      List<String> functionalCapabilities) {
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
                .capabilities(new String[]{ToolCapability.READ_ONLY.getValue()})
                .functionalCapabilities(functionalCapabilities)
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（写入工具快捷方式，带功能性能力）。
     *
     * @param name                    工具名称
     * @param description             工具描述
     * @param riskLevel               风险等级
     * @param functionalCapabilities  功能性能力标签列表（业务动作语义，可为 {@code null}）
     * @return 写入工具定义
     */
    public static ToolDefinition write(String name, String description, ToolRiskLevel riskLevel,
                                       List<String> functionalCapabilities) {
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
                .capabilities(capabilitiesForRiskLevel(riskLevel))
                .functionalCapabilities(functionalCapabilities)
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（写入工具快捷方式，带标签与功能性能力）。
     *
     * @param name                    工具名称
     * @param description             工具描述
     * @param riskLevel               风险等级
     * @param tags                    标签列表
     * @param functionalCapabilities  功能性能力标签列表（业务动作语义，可为 {@code null}）
     * @return 写入工具定义
     */
    public static ToolDefinition write(String name, String description, ToolRiskLevel riskLevel,
                                       String[] tags, List<String> functionalCapabilities) {
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
                .capabilities(capabilitiesForRiskLevel(riskLevel))
                .functionalCapabilities(functionalCapabilities)
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（高风险工具快捷方式，带功能性能力）。
     *
     * @param name                    工具名称
     * @param description             工具描述
     * @param needsApproval           是否需要审批
     * @param functionalCapabilities  功能性能力标签列表（业务动作语义，可为 {@code null}）
     * @return 高风险工具定义
     */
    public static ToolDefinition highRisk(String name, String description, boolean needsApproval,
                                          List<String> functionalCapabilities) {
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
                .capabilities(new String[]{ToolCapability.MUTATE_STATE.getValue(),
                        ToolCapability.PAYMENT.getValue()})
                .functionalCapabilities(functionalCapabilities)
                .status(ToolStatus.ACTIVE)
                .build();
    }

    /**
     * 构建快速定义（高风险工具快捷方式，带标签与功能性能力）。
     *
     * @param name                    工具名称
     * @param description             工具描述
     * @param needsApproval           是否需要审批
     * @param tags                    标签列表
     * @param functionalCapabilities  功能性能力标签列表（业务动作语义，可为 {@code null}）
     * @return 高风险工具定义
     */
    public static ToolDefinition highRisk(String name, String description, boolean needsApproval,
                                          String[] tags, List<String> functionalCapabilities) {
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
                .capabilities(new String[]{ToolCapability.MUTATE_STATE.getValue(),
                        ToolCapability.PAYMENT.getValue()})
                .functionalCapabilities(functionalCapabilities)
                .status(ToolStatus.ACTIVE)
                .build();
    }

    // ==================== 自定义 Builder（扩展 functionalCapabilities 重载） ====================
    // Lombok 在检测到手动声明的同名 builder 类时会将其余字段/方法追加进来；
    // 此处仅自定义 functionalCapabilities 的 List 与 varargs 重载，使其自动去重、忽略 null。

    /**
     * {@link ToolDefinition} 的 Builder。
     * <p>除 Lombok 自动生成的字段 setter 外，额外提供 {@code functionalCapabilities} 的
     * {@link List} 与可变参数（varargs）重载，二者均会去重并忽略 {@code null} 元素。</p>
     */
    public static class ToolDefinitionBuilder {

        /** 功能性能力标签列表（由 Lombok 其余字段 setter 自动补齐，此处仅声明本字段以承载自定义重载） */
        private List<String> functionalCapabilities = new ArrayList<>();

        /**
         * 设置功能性能力标签列表（业务动作语义）。
         * 会去重并忽略 {@code null} 元素；入参为 {@code null} 时设为空列表。
         *
         * @param values 功能性能力标签列表（可为 {@code null}）
         * @return this，便于链式调用
         */
        public ToolDefinitionBuilder functionalCapabilities(List<String> values) {
            this.functionalCapabilities = normalizeFunctionalCapabilities(values);
            return this;
        }

        /**
         * 设置功能性能力标签（业务动作语义，可变参数）。
         * 会去重并忽略 {@code null} 元素；入参为 {@code null} 时设为空列表。
         *
         * @param values 功能性能力标签（可为 {@code null}）
         * @return this，便于链式调用
         */
        public ToolDefinitionBuilder functionalCapabilities(String... values) {
            this.functionalCapabilities = normalizeFunctionalCapabilities(
                    values == null ? null : Arrays.asList(values));
            return this;
        }
    }
}
