/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.toolregistry.tool.product;

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
 * Product memory tool — records user product preferences and habits.
 * <p>Uses {@link AgentMemoryService} from common infrastructure.</p>
 */
@Component
public class ProductMemoryTool {

    private static final Logger log = LoggerFactory.getLogger(ProductMemoryTool.class);

    private static final String AGENT_NAME = "product";

    private final AgentMemoryService memoryService;
    private final ToolRegistry toolRegistry;

    public ProductMemoryTool(AgentMemoryService memoryService, ToolRegistry toolRegistry) {
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void initTools() {
        toolRegistry.registerAll(java.util.List.of(
                ToolDefinition.read("recallMemories", "获取用户商品偏好"),
                ToolDefinition.write("savePreference", "保存用户商品偏好",
                        com.example.smartassistant.common.gateway.tool.ToolRiskLevel.LOW)
        ));
    }

    @Tool(description = "保存用户的商品偏好，如常看品类、价格区间、品牌偏好等。用户明确表达偏好时调用。key常用值: frequentCategory(常看品类)/maxPrice(价格上限)/preferBrand(偏好品牌)")
    public void savePreference(
            @ToolParam(description = "用户ID", required = true) String userId,
            @ToolParam(description = "偏好键名，如 frequentCategory、maxPrice", required = true) String key,
            @ToolParam(description = "偏好值，如 '电子产品'、'200'", required = true) String value) {
        memoryService.save(AGENT_NAME, userId, key, value);
        log.info("[ProductMemory] 保存偏好: userId={}, key={}, value={}", userId, key, value);
    }

    @Tool(description = "获取用户保存的商品偏好和习惯，返回格式化的记忆列表。处理用户问题前可主动调用以了解用户偏好。")
    public String recallMemories(
            @ToolParam(description = "用户ID", required = true) String userId) {
        String memories = memoryService.getAllFormatted(AGENT_NAME, userId);
        if (memories.isBlank()) {
            return "该用户暂无保存的商品偏好信息。";
        }
        log.info("[ProductMemory] 召回记忆: userId={}", userId);
        return memories;
    }
}
