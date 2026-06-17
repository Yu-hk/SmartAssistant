/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent HTTP 直调控制器
 * <p>
 * 替代 A2A 协议，为 Router 提供直接的 HTTP 调用入口。
 * Router 不再需要经过 A2aRemoteAgent，直接 POST 到此端点即可获取 Agent 处理结果。
 * </p>
 */
@RestController
@RequestMapping("/api/order/agent")
public class OrderAgentController {

    private static final Logger log = LoggerFactory.getLogger(OrderAgentController.class);

    private final SmartReActAgent orderAgent;

    public OrderAgentController(SmartReActAgent orderAgent) {
        this.orderAgent = orderAgent;
    }

    /**
     * 处理用户问题并返回 Agent 响应。
     * <p>
     * Router 调用示例：
     * <pre>
     * POST /api/order/agent/process
     * Content-Type: application/json
     *
     * {"question": "帮我下单买一台MacBook Pro，价格25999元"}
     * </pre>
     * </p>
     *
     * @param request 请求体，包含 question 字段
     * @return Agent 执行结果
     */
    @PostMapping("/process")
    public String processQuestion(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return "❌ 问题不能为空";
        }

        log.info("[OrderAgentController] 处理问题: {}", question);

        long startTime = System.currentTimeMillis();
        try {
            String result = orderAgent.execute(question);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[OrderAgentController] 处理完成 (耗时 {}ms), 结果长度={}", elapsed, result != null ? result.length() : 0);
            return result != null ? result : "⚠️ Agent 返回空结果";
        } catch (Exception e) {
            log.error("[OrderAgentController] 处理失败: {}", e.getMessage(), e);
            return "❌ 处理失败: " + e.getMessage();
        }
    }
}
