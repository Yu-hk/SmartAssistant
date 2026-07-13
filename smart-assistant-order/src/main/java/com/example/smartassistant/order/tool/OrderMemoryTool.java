/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.order.tool;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.memory.AgentMemoryService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Order memory tool — records user preferences and habits for order domain.
 * <p>Uses {@link AgentMemoryService} from common infrastructure.</p>
 */
@Component
public class OrderMemoryTool {

    private static final Logger log = LoggerFactory.getLogger(OrderMemoryTool.class);

    private static final String AGENT_NAME = "order";

    private final AgentMemoryService memoryService;
    private final ToolRegistry toolRegistry;

    public OrderMemoryTool(AgentMemoryService memoryService, ToolRegistry toolRegistry) {
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void initTools() {
        toolRegistry.registerAll(java.util.List.of(
                ToolDefinition.read("recallMemories", "获取用户订单偏好"),
                ToolDefinition.write("savePreference", "保存用户订单偏好",
                        com.example.smartassistant.common.gateway.tool.ToolRiskLevel.LOW)
        ));
    }

    @Tool(description = "保存用户的订单相关偏好，如常用路线、座位偏好、支付方式等。用户明确表达偏好时调用。key常用值: preferWindowSeat(靠窗)/preferAisleSeat(过道)/frequentRoute(常用路线)/frequentDeparture(常用出发地)")
    public void savePreference(
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "偏好键名，如 preferWindowSeat、frequentRoute") String key,
            @ToolParam(description = "偏好值，如 '靠窗'、'北京→上海'") String value) {
        memoryService.save(AGENT_NAME, userId, key, value);
        log.info("[OrderMemory] 保存偏好: userId={}, key={}, value={}", userId, key, value);
    }

    @Tool(description = "获取用户保存的订单相关偏好和习惯，返回格式化的记忆列表。在处理用户问题前可主动调用以了解用户偏好。")
    public String recallMemories(
            @ToolParam(description = "用户ID") String userId) {
        String memories = memoryService.getAllFormatted(AGENT_NAME, userId);
        if (memories.isBlank()) {
            return "该用户暂无保存的订单偏好信息。";
        }
        log.info("[OrderMemory] 召回记忆: userId={}", userId);
        return memories;
    }
}
