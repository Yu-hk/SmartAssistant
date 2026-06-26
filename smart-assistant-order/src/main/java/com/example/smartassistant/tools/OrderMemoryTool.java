/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.memory.AgentMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 订单记忆工具——为用户记录常用偏好和习惯，提升后续服务体验。
 *
 * <p>该工具允许 Agent 主动记录用户的行程偏好、座位偏好、常订路线等信息，
 * 下次同一用户来访时自动调用 {@link #recallMemories(String)} 获取记忆上下文。
 * 记忆自动过期（默认 60 天），无需手动清理。</p>
 *
 * <p>写入时机：用户明确表达了偏好时（如"订票尽量靠窗"、"我经常去上海"），
 * Agent 应主动调用 {@link #savePreference(String, String, String)} 记录。</p>
 *
 * <p>读取时机：每次处理用户问题前，系统会自动注入该用户的记忆上下文，
 * Agent 也可主动调用 {@link #recallMemories(String)} 查看完整记忆。</p>
 */
@Component
public class OrderMemoryTool {

    private static final Logger log = LoggerFactory.getLogger(OrderMemoryTool.class);

    private static final String AGENT_NAME = "order";

    private final AgentMemoryService memoryService;

    public OrderMemoryTool(AgentMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 保存用户的常用偏好设置，供后续服务参考。
     *
     * <p>系统会在每次服务前自动注入用户记忆上下文，此工具供 Agent 在
     * 用户明确表达偏好时调用。后续同用户再次咨询时，系统将自动提供
     * 保存的记忆信息。</p>
     *
     * @param userId 用户ID（数字字符串）
     * @param key    偏好键名，建议使用英文驼峰命名，如 preferWindowSeat、frequentRoute
     * @param value  偏好值，如 "靠窗"、"北京→上海"
     */
    @Tool(description = "保存用户的订单相关偏好，如常用路线、座位偏好、支付方式等。用户明确表达偏好时调用。key常用值: preferWindowSeat(靠窗)/preferAisleSeat(过道)/frequentRoute(常用路线)/frequentDeparture(常用出发地)")
    public void savePreference(
            @ToolParam(description = "用户ID", required = true) String userId,
            @ToolParam(description = "偏好键名，如 preferWindowSeat、frequentRoute", required = true) String key,
            @ToolParam(description = "偏好值，如 '靠窗'、'北京→上海'", required = true) String value) {
        memoryService.save(AGENT_NAME, userId, key, value);
        log.info("[OrderMemory] 保存偏好: userId={}, key={}, value={}", userId, key, value);
    }

    /**
     * 获取该用户的所有已保存订单记忆。
     *
     * @param userId 用户ID
     * @return 格式化的记忆文本；无记忆时返回提示信息
     */
    @Tool(description = "获取用户保存的订单相关偏好和习惯，返回格式化的记忆列表。在处理用户问题前可主动调用以了解用户偏好。")
    public String recallMemories(
            @ToolParam(description = "用户ID", required = true) String userId) {
        String memories = memoryService.getAllFormatted(AGENT_NAME, userId);
        if (memories.isBlank()) {
            return "该用户暂无保存的订单偏好信息。";
        }
        log.info("[OrderMemory] 召回记忆: userId={}", userId);
        return memories;
    }
}
