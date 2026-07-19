/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * General Agent HTTP 直调控制器
 * <p>
 * 替代 A2A 协议，为 Router 提供直接的 HTTP 调用入口（与 OrderAgentController 对齐）。
 * Router 的 {@code AgentCallerService} 统一 POST 到各 Agent 的
 * {@code /api/order/agent/process} 端点，请求体为 {"question": "...", "userId": "..."}，
 * 期望返回纯文本应答。
 * </p>
 */
@RestController
@RequestMapping("/api/order/agent")
public class GeneralAgentController {

    private static final Logger log = LoggerFactory.getLogger(GeneralAgentController.class);

    private final SmartReActAgent generalChatAgent;

    @Autowired
    public GeneralAgentController(SmartReActAgent generalChatAgent) {
        this.generalChatAgent = generalChatAgent;
    }

    /**
     * 处理通用对话问题并返回 Agent 响应。
     *
     * @param request 请求体，包含 question / userId 字段
     * @return Agent 执行结果（纯文本）
     */
    @PostMapping("/process")
    public String processQuestion(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return "❌ 问题不能为空";
        }

        String userId = request.get("userId");
        String requestId = request.getOrDefault("requestId", "gen-" + System.nanoTime());
        log.info("[GeneralAgent] 收到请求: question={}, userId={}, requestId={}", question, userId, requestId);

        long startTime = System.currentTimeMillis();
        try {
            String result = generalChatAgent.execute(question);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[GeneralAgent] 处理完成: 耗时={}ms, 结果长度={}",
                    elapsed, result != null ? result.length() : 0);
            return result != null ? result : "⚠️ Agent 返回空结果";
        } catch (Exception e) {
            log.error("[GeneralAgent] 处理失败: {}", e.getMessage(), e);
            return "❌ 处理失败: " + e.getMessage();
        }
    }
}
