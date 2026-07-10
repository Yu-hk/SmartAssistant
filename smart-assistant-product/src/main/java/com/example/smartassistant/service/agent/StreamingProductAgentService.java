/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageSpan;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.search.ProductRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Product 流式 Agent 服务。
 * <p>
 * ⭐ P1 增强：在执行 Agent 之前先做 RAG 检索质量评估。
 * <ul>
 *   <li>无证据（{@code isRejected()}）→ 直接返回结构化拒答消息，<b>不调用 LLM</b>（避免幻觉）。</li>
 *   <li>有证据 → 把检索到的商品知识注入上下文后再交给 Agent，并记录全阶段 trace。</li>
 *   <li>RAG 检索异常 → 降级为"无上下文直接生成"，不阻断主流程。</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
public class StreamingProductAgentService {

    private final SmartReActAgent productAgent;
    private final ProductRagService productRagService;

    /** ⭐ P1 全阶段 trace 记录器（可选，null 时跳过 trace） */
    @Autowired(required = false)
    private StageTraceRecorder stageTraceRecorder;

    /** ⭐ P5-A 生产忠实度护栏（可选，默认内置实例；测试可注入定制实例） */
    private FaithfulnessGuard faithfulnessGuard = new FaithfulnessGuard();

    /** ⭐ G4 运营指标收集器（应答/无证据拒答），零装配、全局注册表 */
    private final OpsMetrics opsMetrics = new OpsMetrics();

    /** 测试/手动注入用 setter */
    public void setStageTraceRecorder(StageTraceRecorder stageTraceRecorder) {
        this.stageTraceRecorder = stageTraceRecorder;
    }

    /** 测试可注入定制 FaithfulnessGuard */
    public void setFaithfulnessGuard(FaithfulnessGuard faithfulnessGuard) {
        this.faithfulnessGuard = faithfulnessGuard;
    }

    public StreamingProductAgentService(@Qualifier("productAgent") SmartReActAgent productAgent,
                                        @Autowired(required = false) ProductRagService productRagService) {
        this.productAgent = productAgent;
        this.productRagService = productRagService;
    }

    /**
     * 执行商品咨询（兼容旧调用，自动生成 requestId）。
     */
    public String execute(String userMessage) {
        return execute(userMessage, null);
    }

    /**
     * 执行商品咨询（带请求级 requestId，用于全阶段 trace 关联）。
     *
     * @param userMessage 用户消息
     * @param requestId   请求 ID（Consumer/Router 下发；为 null 时本地生成）
     * @return Agent 回复或结构化拒答消息
     */
    public String execute(String userMessage, String requestId) {
        String rid = (requestId != null && !requestId.isBlank()) ? requestId : ("prod-" + System.nanoTime());
        // ⭐ G4 运营指标：记录一次商品域应答（无答案率分母）
        opsMetrics.recordAnswer("product", "product");
        try {
            log.info("[StreamingProductAgent] 执行推理: {}, requestId={}", userMessage, rid);

            // ⭐ P1: RAG 检索质量评估（决定拒答 or 注入上下文）
            // P5-A: ragContext 提升到外层作用域，供 GENERATION 后的 Faithfulness 校验使用
            String ragContext = null;
            if (productRagService != null) {
                try {
                    long retrievalStart = System.currentTimeMillis();
                    RetrievalQualityResult qr = productRagService.retrieveWithQualityResult(userMessage);
                    long retrievalMs = System.currentTimeMillis() - retrievalStart;

                    if (qr.isRejected()) {
                        // 无证据：短路拒答，不调用 LLM
                        // ⭐ G4 运营指标：记录无证据拒答
                        opsMetrics.recordNoEvidenceAnswer("product", "product");
                        if (stageTraceRecorder != null) {
                            stageTraceRecorder.getOrCreate(rid, userMessage, "product_agent")
                                    .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                            Map.of("qualityScore", qr.getNormalizedScore(),
                                                    "rejectionCode", qr.getRejectionCode())));
                            stageTraceRecorder.markRejection(rid, qr.getRejectionCode(), qr.getRejectionMessage());
                            stageTraceRecorder.recordStage(rid, RagStage.GENERATION, StageSpan.STATUS_SKIPPED, 0,
                                    Map.of("reason", "no-evidence"));
                            stageTraceRecorder.save(rid);
                        }
                        log.info("[StreamingProductAgent] ⛔ 无证据拒答: code={}, requestId={}",
                                qr.getRejectionCode(), rid);
                        return qr.getRejectionMessage();
                    }

                    // 有证据：记录 RETRIEVAL 阶段；高质量时把知识注入上下文
                    if (stageTraceRecorder != null) {
                        stageTraceRecorder.getOrCreate(rid, userMessage, "product_agent")
                                .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                        Map.of("qualityScore", qr.getNormalizedScore(),
                                                "highQuality", qr.isHighQuality())));
                    }
                    if (qr.isHighQuality() && qr.getContent() != null && !qr.getContent().isBlank()) {
                        userMessage = "[系统已检索到以下商品信息]\n" + qr.getContent()
                                + "\n\n用户问题：" + userMessage;
                        ragContext = qr.getContent();
                        log.info("[StreamingProductAgent] RAG 知识已注入上下文");
                    }
                } catch (Exception ragEx) {
                    // RAG 失败：降级为无上下文生成，不阻断主流程
                    log.warn("[StreamingProductAgent] RAG 检索失败，降级无上下文生成: {}", ragEx.getMessage());
                }
            }

            // ⭐ GENERATION 阶段
            long genStart = System.currentTimeMillis();
            String result = null;
            String genStatus = StageSpan.STATUS_OK;
            try {
                result = productAgent.execute(userMessage);
                // ⭐ P5-A 生产 Faithfulness 校验（文章Q⑩校验层）：
                // 回答关键断言未被检索上下文支撑时，非阻断地追加免责声明 + 埋点（log）
                if (ragContext != null && !ragContext.isBlank()) {
                    FaithfulnessGuard.FaithfulnessVerdict fg = faithfulnessGuard.check(result, ragContext);
                    if (fg.hallucination()) {
                        result = result + "\n\n" + fg.message();
                        log.warn("[StreamingProductAgent] ⚠️ Faithfulness 风险: score={}, claims={}",
                                String.format("%.2f", fg.score()), fg.claims().size());
                    }
                }
            } catch (Exception e) {
                genStatus = StageSpan.STATUS_ERROR;
                throw e;
            } finally {
                long genMs = System.currentTimeMillis() - genStart;
                if (stageTraceRecorder != null) {
                    stageTraceRecorder.recordStage(rid, RagStage.GENERATION, genStatus, genMs,
                            Map.of("outputLength", result != null ? result.length() : 0));
                    stageTraceRecorder.save(rid);
                }
            }

            if (result != null) {
                return result;
            }
            return "Agent 返回为空";
        } catch (Exception e) {
            log.error("[StreamingProductAgent] 执行异常: {}", e.getMessage(), e);
            return "处理失败: " + e.getMessage();
        }
    }
}
