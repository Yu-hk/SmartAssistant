package com.example.smartassistant.common.gateway.tool.hook;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Hook 执行上下文（REQ-02）。
 * <p>
 * 封装工具执行时的上下文信息，传递给 {@link ToolExecutionHook} 的各个回调方法。
 * {@code elapsedMs} 字段在 preExecute 时为 0，在 postExecute/onError 时由
 * {@link com.example.smartassistant.common.gateway.tool.ToolGateway} 填充实际耗时。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
@Getter
@Builder
public class ToolHookContext {

    /** 工具名称 */
    private final String toolName;

    /** 工具定义（包含风险等级、状态、scopes 等元数据） */
    private final ToolDefinition toolDefinition;

    /** 调用方 Scope（null = 不检查权限） */
    private final String scope;

    /** 幂等键（null = 不检查幂等） */
    private final String idempotencyKey;

    /** 执行开始时间戳（毫秒） */
    private final long startTimeMs;

    /** 已耗时（毫秒），preExecute 时为 0，postExecute/onError 时填充 */
    @Setter
    private long elapsedMs;
}
