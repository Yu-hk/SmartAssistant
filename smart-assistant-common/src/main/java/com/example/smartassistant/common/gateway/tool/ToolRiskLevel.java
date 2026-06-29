package com.example.smartassistant.common.gateway.tool;

/**
 * 工具风险等级枚举。
 */
public enum ToolRiskLevel {
    /** 只读操作：查询、搜索（无副作用） */
    READ,
    /** 低风险写操作：收藏、关注等 */
    LOW,
    /** 中风险操作：修改配置、非关键数据写等 */
    MEDIUM,
    /** 高风险操作：退款、删除、涉及资金等 */
    HIGH
}
