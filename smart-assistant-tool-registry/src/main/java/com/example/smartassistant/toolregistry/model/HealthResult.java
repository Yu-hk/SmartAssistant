package com.example.smartassistant.toolregistry.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 健康检查结果：聚合所有工具的健康状态。
 */
@Data
@Builder
@AllArgsConstructor
public class HealthResult {

    /** 工具总数 */
    private int total;

    /** 健康数（ACTIVE / EXPERIMENTAL 且无异常） */
    private int healthy;

    /** 降级数（DEPRECATED / 错误率过高） */
    private int degraded;

    /** 已废弃数 */
    private int deprecated;

    /** 各工具的健康状态列表 */
    private java.util.List<ToolHealthItem> tools;

    @Data
    @Builder
    @AllArgsConstructor
    public static class ToolHealthItem {
        private String name;
        private String status;
        private double errorRate;
        private long avgLatencyMs;
    }
}
