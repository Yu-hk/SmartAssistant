/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.memory.ContextOrchestrator;
import com.example.smartassistant.common.memory.MemoryExtractor;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageSpan;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.core.OrderIntentService;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import com.example.smartassistant.service.core.OrderRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    /** 记忆后台提取器：对话结束后自动提取用户偏好 */
    private final MemoryExtractor memoryExtractor;
    /** 上下文协调器：统一调度四层记忆预算 */
    private final ContextOrchestrator orchestrator;

    /** ⭐ P1 全阶段 trace 记录器（可选，null 时跳过 trace） */
    @Autowired(required = false)
    private StageTraceRecorder stageTraceRecorder;

    /** 测试/手动注入用 setter */
    public void setStageTraceRecorder(StageTraceRecorder stageTraceRecorder) {
        this.stageTraceRecorder = stageTraceRecorder;
    }

    public OrderAgentController(SmartReActAgent orderAgent,
                                OrderIntentService intentService,
                                OrderRagService ragService,
                                MemoryExtractor memoryExtractor,
                                ContextOrchestrator orchestrator) {
        this.orderAgent = orderAgent;
        this.intentService = intentService;
        this.ragService = ragService;
        this.memoryExtractor = memoryExtractor;
        this.orchestrator = orchestrator;
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
        // ⭐ P1 全阶段 trace：使用真实 requestId 串联（Router 下发或本地生成）
        String requestId = request.getOrDefault("requestId", "ord-" + System.nanoTime());
        log.info("[OrderAgent] 收到请求: question={}, requestId={}", question, requestId);

        try {
            // Step 1: 意图识别
            IntentType intent = intentService.detect(question);

            // Step 2: 上下文协调器 + RAG 预检索（含质量评估）
            String userId = request.get("userId");
            List<String> extras = new ArrayList<>();

            // ⭐ P1: 先取检索质量结果，决定是否"无证据拒答"
            long retrievalStart = System.currentTimeMillis();
            RetrievalQualityResult qr = (ragService != null)
                    ? ragService.retrieveWithQualityResult(intent, question)
                    : RetrievalQualityResult.highQuality("", 1.0);
            long retrievalMs = System.currentTimeMillis() - retrievalStart;

            if (qr.isRejected()) {
                // ⭐ 无证据：短路，绝不调用 LLM（避免幻觉）
                if (stageTraceRecorder != null) {
                    stageTraceRecorder.getOrCreate(requestId, question, "order_agent")
                            .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                    Map.of("qualityScore", qr.getNormalizedScore(),
                                            "rejectionCode", qr.getRejectionCode())));
                    stageTraceRecorder.markRejection(requestId, qr.getRejectionCode(), qr.getRejectionMessage());
                    stageTraceRecorder.recordStage(requestId, RagStage.GENERATION, StageSpan.STATUS_SKIPPED, 0,
                            Map.of("reason", "no-evidence"));
                    stageTraceRecorder.save(requestId);
                }
                log.info("[OrderAgent] ⛔ 无证据拒答: intent={}, code={}, requestId={}",
                        intent.getLabel(), qr.getRejectionCode(), requestId);
                return qr.getRejectionMessage();
            }

            // 有证据：注入上下文
            String ragContext = ragService.buildEnhancedMessage(qr, question);
            if (!ragContext.equals(question)) {
                extras.add(ragContext);
                log.info("[OrderAgent] RAG 预检索已注入上下文");
            }

            // 通过 Orchestrator 构建分层 prompt
            String enhancedQuestion = orchestrator.buildPrompt(question, userId, "order",
                    extras.isEmpty() ? null : extras);
            log.info("[OrderAgent] 状态锚点已强制注入: userId={}", userId != null ? userId : "访客");

            // Step 4: Agent 执行（GENERATION 阶段 trace）
            log.info("[OrderAgent] 意图识别: {}, userId={}, 记忆注入={}", intent.getLabel(), userId, userId != null);
            long genStart = System.currentTimeMillis();
            String result;
            String genStatus = StageSpan.STATUS_OK;
            try {
                result = orderAgent.execute(enhancedQuestion);
            } catch (Exception e) {
                genStatus = StageSpan.STATUS_ERROR;
                throw e;
            } finally {
                long genMs = System.currentTimeMillis() - genStart;
                if (stageTraceRecorder != null) {
                    stageTraceRecorder.getOrCreate(requestId, question, "order_agent")
                            .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                    Map.of("qualityScore", qr.getNormalizedScore(),
                                            "highQuality", qr.isHighQuality())));
                    stageTraceRecorder.recordStage(requestId, RagStage.GENERATION, genStatus, genMs,
                            Map.of("outputLength", result != null ? result.length() : 0));
                    stageTraceRecorder.save(requestId);
                }
            }
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[OrderAgent] 处理完成: intent={},耗时={}ms,结果长度={}",
                    intent.getLabel(), elapsed, result != null ? result.length() : 0);

            // ⭐ Step 5: 后台自动提取偏好（不阻塞响应）
            if (result != null && userId != null && !userId.isBlank() && !"null".equals(userId)) {
                final String finalQuestion = question;
                final String finalResult = result;
                CompletableFuture.runAsync(() ->
                    memoryExtractor.extractFromConversation("order", userId, finalQuestion, finalResult));
            }

            return result != null ? result : "⚠️ Agent 返回空结果";

        } catch (Exception e) {
            log.error("[OrderAgent] 处理失败: {}", e.getMessage(), e);
            return "❌ 处理失败: " + e.getMessage();
        }
    }
}
