package com.example.smartassistant.common.gateway.tool.compat;

/**
 * 兼容性检查结果枚举（REQ-04）。
 * <p>
 * 表示新旧 {@link com.example.smartassistant.common.gateway.tool.ToolDefinition} 之间的兼容性：
 * <ul>
 *   <li>{@link #BREAKING} — 破坏性变更，需升级主版本号</li>
 *   <li>{@link #COMPATIBLE} — 兼容变更，可正常注册</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
public enum CompatibilityResult {

    /** 破坏性变更：需升级 MAJOR 版本号，否则拒绝注册 */
    BREAKING,

    /** 兼容变更：可正常注册 */
    COMPATIBLE
}
