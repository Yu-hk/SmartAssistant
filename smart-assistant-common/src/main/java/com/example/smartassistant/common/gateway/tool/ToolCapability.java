package com.example.smartassistant.common.gateway.tool;

/**
 * 工具<b>风险能力</b>标签枚举（REQ-05）。
 * <p>
 * 表示工具的「风险 / 访问」维度：工具"有多危险 / 碰什么资源"，用于治理、授权与 scope 鉴权。
 * 取值由 {@link ToolDefinition} 的 {@code riskLevel} 推导
 * （{@code READ}→{@code read-only}；{@code LOW/MEDIUM}→{@code mutate-state}；
 * {@code HIGH}→{@code mutate-state}+{@code payment}），见 {@link ToolDefinition#getCapabilities()}。
 * </p>
 *
 * <p><b>正交性</b>：本枚举描述风险能力，与功能性能力枚举
 * {@link ToolFunctionalCapability}（描述业务动作，如 {@code order-query} / {@code order-refund}）正交，
 * 二者语义不重叠、互不影响。</p>
 *
 * <p>v1 预定义 6 个能力标签 + {@link #UNKNOWN} 兜底，用于校验和查询过滤。</p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
public enum ToolCapability {

    /** 只读操作：查询、搜索（无副作用） */
    READ_ONLY("read-only"),

    /** 状态变更：写入、更新、删除 */
    MUTATE_STATE("mutate-state"),

    /** 网络调用：外部 HTTP/API 请求 */
    NETWORK_CALL("network-call"),

    /** 支付相关：涉及资金操作 */
    PAYMENT("payment"),

    /** 数据访问：数据库/存储读写 */
    DATA_ACCESS("data-access"),

    /** AI 推理：模型调用、向量检索 */
    AI_INFERENCE("ai-inference"),

    /** 未知能力（兜底默认值） */
    UNKNOWN("unknown");

    private final String value;

    ToolCapability(String value) {
        this.value = value;
    }

    /**
     * 获取能力标签的字符串值。
     *
     * @return 能力标签字符串（如 "read-only"）
     */
    public String getValue() {
        return value;
    }

    /**
     * 校验给定的能力标签字符串是否为预定义值。
     *
     * @param capability 能力标签字符串
     * @return 如果是预定义值返回 {@code true}，否则 {@code false}
     */
    public static boolean isValid(String capability) {
        if (capability == null || capability.isBlank()) {
            return false;
        }
        for (ToolCapability cap : values()) {
            if (cap.value.equals(capability)) {
                return true;
            }
        }
        return false;
    }
}
