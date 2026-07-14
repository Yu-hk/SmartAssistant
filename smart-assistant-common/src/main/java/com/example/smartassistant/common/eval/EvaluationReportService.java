/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.rag.compliance.ComplianceGoldenSuiteEvaluator;
import com.example.smartassistant.common.rag.compliance.ComplianceTestCase;
import com.example.smartassistant.common.rag.eval.RAGEvaluationResult;
import com.example.smartassistant.common.rag.eval.RAGEvaluator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * 统一的评估报告服务 — 整合 RAG 评测 + Agent 评测，支持从 JSON 配置加载黄金测试集。
 * <p>
 * 提供了三个核心能力：
 * <ol>
 *   <li><b>黄金测试集管理</b>：从 JSON 文件加载测试用例</li>
 *   <li><b>执行评测</b>：运行 RAG 和 Agent 的离线评测</li>
 *   <li><b>历史追踪</b>：保存每次评测的结果快照，支持趋势分析</li>
 * </ol>
 * </p>
 *
 * <p>配置示例（resources/eval-test-suite.json）：</p>
 * <pre>{@code
 * {
 *   "ragTests": [
 *     {
 *       "id": "RAG-001",
 *       "query": "产品价格",
 *       "queryForEval": "产品价格",
 *       "relevantIds": ["doc1"],
 *       "retrievedIds": ["doc1", "doc2", "doc3"],
 *       "topK": 5,
 *       "context": "商品描述：售价199元...",
 *       "answer": "该商品售价为199元[CID:doc1]"
 *     }
 *   ],
 *   "agentTests": [
 *     {
 *       "id": "AGT-001",
 *       "caseName": "查询订单状态",
 *       "agentName": "order",
 *       "input": "我的订单到哪了？",
 *       "expectedIntent": "order_query",
 *       "expectedTools": ["queryOrder"],
 *       "expectedKeywords": ["订单", "配送"]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
public class EvaluationReportService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationReportService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 评测历史记录（按时间戳排序） */
    private final List<EvaluationRun> history = Collections.synchronizedList(new ArrayList<>());

    /** 黄金测试集 */
    private List<RAGTestSpec> ragTestCases = new ArrayList<>();
    private List<AgentTestSpec> agentTestCases = new ArrayList<>();
    private List<ComplianceTestCase> complianceTestCases = new ArrayList<>();

    private final RAGEvaluator ragEvaluator = new RAGEvaluator();

    /**
     * 从 classpath JSON 加载黄金测试集。
     *
     * @param classpathResource JSON 资源路径（如 "/eval-test-suite.json"）
     */
    public void loadTestSuite(String classpathResource) {
        try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
            if (is == null) {
                log.warn("[EvalReportSvc] 测试集资源不存在: {}", classpathResource);
                return;
            }
            Map<String, Object> suite = MAPPER.readValue(is, new TypeReference<>() {});

            // 解析 RAG 测试
            Object ragRaw = suite.get("ragTests");
            if (ragRaw instanceof List) {
                ragTestCases = MAPPER.convertValue(ragRaw, new TypeReference<>() {});
                log.info("[EvalReportSvc] 加载 RAG 测试用例: {} 条", ragTestCases.size());
            }

            // 解析 Agent 测试
            Object agentRaw = suite.get("agentTests");
            if (agentRaw instanceof List) {
                agentTestCases = MAPPER.convertValue(agentRaw, new TypeReference<>() {});
                log.info("[EvalReportSvc] 加载 Agent 测试用例: {} 条", agentTestCases.size());
            }

            // 解析 Compliance 测试（REQ-8）：复用 ComplianceGoldenSuiteEvaluator 的解析，单一数据源
            complianceTestCases = ComplianceGoldenSuiteEvaluator.loadCases(classpathResource);

        } catch (Exception e) {
            log.error("[EvalReportSvc] 加载测试集失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行 RAG 评测（详细版）。
     *
     * @return 每条用例的 {@link RAGEvaluationResult} 列表（供门禁/指标聚合使用）
     */
    public List<RAGEvaluationResult> runRAGEvaluationDetailed() {
        if (ragTestCases.isEmpty()) {
            return List.of();
        }

        List<RAGEvaluator.EvalRequest> requests = ragTestCases.stream()
                .map(t -> new RAGEvaluator.EvalRequest(
                        t.queryForEval != null ? t.queryForEval : t.query,
                        new HashSet<>(t.relevantIds),
                        t.retrievedIds,
                        t.context,
                        t.answer,
                        t.topK > 0 ? t.topK : 5))
                .toList();

        return ragEvaluator.evaluateBatch(requests);
    }

    /**
     * 执行 RAG 评测（摘要版，向后兼容）。
     *
     * @return 评测报告（typed Map）
     */
    public Map<String, Object> runRAGEvaluation() {
        List<RAGEvaluationResult> results = runRAGEvaluationDetailed();
        if (results.isEmpty()) {
            return Map.of("status", "SKIPPED", "message", "无 RAG 测试用例");
        }

        String report = RAGEvaluationResult.generateBatchReport(results);

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("type", "RAG");
        resultMap.put("total", results.size());
        resultMap.put("passed", results.stream().filter(RAGEvaluationResult::passed).count());
        resultMap.put("failed", results.stream().filter(r -> !r.passed()).count());
        resultMap.put("report", report);

        history.add(new EvaluationRun("RAG", System.currentTimeMillis(), resultMap));
        return resultMap;
    }

    /**
     * 执行 Agent 评测（详细版）。
     *
     * @param actualResults 实际 Agent 执行结果（由系统运行时注入；null 时为用例生成"待执行"标记）
     * @return 每条用例的 {@link AgentEvaluationResult} 列表（供门禁/指标聚合使用）
     */
    public List<AgentEvaluationResult> runAgentEvaluationDetailed(List<AgentEvaluationResult> actualResults) {
        if (agentTestCases.isEmpty() && (actualResults == null || actualResults.isEmpty())) {
            return List.of();
        }

        List<AgentEvaluationResult> results = new ArrayList<>();

        // 使用实际结果（如果有）
        if (actualResults != null) {
            results.addAll(actualResults);
        }

        // 对没有实际结果的测试用例，生成"待执行"标记
        for (AgentTestSpec spec : agentTestCases) {
            boolean alreadyRun = results.stream()
                    .anyMatch(r -> spec.id.equals(r.getCaseId()));
            if (!alreadyRun) {
                results.add(new AgentEvaluationResult.Builder(spec.id)
                        .caseName(spec.caseName)
                        .agentName(spec.agentName)
                        .input(spec.input)
                        .expectedIntent(spec.expectedIntent)
                        .expectedTools(spec.expectedTools)
                        .expectedKeywords(spec.expectedKeywords)
                        .actualResponse("[待执行]")
                        .hasError(false)
                        .build());
            }
        }

        return results;
    }

    /**
     * 执行 Agent 评测（摘要版，向后兼容）。
     *
     * @param actualResults 实际 Agent 执行结果（由系统运行时注入）
     * @return 评测报告（typed Map）
     */
    public Map<String, Object> runAgentEvaluation(List<AgentEvaluationResult> actualResults) {
        List<AgentEvaluationResult> results = runAgentEvaluationDetailed(actualResults);
        if (results.isEmpty()) {
            return Map.of("status", "SKIPPED", "message", "无 Agent 测试用例");
        }

        String report = AgentEvaluationResult.generateBatchReport(results);

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("type", "Agent");
        resultMap.put("total", results.size());
        resultMap.put("passed", results.stream().filter(AgentEvaluationResult::passed).count());
        resultMap.put("failed", results.stream().filter(r -> !r.passed()).count());
        resultMap.put("report", report);

        history.add(new EvaluationRun("Agent", System.currentTimeMillis(), resultMap));
        return resultMap;
    }

    /**
     * 获取评测历史。
     */
    public List<EvaluationRun> getHistory() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * 获取已加载的 Agent 测试用例规格（供评测增强管线注入 {@code TrialExecutor} 后跑 Trial×pass^k）。
     */
    public List<AgentTestSpec> getAgentTestCases() {
        return List.copyOf(agentTestCases);
    }

    /**
     * 获取已加载的合规测试用例规格（REQ-8，complianceTests 段）。
     */
    public List<ComplianceTestCase> getComplianceTestCases() {
        return List.copyOf(complianceTestCases);
    }

    /**
     * 获取统计摘要。
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ragTestCases", ragTestCases.size());
        summary.put("agentTestCases", agentTestCases.size());
        summary.put("complianceTestCases", complianceTestCases.size());
        summary.put("totalRuns", history.size());

        long lastRun = history.isEmpty() ? 0 : history.get(history.size() - 1).timestamp;
        summary.put("lastRunAt", lastRun);
        summary.put("lastRunType", history.isEmpty() ? null : history.get(history.size() - 1).type);

        return summary;
    }

    // ==================== 数据模型 ====================

    /** 单次评测运行记录 */
    public record EvaluationRun(String type, long timestamp, Map<String, Object> result) {}

    /** RAG 测试用例规格 */
    public static class RAGTestSpec {
        public String id;
        public String query;
        public String queryForEval;
        public List<String> relevantIds = new ArrayList<>();
        public List<String> retrievedIds = new ArrayList<>();
        public int topK;
        public String context;
        public String answer;

        public RAGTestSpec() {}
    }

    /** Agent 测试用例规格 */
    public static class AgentTestSpec {
        public String id;
        public String caseName;
        public String agentName;
        public String input;
        public String expectedIntent;
        public List<String> expectedTools = new ArrayList<>();
        public List<String> expectedKeywords = new ArrayList<>();

        public AgentTestSpec() {}
    }
}
