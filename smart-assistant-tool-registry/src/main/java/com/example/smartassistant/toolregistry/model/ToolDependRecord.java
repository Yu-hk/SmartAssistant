package com.example.smartassistant.toolregistry.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具依赖记录：记录哪个 Agent 调用了哪个工具。
 */
@Data
@Builder
@AllArgsConstructor
public class ToolDependRecord {

    /** Agent ID */
    private String agentId;

    /** 工具名称 */
    private String toolName;

    /** 最近调用时间 */
    private LocalDateTime lastCalledAt;

    /** 近 30 天调用次数 */
    private long callCount30d;
}
