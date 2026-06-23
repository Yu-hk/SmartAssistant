/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.error;

/**
 * 统一错误码枚举——全项目工具层与 Agent 层的标准化错误码。
 * <p>
 * 每个错误码关联：错误分类 {@link ErrorCategory}、恢复动作 {@link RecoveryAction}、
 * 是否可重试、默认提示信息。
 * 由 {@link ErrorRecoveryService} 通过表驱动方式实现恢复策略路由。
 * </p>
 *
 * <h3>命名规范</h3>
 * <ul>
 *   <li>TOOL_* ：工具执行类</li>
 *   <li>SERVICE_* ：外部服务不可用</li>
 *   <li>VALIDATION_* ：参数校验/输入错误</li>
 *   <li>SECURITY_* ：安全限制</li>
 *   <li>SYSTEM_* ：系统内部错误</li>
 * </ul>
 *
 * @see ErrorCategory
 * @see RecoveryAction
 * @see ErrorRecoveryService
 */
public enum AgentErrorCode {

    // ==================== 工具执行类 ====================

    /** 工具执行时抛出未预期异常 */
    TOOL_EXECUTION_ERROR("TOOL_EXECUTION_ERROR", true, ErrorCategory.TOOL,
            RecoveryAction.RETRY, "工具执行异常，正在尝试重新执行"),
    /** 工具执行超时（默认 30s） */
    TOOL_TIMEOUT("TOOL_TIMEOUT", true, ErrorCategory.TOOL,
            RecoveryAction.RETRY_BACKOFF, "工具执行超时，正在退避重试"),
    /** LLM 调用了系统不存在的工具名 */
    UNKNOWN_TOOL("UNKNOWN_TOOL", false, ErrorCategory.TOOL,
            RecoveryAction.CLARIFY_USER, "未知工具调用，已阻止执行"),
    /** 模型调用失败（LLM 接口异常） */
    MODEL_CALL_FAILED("MODEL_CALL_FAILED", true, ErrorCategory.TOOL,
            RecoveryAction.RETRY_BACKOFF, "AI 服务暂时不可用，请稍后重试"),
    /** 图片生成失败 */
    TOOL_IMAGE_GENERATION_FAILED("IMAGE_GENERATION_FAILED", true, ErrorCategory.TOOL,
            RecoveryAction.RETRY, "图片生成失败，正在重试"),
    /** 图片生成超时 */
    TOOL_IMAGE_GENERATION_TIMEOUT("IMAGE_GENERATION_TIMEOUT", true, ErrorCategory.TOOL,
            RecoveryAction.RETRY_BACKOFF, "图片生成超时，正在退避重试"),

    // ==================== 数据查找类 ====================

    /** 订单不存在 */
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", false, ErrorCategory.DATA,
            RecoveryAction.CLARIFY_USER, "未找到该订单，请确认订单号是否正确"),
    /** 商品不存在 */
    PRODUCT_NOT_FOUND("PRODUCT_NOT_FOUND", false, ErrorCategory.DATA,
            RecoveryAction.CLARIFY_USER, "未找到该商品，请确认商品名称或编码"),
    /** 物流信息不存在 */
    LOGISTICS_NOT_FOUND("LOGISTICS_NOT_FOUND", false, ErrorCategory.DATA,
            RecoveryAction.CLARIFY_USER, "未找到物流信息，请确认快递单号"),
    /** 搜索无结果 */
    NO_RESULTS("NO_RESULTS", false, ErrorCategory.DATA,
            RecoveryAction.CLARIFY_USER, "未找到相关结果，请换一种描述"),
    /** 天气数据不存在 */
    WEATHER_NO_DATA("WEATHER_NO_DATA", false, ErrorCategory.DATA,
            RecoveryAction.CLARIFY_USER, "未找到该城市的天气数据"),

    // ==================== 状态冲突类 ====================

    /** 订单状态与操作不匹配 */
    INVALID_STATUS("INVALID_STATUS", false, ErrorCategory.STATE,
            RecoveryAction.CLARIFY_USER, "当前订单状态不支持该操作"),
    /** 已在退款处理中 */
    ALREADY_REFUNDING("ALREADY_REFUNDING", false, ErrorCategory.STATE,
            RecoveryAction.CLARIFY_USER, "该订单已在退款处理中"),
    /** 缺少物流单号 */
    TRACKING_REQUIRED("TRACKING_REQUIRED", false, ErrorCategory.STATE,
            RecoveryAction.CLARIFY_USER, "请提供快递单号以便查询"),
    /** 状态更新写入失败 */
    UPDATE_FAILED("UPDATE_FAILED", true, ErrorCategory.STATE,
            RecoveryAction.RETRY, "状态更新写入失败，正在重试"),

    // ==================== 外部服务类 ====================

    /** 新闻服务不可用 */
    SERVICE_NEWS_UNAVAILABLE("NEWS_UNAVAILABLE", true, ErrorCategory.SERVICE,
            RecoveryAction.FALLBACK_AGENT, "新闻服务暂不可用"),
    /** 搜索服务不可用 */
    SERVICE_SEARCH_UNAVAILABLE("SEARCH_UNAVAILABLE", true, ErrorCategory.SERVICE,
            RecoveryAction.FALLBACK_AGENT, "搜索服务暂不可用"),
    /** 天气服务不可用 */
    SERVICE_WEATHER_UNAVAILABLE("WEATHER_UNAVAILABLE", true, ErrorCategory.SERVICE,
            RecoveryAction.RETRY, "天气服务暂时无法访问"),
    /** 汇率服务不可用 */
    SERVICE_RATE_UNAVAILABLE("RATE_UNAVAILABLE", true, ErrorCategory.SERVICE,
            RecoveryAction.RETRY_BACKOFF, "汇率服务暂时不可用"),
    /** 优惠券查询失败 */
    SERVICE_COUPON_QUERY_FAILED("COUPON_QUERY_FAILED", true, ErrorCategory.SERVICE,
            RecoveryAction.RETRY, "查询优惠券失败，正在重试"),
    /** 优惠计算失败 */
    SERVICE_COUPON_CALC_FAILED("COUPON_CALC_FAILED", true, ErrorCategory.SERVICE,
            RecoveryAction.RETRY, "计算优惠方案失败，正在重试"),

    // ==================== 参数校验类 ====================

    /** 数学表达式解析失败 */
    VALIDATION_EXPRESSION_PARSE("EXPRESSION_PARSE_ERROR", false, ErrorCategory.VALIDATION,
            RecoveryAction.CLARIFY_USER, "数学表达式格式有误，请检查输入"),
    /** 不支持的货币代码 */
    VALIDATION_INVALID_CURRENCY("INVALID_CURRENCY", false, ErrorCategory.VALIDATION,
            RecoveryAction.CLARIFY_USER, "不支持的货币代码，请使用标准货币代码"),
    /** 空计算脚本 */
    VALIDATION_SCRIPT_EMPTY("SCRIPT_EMPTY", false, ErrorCategory.VALIDATION,
            RecoveryAction.CLARIFY_USER, "计算脚本为空，请输入计算表达式"),
    /** 图片分析失败 */
    VALIDATION_IMAGE_ANALYSIS("IMAGE_ANALYSIS_FAILED", false, ErrorCategory.VALIDATION,
            RecoveryAction.CLARIFY_USER, "图片分析失败，请确认图片内容"),
    /** 单位转换失败（温度/长度/重量/汇率） */
    VALIDATION_CONVERSION_ERROR("CONVERSION_ERROR", true, ErrorCategory.VALIDATION,
            RecoveryAction.RETRY, "单位转换失败，正在重试"),

    // ==================== 安全限制类 ====================

    /** 脚本包含危险操作被拒绝 */
    SECURITY_SCRIPT_REJECTED("SCRIPT_REJECTED", false, ErrorCategory.SECURITY,
            RecoveryAction.TERMINATE, "脚本包含不允许的操作，已拒绝执行"),

    // ==================== 系统内部类 ====================

    /** 错误序列化失败 */
    SYSTEM_SERIALIZATION_ERROR("SERIALIZATION_ERROR", false, ErrorCategory.SYSTEM,
            RecoveryAction.TERMINATE, "系统内部错误"),
    /** Token 预算耗尽 */
    SYSTEM_BUDGET_EXCEEDED("BUDGET_EXCEEDED", false, ErrorCategory.SYSTEM,
            RecoveryAction.CLARIFY_USER, "Token 预算即将耗尽，请总结当前进度"),
    /** Agent 执行超时 */
    SYSTEM_AGENT_TIMEOUT("AGENT_TIMEOUT", false, ErrorCategory.SYSTEM,
            RecoveryAction.FALLBACK_AGENT, "系统处理超时，请简化问题后重试"),
    /** 达到最大迭代次数 */
    SYSTEM_MAX_ITERATIONS("MAX_ITERATIONS", false, ErrorCategory.SYSTEM,
            RecoveryAction.FALLBACK_AGENT, "已达到最大执行次数上限"),
    /** 连续重复调用检测 */
    SYSTEM_NO_INCREMENT("NO_INCREMENT", false, ErrorCategory.SYSTEM,
            RecoveryAction.RETRY_ALTERNATIVE, "检测到重复调用，已切换策略"),
    /** 路由层整体失败 */
    SYSTEM_ROUTE_FAILED("ROUTE_FAILED", false, ErrorCategory.SYSTEM,
            RecoveryAction.FALLBACK_AGENT, "路由处理失败，请稍后重试");

    // ==================== 字段 ====================

    /** 字符串形式的错误码（保持与现有 ToolResult 格式兼容） */
    private final String code;

    /** 是否可重试 */
    private final boolean retryable;

    /** 错误分类 */
    private final ErrorCategory category;

    /** 默认恢复动作 */
    private final RecoveryAction defaultAction;

    /** 默认用户提示信息 */
    private final String defaultHint;

    AgentErrorCode(String code, boolean retryable, ErrorCategory category,
                   RecoveryAction defaultAction, String defaultHint) {
        this.code = code;
        this.retryable = retryable;
        this.category = category;
        this.defaultAction = defaultAction;
        this.defaultHint = defaultHint;
    }

    // ==================== Getters ====================

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
    public ErrorCategory getCategory() { return category; }
    public RecoveryAction getDefaultAction() { return defaultAction; }
    public String getDefaultHint() { return defaultHint; }

    // ==================== 查找方法 ====================

    /** 按字符串代码查找枚举，未找到返回 null */
    public static AgentErrorCode fromCode(String code) {
        if (code == null || code.isBlank()) return null;
        for (AgentErrorCode e : values()) {
            if (e.code.equals(code)) return e;
        }
        return null;
    }

    /** 按字符串代码查找，未找到时兜底返回 SYSTEM_SERIALIZATION_ERROR */
    public static AgentErrorCode fromCodeOrDefault(String code) {
        AgentErrorCode found = fromCode(code);
        return found != null ? found : SYSTEM_SERIALIZATION_ERROR;
    }
}
