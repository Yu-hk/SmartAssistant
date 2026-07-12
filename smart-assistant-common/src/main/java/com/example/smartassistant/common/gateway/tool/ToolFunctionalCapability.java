package com.example.smartassistant.common.gateway.tool;

/**
 * 功能性能力（Functional Capability）受控词表。
 * <p>
 * 与风险能力枚举 {@link ToolCapability} <b>正交</b>：{@code ToolCapability} 描述工具"有多危险 / 碰什么资源"
 * （用于治理、授权、scope 鉴权），而本枚举描述工具"能做什么业务动作"
 * （如 {@code order-query} 查订单、{@code order-refund} 退款申请），用于 T1 能力作用域预载与 T2 自主发现匹配。
 * </p>
 *
 * <p>词表为 kebab-case 受控令牌（首批 32 个，见设计文档 §7.1），字符串值持久化于
 * {@link ToolDefinition#getFunctionalCapabilities()}。注册 / 校验时按本枚举做受控校验。</p>
 *
 * <p><b>v1 校验策略为 WARN-only</b>：{@link #isValid(String)} 仅用于判定字符串是否为已知令牌；
 * 未知自定义词允许存在（不抛异常），由校验方（如 {@code ToolManifestValidator}，T6 阶段）决定是否告警。
 * 迁移窗关闭后可选收紧为 ERROR。</p>
 *
 * @author Yu-hk
 * @since 2026-07-12
 */
public enum ToolFunctionalCapability {

    // ==================== 首批受控词表（32 个，设计文档 §7.1） ====================

    /** 寒暄 / 闲聊 */
    GREETING("greeting"),

    /** 数学 / 脚本计算 */
    MATH_CALCULATE("math-calculate"),

    /** 单位换算 */
    UNIT_CONVERT("unit-convert"),

    /** 天气查询 */
    WEATHER_QUERY("weather-query"),

    /** 图片内容分析 */
    IMAGE_ANALYZE("image-analyze"),

    /** 文生图 */
    IMAGE_GENERATE("image-generate"),

    /** 趋势动画生成 */
    GIF_GENERATE("gif-generate"),

    /** 联网检索 */
    WEB_SEARCH("web-search"),

    /** 热点资讯 */
    NEWS_HOT("news-hot"),

    /** 历史纠错查询 */
    CORRECTION_QUERY("correction-query"),

    /** 商品详情查询 */
    PRODUCT_QUERY("product-query"),

    /** 库存查询 */
    PRODUCT_STOCK("product-stock"),

    /** 价格 / 促销查询 */
    PRODUCT_PRICE("product-price"),

    /** 商品知识库检索 */
    PRODUCT_KNOWLEDGE("product-knowledge"),

    /** 订单查询 */
    ORDER_QUERY("order-query"),

    /** 物流轨迹 */
    ORDER_LOGISTICS("order-logistics"),

    /** 创建订单 */
    ORDER_CREATE("order-create"),

    /** 支付订单 */
    ORDER_PAY("order-pay"),

    /** 取消订单 */
    ORDER_CANCEL("order-cancel"),

    /** 退款申请 */
    ORDER_REFUND("order-refund"),

    /** 商家发货 */
    ORDER_SHIP("order-ship"),

    /** 确认收货 / 确认支付 */
    ORDER_CONFIRM("order-confirm"),

    /** 优惠券查询 */
    ORDER_COUPON_QUERY("order-coupon-query"),

    /** 最优券组合 */
    ORDER_COUPON_OPTIMIZE("order-coupon-optimize"),

    /** 订单统计 / 分析 */
    ORDER_ANALYTICS("order-analytics"),

    /** 订单知识库检索 */
    ORDER_KNOWLEDGE("order-knowledge"),

    /** 商品偏好读取 */
    PRODUCT_PREFERENCE_READ("product-preference-read"),

    /** 商品偏好保存 */
    PRODUCT_PREFERENCE_WRITE("product-preference-write"),

    /** 订单偏好读取 */
    ORDER_PREFERENCE_READ("order-preference-read"),

    /** 订单偏好保存 */
    ORDER_PREFERENCE_WRITE("order-preference-write"),

    /** 通用知识库检索 */
    KNOWLEDGE_RETRIEVE("knowledge-retrieve"),

    /** 自然语言 / 直连 SQL 查询 */
    SQL_QUERY("sql-query");

    private final String value;

    ToolFunctionalCapability(String value) {
        this.value = value;
    }

    /**
     * 获取能力令牌的字符串值（kebab-case）。
     *
     * @return 能力令牌字符串（如 "order-query"）
     */
    public String getValue() {
        return value;
    }

    /**
     * 根据字符串值查找对应的功能性能力枚举。
     *
     * @param value 能力令牌字符串（如 "order-query"）
     * @return 匹配的枚举常量；若 {@code value} 为 {@code null} 或不属于已知令牌则返回 {@code null}
     */
    public static ToolFunctionalCapability fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ToolFunctionalCapability cap : values()) {
            if (cap.value.equals(value)) {
                return cap;
            }
        }
        return null;
    }

    /**
     * 校验给定字符串是否为已知的功能性能力令牌。
     * <p>v1 为宽松校验（WARN-only 语义）：本方法仅做"是否精确等于某个受控令牌"的判定，
     * 不抛异常；未知自定义词由调用方决定是否告警。</p>
     *
     * @param value 待校验字符串
     * @return 若精确等于某个已知令牌的 value 返回 {@code true}，否则 {@code false}
     */
    public static boolean isValid(String value) {
        return fromValue(value) != null;
    }
}
