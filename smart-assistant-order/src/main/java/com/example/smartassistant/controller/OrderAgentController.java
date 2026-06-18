/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.service.core.OrderIntentService;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import com.example.smartassistant.service.core.OrderRagService;
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
 * <p>
 * <b>改进</b>：加入意图检测 + RAG 预检索流程：
 * <ol>
 *   <li>识别用户意图（下单/查询/退款/取消/其他）</li>
 *   <li>根据意图预检索相关数据（如预查订单信息）</li>
 *   <li>将检索结果注入上下文后交给 Agent 执行</li>
 * </ol>
 * </p>
 */
@RestController
@RequestMapping("/api/order/agent")
public class OrderAgentController {

    private static final Logger log = LoggerFactory.getLogger(OrderAgentController.class);

    private final SmartReActAgent orderAgent;
    private final OrderIntentService intentService;
    private final OrderRagService ragService;

    public OrderAgentController(SmartReActAgent orderAgent,
                                OrderIntentService intentService,
                                OrderRagService ragService) {
        this.orderAgent = orderAgent;
        this.intentService = intentService;
        this.ragService = ragService;
    }

    /**
     * 处理用户问题并返回 Agent 响应。
     * <p>
     * 流程：
     * <ol>
     *   <li>LLM 意图识别（下单/查询/退款/取消）</li>
     *   <li>根据意图 RAG 预检索（如预查订单信息）</li>
     *   <li>将检索结果注入上下文</li>
     *   <li>交给 Agent 执行并返回</li>
     * </ol>
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

        long startTime = System.currentTimeMillis();
        log.info("[OrderAgent] 收到请求: question={}", question);

        try {
            // Step 1: 意图识别
            IntentType intent = intentService.detect(question);
            log.info("[OrderAgent] 意图识别: {}", intent.getLabel());

            // Step 2: RAG 预检索 + 上下文注入
            String enhancedQuestion = ragService.buildEnhancedMessage(intent, question);
            if (!enhancedQuestion.equals(question)) {
                log.info("[OrderAgent] RAG 预检索已注入上下文");
            }

            // Step 3: Agent 执行
            String result = orderAgent.execute(enhancedQuestion);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[OrderAgent] 处理完成: intent={},耗时={}ms,结果长度={}",
                    intent.getLabel(), elapsed, result != null ? result.length() : 0);
            return result != null ? result : "⚠️ Agent 返回空结果";

        } catch (Exception e) {
            log.error("[OrderAgent] 处理失败: {}", e.getMessage(), e);
            return "❌ 处理失败: " + e.getMessage();
        }
    }
}
