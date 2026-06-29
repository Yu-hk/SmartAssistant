package com.example.smartassistant.common.error;

/**
 * Agent 错误码枚举。
 * <p>
 * 统一标识 Agent 执行过程中的错误类型，每个错误码携带机器可读的编码、
 * 是否可重试标志和默认提示信息。上游 {@link ErrorRecoveryService}
 * 和 {@link com.example.smartassistant.common.tool.ToolResult} 据此定位恢复策略。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
public enum AgentErrorCode {

    // ==================== 路由相关 ====================

    /** 路由失败（不可重试） */
    SYSTEM_ROUTE_FAILED("SYSTEM_ROUTE_FAILED", false, "系统路由暂时不可用，请稍后再试"),

    /** Agent 调用超时（可重试） */
    AGENT_TIMEOUT("AGENT_TIMEOUT", true, "系统响应超时，请稍后再试"),

    /** Agent 返回空结果（可重试） */
    AGENT_EMPTY_REPLY("AGENT_EMPTY_REPLY", true, "暂时无法获取回复，请重新描述问题"),

    /** 智能体调用超时（不可重试） */
    SYSTEM_AGENT_TIMEOUT("SYSTEM_AGENT_TIMEOUT", false, "智能体调用超时，请稍后重试"),

    /** 智能体预算超限（不可重试） */
    SYSTEM_BUDGET_EXCEEDED("SYSTEM_BUDGET_EXCEEDED", false, "系统预算已超限，无法继续执行"),

    /** 模型调用失败（可重试） */
    MODEL_CALL_FAILED("MODEL_CALL_FAILED", true, "模型调用失败，请稍后重试"),

    /** 智能体无进展（不可重试） */
    SYSTEM_NO_INCREMENT("SYSTEM_NO_INCREMENT", false, "系统未能取得进展，请尝试其他方式"),

    /** 智能体达最大迭代次数（不可重试） */
    SYSTEM_MAX_ITERATIONS("SYSTEM_MAX_ITERATIONS", false, "处理步骤超出限制，请简化您的请求"),

    /** 未知工具（不可重试） */
    UNKNOWN_TOOL("UNKNOWN_TOOL", false, "未知的工具调用，请检查工具名称"),

    // ==================== 工具相关 ====================

    /** 工具执行失败（可重试） */
    TOOL_EXECUTION_FAILED("TOOL_EXECUTION_FAILED", true, "工具执行失败，请检查输入后重试"),

    /** 工具执行错误 */
    TOOL_EXECUTION_ERROR("TOOL_EXECUTION_ERROR", true, "工具执行异常，请稍后重试"),

    /** 工具参数无效（不可重试） */
    TOOL_INVALID_ARGUMENT("TOOL_INVALID_ARGUMENT", false, "输入参数不正确，请核对后重新提交"),

    /** 图片生成失败（可重试） */
    TOOL_IMAGE_GENERATION_FAILED("TOOL_IMAGE_GENERATION_FAILED", true, "图片生成失败，请稍后重试"),

    /** 图片生成超时（可重试） */
    TOOL_IMAGE_GENERATION_TIMEOUT("TOOL_IMAGE_GENERATION_TIMEOUT", true, "图片生成超时，请稍后重试"),

    // ==================== 校验相关 ====================

    /** 脚本为空（不可重试） */
    VALIDATION_SCRIPT_EMPTY("VALIDATION_SCRIPT_EMPTY", false, "脚本内容为空"),

    /** 安全拒绝（不可重试） */
    SECURITY_SCRIPT_REJECTED("SECURITY_SCRIPT_REJECTED", false, "脚本包含危险操作，已被安全机制拦截"),

    /** 资源超限（不可重试） */
    SECURITY_SCRIPT_RESOURCE_LIMIT("SECURITY_SCRIPT_RESOURCE_LIMIT", false, "脚本超出资源限制"),

    /** 脚本超时（可重试） */
    SECURITY_SCRIPT_TIMEOUT("SECURITY_SCRIPT_TIMEOUT", true, "脚本执行超时"),

    /** 表达式解析失败（不可重试） */
    VALIDATION_EXPRESSION_PARSE("VALIDATION_EXPRESSION_PARSE", false, "表达式解析失败"),

    /** 转换错误（不可重试） */
    VALIDATION_CONVERSION_ERROR("VALIDATION_CONVERSION_ERROR", false, "单位转换失败"),

    /** 无效货币代码（不可重试） */
    VALIDATION_INVALID_CURRENCY("VALIDATION_INVALID_CURRENCY", false, "不支持的货币代码"),

    /** 图片分析失败（可重试） */
    VALIDATION_IMAGE_ANALYSIS("VALIDATION_IMAGE_ANALYSIS", true, "图片分析失败，请检查图片URL后重试"),

    // ==================== 服务相关 ====================

    /** 新闻服务不可用（可重试） */
    SERVICE_NEWS_UNAVAILABLE("SERVICE_NEWS_UNAVAILABLE", true, "新闻服务暂时不可用，请稍后重试"),

    /** 搜索服务不可用（可重试） */
    SERVICE_SEARCH_UNAVAILABLE("SERVICE_SEARCH_UNAVAILABLE", true, "搜索服务暂时不可用，请稍后重试"),

    /** 汇率服务不可用（可重试） */
    SERVICE_RATE_UNAVAILABLE("SERVICE_RATE_UNAVAILABLE", true, "汇率服务暂时不可用，请稍后重试"),

    /** 天气服务不可用（可重试） */
    SERVICE_WEATHER_UNAVAILABLE("SERVICE_WEATHER_UNAVAILABLE", true, "天气服务暂时不可用，请稍后重试"),

    /** 优惠券查询失败（可重试） */
    SERVICE_COUPON_QUERY_FAILED("SERVICE_COUPON_QUERY_FAILED", true, "查询优惠券失败，请稍后重试"),

    /** 优惠计算失败（可重试） */
    SERVICE_COUPON_CALC_FAILED("SERVICE_COUPON_CALC_FAILED", true, "计算优惠方案失败，请稍后重试"),

    // ==================== 数据相关 ====================

    /** 数据不存在（不可重试） */
    DATA_NOT_FOUND("DATA_NOT_FOUND", false, "未找到相关数据"),

    /** 数据格式错误（不可重试） */
    DATA_FORMAT_ERROR("DATA_FORMAT_ERROR", false, "数据格式错误，请联系技术支持"),

    /** 订单未找到（不可重试） */
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", false, "未找到该订单"),

    /** 商品未找到（不可重试） */
    PRODUCT_NOT_FOUND("PRODUCT_NOT_FOUND", false, "未找到该商品"),

    /** 状态无效（不可重试） */
    INVALID_STATUS("INVALID_STATUS", false, "当前订单状态不允许此操作"),

    /** 更新失败（可重试） */
    UPDATE_FAILED("UPDATE_FAILED", true, "更新失败，请稍后重试"),

    /** 已在退款中（不可重试） */
    ALREADY_REFUNDING("ALREADY_REFUNDING", false, "该订单已在退款处理中"),

    /** 物流信息未找到（不可重试） */
    LOGISTICS_NOT_FOUND("LOGISTICS_NOT_FOUND", false, "未找到物流信息"),

    /** 需要快递单号（不可重试） */
    TRACKING_REQUIRED("TRACKING_REQUIRED", false, "请提供快递单号"),

    /** 天气数据不存在（不可重试） */
    WEATHER_NO_DATA("WEATHER_NO_DATA", false, "未找到该城市的天气数据"),

    /** 无结果（不可重试） */
    NO_RESULTS("NO_RESULTS", false, "未找到相关结果"),

    // ==================== 权限相关 ====================

    /** 权限不足（不可重试） */
    PERMISSION_DENIED("PERMISSION_DENIED", false, "您没有权限执行此操作"),

    /** 审批未通过（不可重试） */
    APPROVAL_REJECTED("APPROVAL_REJECTED", false, "操作未通过审批，如有疑问请联系管理员");

    // ==================== 字段 ====================

    private final String code;
    private final boolean retryable;
    private final String defaultHint;

    // ==================== 构造 ====================

    AgentErrorCode(String code, boolean retryable, String defaultHint) {
        this.code = code;
        this.retryable = retryable;
        this.defaultHint = defaultHint;
    }

    // ==================== 方法 ====================

    /** 获取机器可读的错误码字符串 */
    public String getCode() {
        return code;
    }

    /** 是否可重试（true=临时故障可重试，false=不可恢复） */
    public boolean isRetryable() {
        return retryable;
    }

    /** 获取默认提示信息 */
    public String getDefaultHint() {
        return defaultHint;
    }

    /** 获取错误码的描述（默认返回枚举名） */
    public String getDescription() {
        return name();
    }

    /**
     * 根据错误码字符串查找对应的枚举值。
     *
     * @param code 机器可读的错误码字符串（如 "ORDER_NOT_FOUND"）
     * @return 匹配的枚举值；未匹配返回 null
     */
    public static AgentErrorCode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String upper = code.toUpperCase().trim();
        for (AgentErrorCode value : values()) {
            if (value.code.equals(upper) || value.name().equals(upper)) {
                return value;
            }
        }
        return null;
    }
}
