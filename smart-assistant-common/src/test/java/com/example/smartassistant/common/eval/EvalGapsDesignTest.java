/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;
import com.example.smartassistant.common.eval.grader.GraderResult;
import com.example.smartassistant.common.eval.grader.GraderWaterfall;
import com.example.smartassistant.common.eval.grader.LlmGrader;
import com.example.smartassistant.common.eval.grader.RuleGrader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测体系四大缺口（文章《Agent 评测体系》）落地设计的验证测试：
 * ① Trial / pass^k　② Grader 瀑布流（规则→LLM + 偏差防护）　③ 人工路由　④ 根因分析。
 *
 * <p>纯单元测试，LLM 调用以 Mockito 桩替代，无需 Ollama/Redis 基础设施。</p>
 */
class EvalGapsDesignTest {

    // ==================== ① Trial / pass^k ====================

    @Test
    void passKMath() {
        double p = 0.8;
        int k = 10;
        double atK = PassKCalculator.passAtK(p, k);
        double powK = PassKCalculator.passPowerK(p, k);
        assertTrue(atK > 0.999, "pass@10 应≈1");
        assertEquals(Math.pow(0.8, 10), powK, 1e-9);
        assertEquals(0.0, PassKCalculator.passPowerK(0.0, 5));
        assertEquals(1.0, PassKCalculator.passAtK(1.0, 5));
    }

    @Test
    void trialRunnerAggregates() {
        AgentTestSpec spec = spec("T1", "order");
        TrialRunner.TrialExecutor alwaysPass = s -> passResult(s.id, s.agentName, s.expectedIntent, s.expectedTools);
        TrialResult tr = new TrialRunner().run(spec, 5, alwaysPass);
        assertEquals(5, tr.passCount());
        PassKCalculator.PassKResult pk = PassKCalculator.from(tr, 5);
        assertEquals(1.0, pk.passAtK(), 1e-9);

        TrialRunner.TrialExecutor alwaysFail = s -> failResult(s.id);
        TrialResult trFail = new TrialRunner().run(spec, 5, alwaysFail);
        assertEquals(0, trFail.passCount());
        assertEquals(0.0, PassKCalculator.from(trFail, 5).passPowerK(), 1e-9);
    }

    // ==================== ② Grader 瀑布流 ====================

    @Test
    void ruleGraderPartialCredit() {
        AgentTestSpec spec = spec("P1", "order");
        spec.expectedTools = List.of("a", "b");
        spec.expectedKeywords = List.of("x");
        // 工具实际只命中 a（预期 a,b）→ 部分命中
        AgentEvaluationResult r = new AgentEvaluationResult.Builder("P1")
                .agentName("order").input("q").expectedIntent("i")
                .expectedTools(List.of("a", "b")).expectedKeywords(List.of("x"))
                .actualResponse("x").actualIntent("i")
                .actualToolsCalled(List.of("a")).build();
        GraderResult gr = new RuleGrader().grade(r, spec);
        assertTrue(gr.partial(), "工具部分命中应标记 partial");
    }

    @Test
    void graderWaterfallOfflineRuleOnly() {
        AgentTestSpec spec = spec("W1", "order");
        AgentEvaluationResult r = passResult("W1", "order", "i", List.of("t"));
        GraderWaterfall wf = new GraderWaterfall(List.of(new RuleGrader()));
        GraderResult gr = wf.grade(r, spec);
        assertEquals("RULE", gr.graderType());
        assertTrue(gr.score() > 0.6);
    }

    @Test
    void llmGraderParsesScore() {
        ChatModel cm = mock(ChatModel.class);
        stubModel(cm, "{\"score\":0.9,\"rationale\":\"回复准确\",\"confidence\":0.8}");
        AgentTestSpec spec = spec("L1", "order");
        AgentEvaluationResult r = passResult("L1", "order", "i", List.of("t"));
        GraderResult gr = new LlmGrader(cm).grade(r, spec);
        assertEquals(0.9, gr.score(), 1e-9);
        assertEquals("LLM", gr.graderType());
        assertFalse(gr.requiresHumanReview());
    }

    @Test
    void llmGraderLengthBiasGuardrail() {
        ChatModel cm = mock(ChatModel.class);
        stubModel(cm, "{\"score\":0.9,\"rationale\":\"ok\",\"confidence\":0.8}");
        AgentTestSpec spec = spec("L2", "g");
        // 过短回复(<10字)且高分 → 长度偏差封顶 0.6
        AgentEvaluationResult r = new AgentEvaluationResult.Builder("L2")
                .agentName("g").input("q").expectedIntent("i")
                .expectedTools(List.of("t")).expectedKeywords(List.of("k"))
                .actualResponse("ok").actualIntent("i").actualToolsCalled(List.of("t")).build();
        GraderResult gr = new LlmGrader(cm).grade(r, spec);
        assertEquals(0.6, gr.score(), 1e-9);
    }

    // ==================== ③ 人工路由 ====================

    @Test
    void humanReviewRouterRoutesBySample() {
        InMemoryHumanReviewStore store = new InMemoryHumanReviewStore();
        HumanReviewRouter router = new HumanReviewRouter(store, 1.0); // 100% 抽检
        AgentTestSpec spec = spec("H1", "order");
        AgentEvaluationResult r = passResult("H1", "order", "i", List.of("t"));
        assertTrue(router.route(r, null, spec));
        assertEquals(1, store.pending().size());
    }

    @Test
    void humanReviewRouterSkipsWhenConfident() {
        InMemoryHumanReviewStore store = new InMemoryHumanReviewStore();
        HumanReviewRouter router = new HumanReviewRouter(store, 0.0); // 不抽检
        AgentTestSpec spec = spec("H2", "order");
        AgentEvaluationResult r = passResult("H2", "order", "i", List.of("t"));
        assertFalse(router.route(r, null, spec));
        assertEquals(0, store.pending().size());
    }

    // ==================== ④ 根因分析 ====================

    @Test
    void rootCauseAnalyzerClustersAndDiagnoses() {
        AgentEvaluationResult a = new AgentEvaluationResult.Builder("A")
                .agentName("order").input("q").expectedIntent("x").expectedTools(List.of("t"))
                .expectedKeywords(List.of("k"))
                .actualResponse("no").actualIntent("y").actualToolsCalled(List.of("t")).build(); // 意图错
        AgentEvaluationResult b = new AgentEvaluationResult.Builder("B")
                .agentName("order").input("q").expectedIntent("x").expectedTools(List.of("t"))
                .expectedKeywords(List.of("k"))
                .actualResponse("no").actualIntent("x").actualToolsCalled(List.of("z")).build(); // 工具错
        RootCauseAnalysis rc = new RootCauseAnalyzer().analyze(List.of(a, b));
        assertEquals(2, rc.evidenceCount());
        assertEquals(2, rc.clusters().size());
        assertFalse(new RootCauseAnalyzer().generateFixTickets(rc).isEmpty());
    }

    // ==================== 集成：EvalPipeline ====================

    @Test
    void evalPipelineOfflineRunsFullChain() {
        AgentTestSpec spec = spec("E1", "order");
        TrialRunner.TrialExecutor ex = s -> passResult(s.id, s.agentName, s.expectedIntent, s.expectedTools);
        EvalPipeline p = new EvalPipeline.Builder().trials(3).k(3).executor(ex).build();
        List<EvalPipeline.CaseVerdict> vs = p.evaluateAll(List.of(spec));
        assertEquals(1, vs.size());
        assertNotNull(p.lastRootCause());
    }

    // ==================== 辅助 ====================

    private static AgentTestSpec spec(String id, String agent) {
        AgentTestSpec s = new AgentTestSpec();
        s.id = id;
        s.agentName = agent;
        s.input = "q";
        s.expectedIntent = "i";
        s.expectedTools = List.of("t");
        s.expectedKeywords = List.of("k");
        return s;
    }

    private static AgentEvaluationResult passResult(String id, String agent, String intent, List<String> tools) {
        return new AgentEvaluationResult.Builder(id)
                .agentName(agent).input("q").expectedIntent(intent)
                .expectedTools(tools).expectedKeywords(List.of("k"))
                .actualResponse("订单k的详细物流状态如下已发货").actualIntent(intent)
                .actualToolsCalled(tools).build();
    }

    private static AgentEvaluationResult failResult(String id) {
        return new AgentEvaluationResult.Builder(id)
                .agentName("order").input("q").expectedIntent("i")
                .expectedTools(List.of("t")).expectedKeywords(List.of("k"))
                .actualResponse("[待执行]").hasError(true).build();
    }

    private static void stubModel(ChatModel cm, String json) {
        AssistantMessage am = new AssistantMessage(json, Map.of(), List.of());
        ChatResponse cr = new ChatResponse(List.of(new Generation(am)));
        when(cm.call(any(Prompt.class))).thenReturn(cr);
    }
}
