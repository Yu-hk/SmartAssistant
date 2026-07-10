package com.example.smartassistant.common.gateway.tool;

/**
 * 工具生命周期状态枚举。
 * <p>
 * 描述一个工具在其生命周期中所处的阶段，用于 {@link ToolRegistry} 和 {@link ToolGateway}
 * 在执行前判断工具是否可用、是否需要发出警告或拦截调用。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-01
 */
public enum ToolStatus {

    /** 实验期：仅授权 Agent 可见，非稳定 API */
    EXPERIMENTAL,

    /** 稳定可用：所有授权调用方均可使用 */
    ACTIVE,

    /** 已废弃：调用仍可用，但响应中会包含废弃警告头 */
    DEPRECATED,

    /** 已停用：调用被网关拦截，返回不可用错误 */
    DISABLED,

    /** 已移除：仅保留审计记录，调用返回工具不存在 */
    REMOVED
}
