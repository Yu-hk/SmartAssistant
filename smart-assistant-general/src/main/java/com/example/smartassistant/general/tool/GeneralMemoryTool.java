/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.memory.AgentMemoryService;
import com.example.smartassistant.common.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * General conversation memory tool — records user preferences and habits.
 * <p>Uses {@link AgentMemoryService} from common infrastructure.</p>
 */
@Component
public class GeneralMemoryTool {

    private static final Logger log = LoggerFactory.getLogger(GeneralMemoryTool.class);

    private static final String AGENT_NAME = "general";

    private final AgentMemoryService memoryService;

    public GeneralMemoryTool(AgentMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(description = "保存用户的通用偏好，如回复风格、常用单位、兴趣话题等。用户明确表达偏好时调用。key常用值: replyStyle(回复风格)/preferTempUnit(温度单位)/preferCurrency(常用币种)")
    public String savePreference(
            @ToolParam(description = "用户ID", required = true) String userId,
            @ToolParam(description = "偏好键名，如 replyStyle、preferTempUnit", required = true) String key,
            @ToolParam(description = "偏好值", required = true) String value) {
        log.info("[GeneralMemory] 保存偏好: userId={}, key={}, value={}", userId, key, value);
        if (userId == null || userId.isBlank()) {
            return ToolResult.error(AgentErrorCode.TOOL_INVALID_ARGUMENT, "用户ID不能为空");
        }
        if (key == null || key.isBlank()) {
            return ToolResult.error(AgentErrorCode.TOOL_INVALID_ARGUMENT, "偏好键名不能为空");
        }
        if (value == null || value.isBlank()) {
            return ToolResult.error(AgentErrorCode.TOOL_INVALID_ARGUMENT, "偏好值不能为空");
        }
        try {
            memoryService.save(AGENT_NAME, userId, key, value);
            return "已保存偏好: " + key + " = " + value;
        } catch (Exception e) {
            log.error("[GeneralMemory] 保存偏好异常: {}", e.getMessage(), e);
            return ToolResult.error(AgentErrorCode.TOOL_EXECUTION_FAILED, "保存偏好失败，请稍后重试");
        }
    }

    @Tool(description = "获取用户保存的通用偏好和习惯，返回格式化的记忆列表。处理用户问题前可主动调用以了解用户偏好。")
    public String recallMemories(
            @ToolParam(description = "用户ID", required = true) String userId) {
        log.info("[GeneralMemory] 召回记忆: userId={}", userId);
        if (userId == null || userId.isBlank()) {
            return ToolResult.error(AgentErrorCode.TOOL_INVALID_ARGUMENT, "用户ID不能为空");
        }
        try {
            String memories = memoryService.getAllFormatted(AGENT_NAME, userId);
            if (memories.isBlank()) {
                return "该用户暂无保存的通用偏好信息。";
            }
            return memories;
        } catch (Exception e) {
            log.error("[GeneralMemory] 召回记忆异常: {}", e.getMessage(), e);
            return ToolResult.error(AgentErrorCode.TOOL_EXECUTION_FAILED, "查询偏好失败，请稍后重试");
        }
    }
}
