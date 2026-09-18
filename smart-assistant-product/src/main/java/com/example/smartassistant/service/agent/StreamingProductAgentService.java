/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.quality.DomainAgentResponse;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TierModelRegistry;
import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageSpan;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.search.ProductRagService;
import com.example.smartassistant.service.core.ProductDiscoveryService;
import com.example.smartassistant.service.core.ProductPublicAnswer;
import com.example.smartassistant.service.core.StructuredProductRecommendation;
import com.example.smartassistant.service.quality.ProductDomainQualityValidator;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ProductDomainQualityValidator domainQualityValidator;
    private final ProductDiscoveryService productDiscoveryService;

    @Autowired
    private com.example.smartassistant.common.rag.source.UserDocumentQaService userDocumentQaService;

    /** ⭐ P1 全阶段 trace 记录器（可选，null 时跳过 trace） */
    @Autowired(required = false)
    private StageTraceRecorder stageTraceRecorder;

    /** 商品推荐场景的数据分析 Prompt；缺失时保持确定性目录结果。 */
    @Autowired(required = false)
    private PromptManager promptManager;

    /** 固定档位模型：Flash 负责分析，Pro 负责核实与推荐。 */
    @Autowired(required = false)
    private TierModelRegistry tierModelRegistry;

    @Value("${product.recommendation.max-reanalysis:1}")
    private int maxReanalysis = 1;

    @Value("${product.recommendation.analysis-max-tokens:900}")
    private int analysisMaxTokens = 900;

    @Value("${product.recommendation.audit-max-tokens:256}")
    private int auditMaxTokens = 256;

    @Value("${product.recommendation.output-max-tokens:700}")
    private int recommendationMaxTokens = 700;

    @Value("${product.recommendation.disable-thinking:true}")
    private boolean disableRecommendationThinking = true;

    @Value("${spring.ai.openai.base-url:}")
    private String modelApiBaseUrl = "";

    @Value("${product.rag.answer-verification.max-retries:1}")
    private int ragAnswerVerificationMaxRetries = 1;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** ⭐ P5-A 生产忠实度护栏（可选，默认内置实例；测试可注入定制实例） */
    private FaithfulnessGuard faithfulnessGuard = new FaithfulnessGuard();

    /** ⭐ G4 运营指标收集器（应答/无证据拒答），零装配、全局注册表 */
    private final OpsMetrics opsMetrics = new OpsMetrics();

    /** 测试/手动注入用 setter */
    public void setStageTraceRecorder(StageTraceRecorder stageTraceRecorder) {
        this.stageTraceRecorder = stageTraceRecorder;
    }

    /** 测试/手动注入用 setter。 */
    public void setPromptManager(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    /** 测试/手动注入用 setter。 */
    public void setTierModelRegistry(TierModelRegistry tierModelRegistry) {
        this.tierModelRegistry = tierModelRegistry;
    }

    /** 测试可覆盖最大重分析次数。 */
    public void setMaxReanalysis(int maxReanalysis) {
        this.maxReanalysis = Math.max(0, maxReanalysis);
    }

    /** Tests may override the bounded output budgets without Spring property binding. */
    void setRecommendationTokenLimits(int analysis, int audit, int recommendation) {
        this.analysisMaxTokens = Math.max(64, analysis);
        this.auditMaxTokens = Math.max(64, audit);
        this.recommendationMaxTokens = Math.max(64, recommendation);
    }

    /** 测试可注入定制 FaithfulnessGuard */
    public void setFaithfulnessGuard(FaithfulnessGuard faithfulnessGuard) {
        this.faithfulnessGuard = faithfulnessGuard;
    }

    public StreamingProductAgentService(SmartReActAgent productAgent,
                                        ProductRagService productRagService) {
        this(productAgent, productRagService, new ProductDomainQualityValidator(), null);
    }

    @Autowired
    public StreamingProductAgentService(@Qualifier("productAgent") SmartReActAgent productAgent,
                                        @Autowired(required = false) ProductRagService productRagService,
                                        ProductDomainQualityValidator domainQualityValidator,
                                        @Autowired(required = false) ProductDiscoveryService productDiscoveryService) {
        this.productAgent = productAgent;
        this.productRagService = productRagService;
        this.domainQualityValidator = domainQualityValidator;
        this.productDiscoveryService = productDiscoveryService;
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
        return executeWithQuality(userMessage, requestId).answer();
    }

    /** Executes product consultation and exposes the domain quality decision to HTTP callers. */
    public DomainAgentResponse executeWithQuality(String userMessage, String requestId) {
        var document = com.example.smartassistant.common.rag.source.UserDocumentContext.from(userMessage);
        if (document.userOnly()) {
            return userDocumentQaService.answer(document);
        }
        String originalUserMessage = userMessage;
        String rid = (requestId != null && !requestId.isBlank()) ? requestId : ("prod-" + System.nanoTime());
        // ⭐ G4 运营指标：记录一次商品域应答（无答案率分母）
        opsMetrics.recordAnswer("product", "product");
        try (var toolEvidence = com.example.smartassistant.service.quality.ProductToolEvidenceScope.open()) {
            log.info("[StreamingProductAgent] 执行推理: requestId={}, messageLength={}",
                    rid, userMessage != null ? userMessage.length() : 0);

            // Generic catalog/popularity questions are deterministic data queries, not RAG questions.
            // Handle them before RAG so an empty semantic retrieval cannot reject a valid discovery request.
            if (productDiscoveryService != null && productDiscoveryService.supports(userMessage)) {
                long discoveryStart = System.currentTimeMillis();
                ProductDiscoveryService.DiscoveryResult discovery =
                        productDiscoveryService.discover(userMessage, null);
                long discoveryMs = System.currentTimeMillis() - discoveryStart;
                String answer = discovery.answer();
                String generationStatus = StageSpan.STATUS_SKIPPED;
                long generationMs = 0L;
                boolean analysisApplied = false;
                DomainQualityResult generatedQuality = null;

                // 兼容直连入口也必须走 Flash 分析 + Pro 核实推荐；失败时回退确定性目录。
                if (promptManager != null && tierModelRegistry != null
                        && discovery.productCount() > 0 && !discovery.browsingOnly()) {
                    long generationStart = System.currentTimeMillis();
                    try {
                        List<Map<?, ?>> catalog = discovery.products().stream()
                                .map(p -> (Map<?, ?>) objectMapper.convertValue(p, Map.class))
                                .collect(java.util.stream.Collectors.toList());
                        DomainAgentResponse analysis = analyzeVerifiedContext(
                                userMessage, discovery.answer(), catalog, rid);
                        if (!analysis.quality().isFail()) {
                            DomainAgentResponse recommendation = verifyAnalysisAndRecommend(
                                    userMessage,
                                    discovery.answer() + "\n\n[Flash 分析结果]\n" + analysis.answer(),
                                    catalog, rid);
                            if (!recommendation.quality().isFail()) {
                                if (isPopularityRequest(userMessage)
                                        && isRecommendationDeferral(recommendation.answer())) {
                                    log.info("[StreamingProductAgent] 热门请求的模型结论要求补充条件，"
                                            + "回退站内排行榜: requestId={}", rid);
                                } else {
                                    answer = recommendation.answer();
                                    generatedQuality = recommendation.quality();
                                    analysisApplied = true;
                                }
                                generationStatus = StageSpan.STATUS_OK;
                            }
                        }
                    } catch (Exception analysisError) {
                        generationStatus = StageSpan.STATUS_ERROR;
                        log.warn("[StreamingProductAgent] 商品推荐分析失败，回退真实目录结果: {}",
                                analysisError.getMessage());
                    } finally {
                        generationMs = System.currentTimeMillis() - generationStart;
                    }
                }
                if (stageTraceRecorder != null) {
                    stageTraceRecorder.getOrCreate(rid, userMessage, "product_agent")
                            .addStage(StageSpan.of(RagStage.RETRIEVAL, discoveryMs, StageSpan.STATUS_OK,
                                    Map.of("mode", "product-discovery",
                                            "productCount", discovery.productCount(),
                                            "popularityBased", discovery.popularityBased())));
                    stageTraceRecorder.recordStage(rid, RagStage.GENERATION, generationStatus, generationMs,
                            Map.of("analysisApplied", analysisApplied,
                                    "fallback", !analysisApplied,
                                    "auditVerified", generatedQuality != null));
                    stageTraceRecorder.save(rid);
                }
                DomainQualityResult quality = generatedQuality != null
                        ? generatedQuality
                        : discovery.clarificationRequired()
                        ? DomainQualityResult.pass(1.0, "PRODUCT_PREFERENCE_CLARIFICATION")
                        : discovery.productCount() > 0
                        ? discovery.scenarioEvidenceLimited()
                            ? DomainQualityResult.warn(0.7, "PRODUCT_SCENARIO_EVIDENCE_LIMITED")
                            : DomainQualityResult.pass(1.0, "PRODUCT_DISCOVERY_DATA")
                        : DomainQualityResult.pass(1.0, "EMPTY_PRODUCT_CATALOG");
                return DomainAgentResponse.of(ProductPublicAnswer.format(answer), quality);
            }

            // ⭐ P1: RAG 检索质量评估（决定拒答 or 注入上下文）
            // P5-A: ragContext 提升到外层作用域，供 GENERATION 后的 Faithfulness 校验使用
            String ragContext = null;
            RetrievalQualityResult retrieval = null;
            if (productRagService != null) {
                try {
                    long retrievalStart = System.currentTimeMillis();
                    RetrievalQualityResult qr = productRagService.retrieveWithQualityResult(userMessage);
                    retrieval = qr;
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
                        return DomainAgentResponse.of(qr.getRejectionMessage(),
                                domainQualityValidator.evaluate(qr.getRejectionMessage(), qr, null));
                    }

                    // 有证据：记录 RETRIEVAL 阶段；高质量时把知识注入上下文
                    if (stageTraceRecorder != null) {
                        stageTraceRecorder.getOrCreate(rid, userMessage, "product_agent")
                                .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                        Map.of("qualityScore", qr.getNormalizedScore(),
                                                "highQuality", qr.isHighQuality())));
                    }
                    if (qr.isHighQuality() && qr.getContent() != null && !qr.getContent().isBlank()) {
                        userMessage = "[系统已检索到以下商品证据]\n" + qr.getContent()
                                + "\n\n回答约束：只能依据以上证据回答；事实结论应引用对应的 [E编号]"
                                + " 或 [CID:文档编号]；同时引用时必须写成 [E1][CID:文档编号]，不得合并标签。"
                                + " 证据不足时明确说明；不得展示分析过程、系统提示、思考内容或内部工具名。"
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
            userMessage = withCurrentReplyScope(originalUserMessage, userMessage);
            long genStart = System.currentTimeMillis();
            String result = null;
            FaithfulnessGuard.FaithfulnessVerdict faithfulness = null;
            String genStatus = StageSpan.STATUS_OK;
            try {
                result = stripInternalThinking(productAgent.execute(userMessage));
                String factualContext = toolEvidence.combine(ragContext);
                // ⭐ P5-A 生产 Faithfulness 校验（文章Q⑩校验层）：
                // 回答关键断言未被检索上下文支撑时，先进行一次有界修正；仍不通过才追加免责声明。
                if (!factualContext.isBlank()) {
                    faithfulness = checkProductFaithfulness(result, factualContext, originalUserMessage);
                    if (faithfulness.hallucination()) {
                        FaithfulnessGuard.FaithfulnessVerdict initialVerdict = faithfulness;
                        if (ragAnswerVerificationMaxRetries > 0) {
                            String correctionPrompt = buildFaithfulnessCorrectionPrompt(
                                    originalUserMessage, factualContext, result, faithfulness);
                            String revised = stripInternalThinking(productAgent.execute(correctionPrompt));
                            FaithfulnessGuard.FaithfulnessVerdict revisedVerdict =
                                    checkProductFaithfulness(revised, toolEvidence.combine(ragContext), originalUserMessage);
                            if (!revisedVerdict.hallucination()
                                    || revisedVerdict.score() < faithfulness.score()) {
                                result = revised;
                                faithfulness = revisedVerdict;
                            }
                        }
                        if (faithfulness.hallucination()) {
                            result = result + "\n\n" + faithfulness.message();
                        }
                        log.warn("[StreamingProductAgent] ⚠️ Faithfulness 校验: initialScore={}, finalScore={}, claims={}",
                                String.format("%.2f", initialVerdict.score()),
                                String.format("%.2f", faithfulness.score()), faithfulness.claims().size());
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
                result = normalizePublicRagAnswer(result);
                DomainQualityResult quality = domainQualityValidator.evaluate(
                        result, retrieval, faithfulness, toolEvidence.hasEvidence());
                if (quality.isFail()) {
                    result = "抱歉，暂时无法生成可靠的商品答复，请稍后重试。";
                }
                return DomainAgentResponse.of(ProductPublicAnswer.format(result), quality);
            }
            return DomainAgentResponse.of("Agent 返回为空",
                    DomainQualityResult.fail("EMPTY_PRODUCT_ANSWER"));
        } catch (com.example.smartassistant.common.error.ModelCallFailure e) {
            return DomainAgentResponse.of("抱歉，暂时无法完成这次商品查询，请稍后再试。",
                    DomainQualityResult.fail(e.code()));
        } catch (com.example.smartassistant.spi.ProductCatalogUnavailableException e) {
            return DomainAgentResponse.of(e.getMessage(), DomainQualityResult.fail(
                    com.example.smartassistant.spi.ProductCatalogUnavailableException.CODE));
        } catch (Exception e) {
            log.error("[StreamingProductAgent] 执行异常: {}", e.getMessage(), e);
            return DomainAgentResponse.of("处理失败: " + e.getMessage(),
                    DomainQualityResult.fail("PRODUCT_EXECUTION_ERROR"));
        }
    }

    private static boolean isPopularityRequest(String message) {
        if (message == null || message.isBlank()) return false;
        String normalized = message.replaceAll("\\s+", "");
        return normalized.contains("热门") || normalized.contains("热销")
                || normalized.contains("畅销") || normalized.contains("排行");
    }

    private static boolean isRecommendationDeferral(String answer) {
        if (answer == null || answer.isBlank()) return true;
        return answer.contains("无法形成唯一推荐")
                || answer.contains("无法可靠推荐")
                || answer.contains("暂不推荐唯一商品")
                || answer.contains("不能推荐唯一商品")
                || answer.contains("需要补充用户")
                || answer.contains("需补充用户");
    }

    static String normalizePublicRagAnswer(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        return answer.replaceAll("\\[E(\\d+)-CID:([^\\]]+)]", "[E$1][CID:$2]");
    }

    private static final Pattern ANALYSIS_SECTION_HEADING = Pattern.compile(
            "(?m)^\\h*【(?:数据概览|分析过程|核心结论|建议与可视化)】\\h*(?:\\r?\\n|$)");

    FaithfulnessGuard.FaithfulnessVerdict checkProductFaithfulness(
            String answer, String context, String question) {
        if (answer == null || question == null) return faithfulnessGuard.check(answer, context);
        var checked = com.example.smartassistant.service.quality.ProductBudgetClaimVerifier.verify(
                answer, context, question);
        if (!checked.errors().isEmpty()) {
            return new FaithfulnessGuard.FaithfulnessVerdict(true, true, 0.7, checked.errors(),
                    "商品价格与预算计算尚未通过核实，请以实际价格为准。");
        }
        // Only the required standalone headings are formatting, not factual entity claims.
        return faithfulnessGuard.check(ANALYSIS_SECTION_HEADING.matcher(checked.answer()).replaceAll(""),
                com.example.smartassistant.service.quality.ProductMoneySyntax.normalize(context));
    }

    static String withCurrentReplyScope(String originalQuestion, String evidencePrompt) {
        if (originalQuestion == null || originalQuestion.isBlank()) return evidencePrompt;
        // Router appends history under this marker. Keep it for product identity,
        // but never treat earlier answer dimensions as part of the current request.
        int history = originalQuestion.indexOf("[对话上下文]");
        String current = (history >= 0 ? originalQuestion.substring(0, history) : originalQuestion).trim();
        return evidencePrompt + "\n\n[本轮回答范围]\n当前用户提问：" + current
                + "\n仅回答当前提问涉及的维度；历史用于确认商品指代，不是额外的提问。"
                + "只问规格/参数时不附带颜色、价格、库存；只问颜色时不附带其他规格。"
                + "明确同时询问时才一起回答，必要的版本消歧和安全限制仍须保留。"
                + "工具和资料中的其他字段仅供核实，不要转述或在结尾主动邀约。"
                + "本轮所问字段已回答完整就直接结束，不再追加‘如需了解价格或库存’、‘其他方面’等服务邀约；"
                + "只有完成当前任务确实缺少信息、存在版本歧义或需要安全确认时才追问。";
    }

    static String buildFaithfulnessCorrectionPrompt(
            String originalQuestion,
            String evidence,
            String previousAnswer,
            FaithfulnessGuard.FaithfulnessVerdict verdict) {
        return withCurrentReplyScope(originalQuestion, """
                [系统：答案事实校验未通过，请修正]
                用户问题：%s

                可用证据：
                %s

                上一次答案：
                %s

                校验发现 %d 条无证据断言。请删除或改写所有无证据内容，仅依据证据给出最终答案，
                并使用 [E编号] 或 [CID:文档编号] 标注事实来源。请直接给出经过核实的简洁答复，省略推理过程。
                """.formatted(originalQuestion, evidence, previousAnswer,
                verdict.claims() == null ? 0 : verdict.claims().size()));
    }

    private static String stripInternalThinking(String value) {
        if (value == null) return null;
        return value.replaceAll("(?is)<think(?:ing)?>.*?</think(?:ing)?>", "")
                .replaceAll("(?is)\\[思考过程].*?(?=\\n\\s*\\n|$)", "")
                .strip();
    }

    /**
     * Executes an analysis/recommendation node against verified predecessor output.
     * This entry point deliberately skips catalog re-query and RAG so the node consumes exactly
     * the evidence produced by its declared DAG dependencies.
     */
    public DomainAgentResponse analyzeVerifiedContext(String question, String verifiedContext,
                                                      List<? extends Map<?, ?>> products,
                                                      String requestId) {
        return structuredProductDecision(question, verifiedContext, products, requestId, false);
    }

    public DomainAgentResponse verifyAnalysisAndRecommend(String question, String verifiedContext,
                                                          List<? extends Map<?, ?>> products,
                                                          String requestId) {
        return structuredProductDecision(question, verifiedContext, products, requestId, true);
    }

    private DomainAgentResponse structuredProductDecision(String question, String context,
            List<? extends Map<?, ?>> products, String requestId, boolean review) {
        String rid = requestId == null || requestId.isBlank() ? "prod-structured-" + System.nanoTime() : requestId;
        try {
            var facts = new StructuredProductRecommendation(question, products);
            if (!facts.hasEligibleProducts()) return DomainAgentResponse.of(facts.noEligibleAnswer(),
                    DomainQualityResult.pass(1.0, "NO_ELIGIBLE_VERIFIED_PRODUCT"));
            if (promptManager == null || tierModelRegistry == null || !tierModelRegistry.has(ModelTier.LIGHT)
                    || review && !hasDistinctAnalysisAndRecommendationModels()) {
                return DomainAgentResponse.of("商品分析或核实模型未就绪。",
                        DomainQualityResult.fail("PRODUCT_STRUCTURED_MODEL_UNAVAILABLE"));
            }
            String evidence = facts.promptData() + "\n[待核实上游分析，不可覆盖目录事实]\n" + context;
            var decision = requestStructuredDecision(facts, question, evidence, rid, review);
            // A real audit rejection remains a rejection, with at most one bounded revision.
            if (review && !decision.valid() && maxReanalysis > 0) {
                var revised = requestStructuredDecision(facts, question,
                        facts.promptData() + "\n[修正要求]\n" + decision.correction(), rid, false);
                if (revised.valid()) decision = requestStructuredDecision(facts, question,
                        facts.promptData() + "\n[修正后分析]\n" + facts.renderAnalysis(revised), rid, true);
            }
            if (!decision.valid()) return DomainAgentResponse.of("商品信息尚未通过核实，暂时无法给出可靠推荐。",
                    DomainQualityResult.fail("PRODUCT_STRUCTURED_DECISION_REJECTED"));
            return DomainAgentResponse.of(review ? facts.renderRecommendation(decision) : facts.renderAnalysis(decision),
                    DomainQualityResult.pass(1.0, review ? "PRODUCT_STRUCTURED_RECOMMENDATION_VERIFIED" : "PRODUCT_STRUCTURED_ANALYSIS_VERIFIED"));
        } catch (Exception error) {
            // Never log model output, catalog contents or user profiles here.
            log.warn("[ProductStructuredDecision] requestId={}, errorType={}", rid, error.getClass().getSimpleName());
            return DomainAgentResponse.of("商品数据或分析结果未通过校验，请稍后重试。",
                    DomainQualityResult.fail("INVALID_STRUCTURED_PRODUCT_DECISION"));
        }
    }

    private StructuredProductRecommendation.Decision requestStructuredDecision(
            StructuredProductRecommendation facts,
            String question, String evidence, String rid, boolean review) {
        ModelTier tier = review ? ModelTier.HEAVY : ModelTier.LIGHT;
        String prompt = promptManager.renderStructuredProductDecision(question, evidence, review);
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw = callBoundedModel(tierModelRegistry.get(tier), tierModelRegistry.modelName(tier),
                    prompt, review ? Math.max(auditMaxTokens, recommendationMaxTokens) : analysisMaxTokens, rid);
            try { return facts.parse(raw); }
            catch (IllegalArgumentException invalid) {
                if (attempt == 1) throw invalid;
                prompt += "\n上次结构化决策未通过校验。请检查商品 code、可用证据和 eligible，仅返回规定字段，禁止价格或结论字段。";
            }
        }
        throw new IllegalStateException("Structured decision unavailable");
    }

    public DomainAgentResponse analyzeVerifiedContext(String question, String verifiedContext,
                                                      String requestId) {
        if (verifiedContext == null || verifiedContext.isBlank()) {
            return DomainAgentResponse.of(
                    "缺少上游真实商品数据，无法执行分析或推荐。",
                    DomainQualityResult.fail("MISSING_PRODUCT_ANALYSIS_CONTEXT"));
        }
        if (promptManager == null || tierModelRegistry == null
                || !tierModelRegistry.has(ModelTier.LIGHT)) {
            return DomainAgentResponse.of(
                    "Flash 数据分析模型未就绪，无法生成可靠分析。",
                    DomainQualityResult.fail("PRODUCT_ANALYSIS_MODEL_UNAVAILABLE"));
        }

        String rid = requestId != null && !requestId.isBlank()
                ? requestId : "prod-analysis-" + System.nanoTime();
        try {
            String prompt = promptManager.renderDataAnalysisExpert(question, verifiedContext)
                    + "\n\n请压缩在600个中文字符以内，保留四个输出模块、关键数值证据和数据限制。";
            String answer = callBoundedModel(
                    tierModelRegistry.get(ModelTier.LIGHT),
                    tierModelRegistry.modelName(ModelTier.LIGHT),
                    prompt, analysisMaxTokens, rid);
            if (answer == null || answer.isBlank()) {
                return DomainAgentResponse.of("商品分析结果为空。",
                        DomainQualityResult.fail("EMPTY_PRODUCT_ANALYSIS"));
            }
            FaithfulnessGuard.FaithfulnessVerdict verdict =
                    checkProductFaithfulness(answer, verifiedContext, question);
            DomainQualityResult quality = DomainQualityResult.pass(1.0, "PRODUCT_ANALYSIS_FLASH");
            if (verdict.hallucination()) {
                // Log rule categories only: no raw answer, prompt, profile or claim snippets.
                log.warn("[ProductAnalysisQuality] requestId={}, score={}, claimCount={}, claimTypes={}",
                        rid, verdict.score(), verdict.claims().size(),
                        verdict.claims().stream().map(claim -> claim.type()).distinct().toList());
                answer = answer + "\n\n" + verdict.message();
                quality = DomainQualityResult.warn(0.4,
                        "PRODUCT_ANALYSIS_FLASH", "UNSUPPORTED_PRODUCT_ANALYSIS_CLAIMS");
            }
            log.info("[StreamingProductAgent] 验证数据分析完成: requestId={}, quality={}",
                    rid, quality.getStatus());
            return DomainAgentResponse.of(answer, quality);
        } catch (Exception error) {
            log.error("[StreamingProductAgent] 验证数据分析失败: requestId={}, error={}",
                    rid, error.getMessage(), error);
            return DomainAgentResponse.of("商品数据分析失败：" + error.getMessage(),
                    DomainQualityResult.fail("PRODUCT_ANALYSIS_ERROR"));
        }
    }

    /**
     * Uses the Pro model to audit Flash analysis, requests at most one Flash revision when needed,
     * then lets Pro produce the final fact-checked recommendation.
     */
    public DomainAgentResponse verifyAnalysisAndRecommend(String question, String verifiedContext,
                                                          String requestId) {
        if (verifiedContext == null || verifiedContext.isBlank()) {
            return DomainAgentResponse.of("缺少候选商品和分析结果，无法核实推荐。",
                    DomainQualityResult.fail("MISSING_RECOMMENDATION_CONTEXT"));
        }
        if (!hasDistinctAnalysisAndRecommendationModels()) {
            return DomainAgentResponse.of("商品分析与推荐模型未按 Flash/Pro 独立配置。",
                    DomainQualityResult.fail("PRODUCT_MODELS_NOT_DISTINCT"));
        }

        String rid = requestId != null && !requestId.isBlank()
                ? requestId : "prod-recommend-" + System.nanoTime();
        ChatModel analysisModel = tierModelRegistry.get(ModelTier.LIGHT);
        ChatModel recommendationModel = tierModelRegistry.get(ModelTier.HEAVY);
        String workingContext = verifiedContext;
        int revisionCount = 0;
        try {
            AnalysisAudit audit = auditAndRecommend(recommendationModel, question, workingContext, rid);
            while (!audit.valid() && revisionCount < Math.max(0, maxReanalysis)) {
                logAuditRejection(rid, revisionCount, audit);
                revisionCount++;
                String correctionPrompt = promptManager.renderDataAnalysisExpert(
                        question,
                        verifiedContext + "\n\n[Pro 核实模型的修正要求]\n"
                                + audit.correctionInstruction()
                                + "\n问题明细：" + audit.issues());
                String revisedAnalysis = callBoundedModel(
                        analysisModel,
                        tierModelRegistry.modelName(ModelTier.LIGHT),
                        correctionPrompt
                                + "\n\n请压缩在600个中文字符以内，只修正审计指出的问题。",
                        analysisMaxTokens, rid);
                if (revisedAnalysis == null || revisedAnalysis.isBlank()) {
                    return DomainAgentResponse.of("Flash 重分析结果为空，推荐流程已停止。",
                            DomainQualityResult.fail("EMPTY_REANALYSIS_RESULT"));
                }
                workingContext = verifiedContext + "\n\n[Flash 修正后的分析]\n" + revisedAnalysis;
                audit = auditAndRecommend(recommendationModel, question, workingContext, rid);
            }

            if (!audit.valid()) {
                logAuditRejection(rid, revisionCount, audit);
                return DomainAgentResponse.of(
                        "当前推荐尚未通过商品信息核实，暂时无法给出可靠结论。请稍后重试，或提供具体商品型号以便核对。",
                        DomainQualityResult.fail("PRODUCT_ANALYSIS_AUDIT_REJECTED"));
            }

            String recommendation = audit.conclusion();
            if (recommendation == null || recommendation.isBlank()) {
                return deterministicVerifiedFallback(
                        verifiedContext, "EMPTY_VERIFIED_RECOMMENDATION");
            }

            FaithfulnessGuard.FaithfulnessVerdict verdict =
                    checkProductFaithfulness(recommendation, workingContext, question);
            if (verdict.hallucination()) {
                return deterministicVerifiedFallback(
                        verifiedContext, "UNSUPPORTED_VERIFIED_RECOMMENDATION");
            }
            String reason = revisionCount > 0
                    ? "PRODUCT_RECOMMENDATION_PRO_VERIFIED_AFTER_REANALYSIS"
                    : "PRODUCT_RECOMMENDATION_PRO_VERIFIED";
            log.info("[StreamingProductAgent] Pro 推荐核实完成: requestId={}, revisions={}",
                    rid, revisionCount);
            return DomainAgentResponse.of(ProductPublicAnswer.format(recommendation), DomainQualityResult.pass(1.0, reason));
        } catch (Exception error) {
            if (error instanceof IllegalStateException) {
                log.warn("[StreamingProductAgent] Pro 推荐核实格式异常，回退已验证候选: "
                        + "requestId={}, error={}", rid, error.getMessage());
            } else {
                log.error("[StreamingProductAgent] Pro 推荐核实失败: requestId={}, error={}",
                        rid, error.getMessage(), error);
            }
            return deterministicVerifiedFallback(
                    verifiedContext, "PRODUCT_RECOMMENDATION_AUDIT_ERROR");
        }
    }

    /**
     * Model formatting failures must not discard already verified catalog facts. This fallback
     * exposes one concise candidate from predecessor output and never invents missing attributes.
     */
    private DomainAgentResponse deterministicVerifiedFallback(String verifiedContext, String reason) {
        String candidate = firstVerifiedCandidate(verifiedContext);
        if (candidate == null) {
            return DomainAgentResponse.of(
                    "当前商品数据不足以形成可靠推荐，请补充可核实的商品信息后重试。",
                    DomainQualityResult.fail(reason));
        }
        return DomainAgentResponse.of(
                "根据当前可核实的商品数据，优先考虑" + candidate + "。",
                DomainQualityResult.warn(0.75,
                        "PRODUCT_DETERMINISTIC_VERIFIED_FALLBACK", reason));
    }

    private static String firstVerifiedCandidate(String context) {
        if (context == null || context.isBlank()) return null;
        for (String line : context.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("\\d+\\.\\s+.+")
                    && (trimmed.contains("¥") || trimmed.contains("价格"))) {
                return trimmed.replaceFirst("^\\d+\\.\\s*", "")
                        .replaceFirst("[。；;]+$", "");
            }
        }
        Matcher matcher = Pattern.compile("(?:候选|商品)[:：]\\s*([^\\r\\n]+)")
                .matcher(context);
        if (!matcher.find()) return null;
        String value = matcher.group(1).trim().replaceFirst("[。；;]+$", "");
        return value.isBlank() ? null : value;
    }

    private boolean hasDistinctAnalysisAndRecommendationModels() {
        if (promptManager == null || tierModelRegistry == null
                || !tierModelRegistry.has(ModelTier.LIGHT)
                || !tierModelRegistry.has(ModelTier.HEAVY)) {
            return false;
        }
        String light = tierModelRegistry.modelName(ModelTier.LIGHT);
        String heavy = tierModelRegistry.modelName(ModelTier.HEAVY);
        return light != null && heavy != null && !light.equalsIgnoreCase(heavy);
    }

    private static void logAuditRejection(String requestId, int revisions, AnalysisAudit audit) {
        // Keep bounded diagnostic details in server logs, never in the public response.
        log.warn("[StreamingProductAgent] 推荐审核拒绝: requestId={}, revisions={}, issues={}, correction={}",
                requestId, revisions,
                truncateForLog(String.valueOf(audit.issues()), 500),
                truncateForLog(audit.correctionInstruction(), 500));
    }

    private AnalysisAudit auditAndRecommend(ChatModel recommendationModel,
                                            String question, String context, String requestId) throws Exception {
        String auditPrompt = promptManager.renderProductAuditAndRecommendation(question, context);
        String raw = callBoundedModel(
                recommendationModel,
                tierModelRegistry.modelName(ModelTier.HEAVY),
                auditPrompt,
                Math.max(auditMaxTokens, recommendationMaxTokens), requestId);
        AnalysisAudit parsed = parseAudit(raw);
        if (isCompleteReview(parsed)) return parsed;

        // Formatting failure is not a factual rejection. Ask the same Pro model once
        // to repeat the audit in the required schema instead of sending the whole
        // recommendation node (or the Flash analysis) through an expensive retry.
        log.warn("[StreamingProductAgent] Pro 核实推荐结果格式无效，执行一次 JSON 定向重试: raw={}",
                truncateForLog(raw, 240));
        String retryPrompt = auditPrompt
                + "\n\n上一次输出未能解析为规定 JSON。请重新完成同一核实任务，"
                + "只输出包含 valid、issues、correction_instruction、conclusion 的单个 JSON 对象；"
                + "不要输出 Markdown、解释或其他文字。";
        String retriedRaw = callBoundedModel(
                recommendationModel,
                tierModelRegistry.modelName(ModelTier.HEAVY),
                retryPrompt,
                Math.max(auditMaxTokens, recommendationMaxTokens), requestId);
        parsed = parseAudit(retriedRaw);
        if (isCompleteReview(parsed)) return parsed;
        throw new IllegalStateException("Pro 核实推荐模型连续两次未返回完整合法 JSON");
    }

    private static boolean isCompleteReview(AnalysisAudit review) {
        return review != null && (!review.valid()
                || (review.conclusion() != null && !review.conclusion().isBlank()));
    }

    /**
     * Typed analysis/recommendation nodes need concise factual output, not a long reasoning trace.
     * Request-level options preserve the tier-selected model while bounding generation cost.
     */
    private String callBoundedModel(ChatModel model, String modelName,
                                    String prompt, int maxTokens, String requestId) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                // Spring AI 2.0 defaults a fresh OpenAI options object to gpt-5-mini;
                // explicitly retain the tier-selected model on this bounded request.
                .model(modelName)
                .maxTokens(Math.max(64, maxTokens));
        Map<String, Object> thinkingOptions = thinkingExtraBody(
                disableRecommendationThinking, modelApiBaseUrl);
        if (!thinkingOptions.isEmpty()) {
            builder.extraBody(thinkingOptions);
        }
        ChatResponse response = model.call(new Prompt(prompt, builder.build()));
        com.example.smartassistant.common.rag.advisor.TokenUsageAdvisor.recordDirectUsage(
                requestId, response == null ? null : response.getMetadata());
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    static Map<String, Object> thinkingExtraBody(boolean disabled, String baseUrl) {
        if (!disabled) return Map.of();
        String normalizedBaseUrl = baseUrl == null
                ? "" : baseUrl.toLowerCase(java.util.Locale.ROOT);
        if (normalizedBaseUrl.contains("deepseek")) {
            return Map.of("thinking", Map.of("type", "disabled"));
        }
        return Map.of("enable_thinking", false);
    }

    private AnalysisAudit parseAudit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Map<String, Object> data = firstJsonObjectContaining(raw, "valid");
        if (data == null || !data.containsKey("valid")) return null;
        boolean valid = Boolean.TRUE.equals(data.get("valid"))
                || "true".equalsIgnoreCase(String.valueOf(data.get("valid")));
        List<String> issues = new ArrayList<>();
        if (data.get("issues") instanceof List<?> values) {
            values.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).filter(value -> !value.isBlank())
                    .forEach(issues::add);
        }
        String instruction = String.valueOf(data.getOrDefault("correction_instruction", ""));
        String conclusion = data.get("conclusion") == null ? ""
                : stripInternalThinking(String.valueOf(data.get("conclusion"))).strip();
        if (!valid && issues.isEmpty()) issues.add("Pro 核实未通过但未返回问题明细");
        if (!valid && instruction.isBlank()) instruction = String.join("；", issues);
        return new AnalysisAudit(valid, List.copyOf(issues), instruction, conclusion);
    }

    private Map<String, Object> firstJsonObjectContaining(String raw, String requiredKey) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (depth == 0) start = index;
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    try {
                        Map<String, Object> candidate = objectMapper.readValue(
                                raw.substring(start, index + 1),
                                new TypeReference<Map<String, Object>>() { });
                        if (candidate.containsKey(requiredKey)) return candidate;
                    } catch (Exception ignored) {
                        // Continue scanning in case the model emitted another valid object.
                    }
                    start = -1;
                }
            }
        }
        return null;
    }

    private static String truncateForLog(String value, int maxLength) {
        if (value == null) return "<null>";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private record AnalysisAudit(boolean valid, List<String> issues,
                                 String correctionInstruction, String conclusion) {}
}
