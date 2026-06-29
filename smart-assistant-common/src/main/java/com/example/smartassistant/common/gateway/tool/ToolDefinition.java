package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;

import java.time.Duration;
import java.util.Map;

/**
 * 工具定义（不可变）。
 * <p>
 * 描述一个工具的元数据，用于 ToolRegistry 注册和 ToolGateway 执行前的校验。
 * 所有 @Tool 方法应在初始化时注册其定义。
 * </p>
 *
 * @param name          工具名称（唯一标识）
 * @param description   工具描述
 * @param riskLevel     风险等级
 * @param timeout       执行超时
 * @param needsApproval 是否需要审批（仅 HIGH 风险时需要）
 * @param maxRetries    最大重试次数
 * @param rateLimit     每秒允许调用次数（0=不限制）
 * @param scopes        允许访问的 Scope 列表（空=所有 Scope 允许）
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
public record ToolDefinition(
        String name,
        String description,
        ToolRiskLevel riskLevel,
        Duration timeout,
        boolean needsApproval,
        int maxRetries,
        int rateLimit,
        String[] scopes
) {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_RETRIES = 0;
    public static final int DEFAULT_RATE_LIMIT = 0;

    /** 是否为只读操作 */
    public boolean isReadOnly() {
        return riskLevel == ToolRiskLevel.READ;
    }

    /** 是否为高风险操作 */
    public boolean isHighRisk() {
        return riskLevel == ToolRiskLevel.HIGH;
    }

    /** 获取对应的默认错误码 */
    public AgentErrorCode getErrorCode() {
        return switch (riskLevel) {
            case READ -> AgentErrorCode.DATA_NOT_FOUND;
            case LOW, MEDIUM -> AgentErrorCode.TOOL_EXECUTION_FAILED;
            case HIGH -> AgentErrorCode.PERMISSION_DENIED;
        };
    }

    /** 构建快速定义（只读工具快捷方式） */
    public static ToolDefinition read(String name, String description) {
        return new ToolDefinition(name, description, ToolRiskLevel.READ,
                DEFAULT_TIMEOUT, false, DEFAULT_MAX_RETRIES, DEFAULT_RATE_LIMIT, new String[0]);
    }

    /** 构建快速定义（高风险工具快捷方式） */
    public static ToolDefinition highRisk(String name, String description, boolean needsApproval) {
        return new ToolDefinition(name, description, ToolRiskLevel.HIGH,
                Duration.ofSeconds(15), needsApproval, 1, 10, new String[0]);
    }

    /** 构建快速定义（写入工具快捷方式） */
    public static ToolDefinition write(String name, String description, ToolRiskLevel riskLevel) {
        return new ToolDefinition(name, description, riskLevel,
                DEFAULT_TIMEOUT, riskLevel == ToolRiskLevel.HIGH, DEFAULT_MAX_RETRIES,
                DEFAULT_RATE_LIMIT, new String[0]);
    }
}
