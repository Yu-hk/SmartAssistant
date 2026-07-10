package com.example.smartassistant.common.gateway.tool.compat;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;

/**
 * 工具兼容性检查接口（REQ-04）。
 * <p>
 * 对比新旧 {@link ToolDefinition} 的能力契约差异，判定 {@link CompatibilityResult}。
 * v1 基于元数据字段（riskLevel/timeout/scopes/needsApproval）比对，
 * v2 可扩展参数级（inputSchema）比对。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
public interface ToolCompatibilityChecker {

    /**
     * 对比新旧 ToolDefinition，返回兼容性结果。
     *
     * @param oldDef 旧的工具定义（已注册）
     * @param newDef 新的工具定义（待注册）
     * @return 兼容性结果
     */
    CompatibilityResult check(ToolDefinition oldDef, ToolDefinition newDef);

    /**
     * 获取最近一次 {@link #check} 的原因说明。
     *
     * @return 原因字符串
     */
    String getReason();
}
