package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteExecutionPreplannedGraphTest {

    @Test
    void buildsMultiIntentGraphWithoutASecondPlannerCall() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("COMPLEX");
        analysis.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY", "description", "查询当前热门商品列表"),
                Map.of("intent", "ORDER_INFO", "description", "说明下单需要提供的信息")));
        analysis.setActionConstraints(List.of("不创建订单", "不支付"));

        String question = "[用户原始请求]\n查询热门商品并说明下单信息\n"
                + "[用户操作约束]\n- 不创建订单\n- 不支付";
        IntentGraph graph = RouteExecutionService.buildGraphFromAnalysis(question, analysis);

        assertNotNull(graph);
        assertEquals(2, graph.getNodeCount());
        assertEquals(2, graph.getRootNodes().size());
        assertEquals(List.of("product", RouteExecutionService.BUILTIN_ORDER_PREPARATION_AGENT),
                graph.getAllNodes().stream()
                .map(IntentGraph.IntentNode::getTargetAgent).toList());
        assertTrue(graph.getAllNodes().stream().toList().get(1).getDescription()
                .contains("收货人姓名、联系电话、收货地址"));
        IntentGraph.IntentNode productNode = graph.getAllNodes().stream().toList().get(0);
        assertEquals("查询当前热门商品列表", productNode.getDescription());
        assertFalse(productNode.getDescription().contains("不创建订单"));
        assertFalse(productNode.getDescription().contains("[完整用户请求与全局约束]"));
        IntentGraph.IntentNode preparationNode = graph.getAllNodes().stream().toList().get(1);
        assertTrue(preparationNode.getDescription().contains("不得替用户补造缺失参数"));
    }

    @Test
    void keepsExplicitDependencyBetweenSubIntents() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY", "description", "查询商品"),
                Map.of("intent", "CREATE_ORDER", "description", "创建订单", "depends_on", "PRODUCT_QUERY")));

        IntentGraph graph = RouteExecutionService.buildGraphFromAnalysis("先查商品再下单", analysis);

        assertNotNull(graph);
        assertEquals(1, graph.getRootNodes().size());
        assertEquals(List.of("t1"), graph.getAllNodes().stream().toList().get(1).getDependsOn());
    }

    @Test
    void preservesNonAdjacentDependencyByStableNodeId() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("id", "catalog", "intent", "PRODUCT_QUERY", "description", "查询商品"),
                Map.of("id", "weather", "intent", "WEATHER_QUERY", "description", "查询天气"),
                Map.of("id", "order", "intent", "ORDER_INFO", "description", "说明订单信息",
                        "depends_on", "catalog")));

        IntentGraph graph = RouteExecutionService.buildGraphFromAnalysis(
                "查询商品和天气，再根据商品说明订单信息", analysis);

        assertNotNull(graph);
        IntentGraph.IntentNode orderNode = graph.getAllNodes().stream()
                .filter(node -> "order".equals(node.getId()))
                .findFirst().orElseThrow();
        assertEquals(List.of("catalog"), orderNode.getDependsOn());
        assertFalse(orderNode.getDependsOn().contains("weather"));
    }

    @Test
    void createsIdempotencyKeyForWriteNode() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY", "description", "查询商品"),
                Map.of("intent", "CREATE_ORDER", "description", "使用完整资料创建订单",
                        "depends_on", "PRODUCT_QUERY")));

        var plan = RouteExecutionService.buildExecutionPlan(
                "先查询再创建订单", analysis, "req-123");

        assertNotNull(plan);
        var writeNode = plan.nodes().get(1);
        assertEquals(com.example.smartassistant.router.model.ExecutionPlan.AccessMode.WRITE,
                writeNode.accessMode());
        assertEquals("req-123:" + writeNode.nodeId(), writeNode.idempotencyKey());

        IntentGraph.IntentNode graphWriteNode = plan.toIntentGraph().getAllNodes().stream()
                .filter(node -> writeNode.nodeId().equals(node.getId()))
                .findFirst().orElseThrow();
        assertEquals(writeNode.operation(), graphWriteNode.getOperation());
        assertEquals(writeNode.input(), graphWriteNode.getInput());
        assertEquals(plan.globalConstraints(), graphWriteNode.getConstraints());
        assertEquals(writeNode.idempotencyKey(), graphWriteNode.getIdempotencyKey());
    }

    @Test
    void incompleteCreateOrderBecomesReadOnlyPreparationExplanation() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setNeedsClarification(true);
        analysis.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY", "description", "查询热门商品"),
                Map.of("intent", "CREATE_ORDER", "description", "选择商品后下单")));

        IntentGraph graph = RouteExecutionService.buildGraphFromAnalysis(
                "[执行边界] 不得创建订单，先查询再追问", analysis);

        assertNotNull(graph);
        IntentGraph.IntentNode second = graph.getAllNodes().stream().toList().get(1);
        assertEquals(RouteExecutionService.BUILTIN_ORDER_PREPARATION_AGENT, second.getTargetAgent());
        assertTrue(second.getDescription().contains("只说明，不执行"));
        assertTrue(second.getDescription().contains("具体商品及金额"));
    }

    @Test
    void deterministicallyMergesProductResultAndBuiltInChecklist() {
        var product = new com.example.smartassistant.router.model.SubTaskResult(
                "t1", "查询热门商品", "product", "热门商品：测试笔记本 ¥3798", true,
                List.of(), Map.of());
        var checklist = new com.example.smartassistant.router.model.SubTaskResult(
                "t2", "说明下单资料", RouteExecutionService.BUILTIN_ORDER_PREPARATION_AGENT,
                RouteExecutionService.builtInOrderPreparationReply(), true, List.of(), Map.of());

        String merged = RouteExecutionService.mergeOrderPreparationResults(List.of(product, checklist));

        assertTrue(merged.contains("热门商品：测试笔记本 ¥3798"));
        assertTrue(merged.contains("收货人姓名"));
        assertTrue(merged.contains("联系电话"));
        assertTrue(merged.contains("未创建订单"));
    }

    @Test
    void partialFailureProducesOrchestratorWarningInsteadOfDiscardingSuccess() {
        var success = new com.example.smartassistant.router.model.SubTaskResult(
                "t1", "查询天气", "general", "北京晴", true);
        success.setDomainQuality(DomainQualityResult.pass(0.9, "WEATHER_OK"));
        var failed = new com.example.smartassistant.router.model.SubTaskResult(
                "t2", "查询商品", "product", "", false,
                com.example.smartassistant.router.model.SubTaskResult.ErrorType.RETRYABLE_FAILED);

        DomainQualityResult quality = RouteExecutionService.aggregateDomainQuality(
                List.of(success, failed));

        assertTrue(quality.isWarn());
        assertTrue(quality.getReasonCodes().contains("PARTIAL_AGENT_FAILURE"));
    }
}
