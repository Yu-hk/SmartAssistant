package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteExecutionPreplannedGraphTest {

    @Test
    void carriesStructuredProductCategoryIntoDiscoveryNodeInput() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("PRODUCT");
        analysis.setEntities(Map.of("product_category", "平板电脑"));
        analysis.setSubIntents(List.of(Map.of(
                "id", "discover_products",
                "intent", "PRODUCT_DISCOVERY",
                "description", "查询热门平板电脑候选",
                "target_agent", "product",
                "operation", "DISCOVER_PRODUCTS")));

        var plan = RouteExecutionService.buildExecutionPlan(
                "我想买一部平板电脑，帮我推荐一款热门的", analysis, "req-tablet");

        assertNotNull(plan);
        assertEquals("平板电脑", plan.nodes().getFirst().input().get("product_category"));
    }

    @Test
    void buildsMultiIntentGraphWithoutASecondPlannerCall() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("COMPLEX");
        analysis.setSubIntents(List.of(
                Map.of("intent", "PRODUCT_QUERY", "description", "查询当前热门商品列表",
                        "target_agent", "product", "operation", "QUERY_PRODUCT"),
                Map.of("intent", "ORDER_INFO", "description", "说明下单需要提供的信息",
                        "target_agent", "order", "operation", "EXPLAIN_ORDER_REQUIREMENTS")));
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
        assertTrue(productNode.getDescription().startsWith("查询当前热门商品列表"));
        assertEquals("查询当前热门商品列表", productNode.getDescription());
        IntentGraph.IntentNode preparationNode = graph.getAllNodes().stream().toList().get(1);
        assertTrue(preparationNode.getDescription().contains("不得替用户补造缺失参数"));
    }

    @Test
    void keepsExplicitDependencyBetweenSubIntents() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("id", "product", "intent", "PRODUCT_QUERY", "description", "查询商品",
                        "target_agent", "product", "operation", "QUERY_PRODUCT"),
                Map.of("id", "order", "intent", "CREATE_ORDER", "description", "创建订单",
                        "target_agent", "order", "operation", "CREATE_ORDER",
                        "access_mode", "WRITE", "depends_on", List.of("product"))));

        IntentGraph graph = RouteExecutionService.buildGraphFromAnalysis("先查商品再下单", analysis);

        assertNotNull(graph);
        assertEquals(1, graph.getRootNodes().size());
        assertEquals(List.of("product"), graph.getAllNodes().stream().toList().get(1).getDependsOn());
    }

    @Test
    void transportsPlannerNodeContractsIntoTheRuntimeGraph() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.ofEntries(
                        Map.entry("id", "discover"),
                        Map.entry("intent", "PRODUCT_DISCOVERY"),
                        Map.entry("description", "查询候选商品"),
                        Map.entry("target_agent", "product"),
                        Map.entry("operation", "DISCOVER_PRODUCTS")),
                Map.ofEntries(
                        Map.entry("id", "analysis"),
                        Map.entry("intent", "PRODUCT_ANALYSIS"),
                        Map.entry("description", "分析候选商品"),
                        Map.entry("target_agent", "product"),
                        Map.entry("operation", "ANALYZE_PRODUCT_DATA"),
                        Map.entry("depends_on", List.of("discover")),
                        Map.entry("merge_policy", "STRUCTURED"),
                        Map.entry("output_schema", "product-analysis.v1")),
                Map.ofEntries(
                        Map.entry("id", "recommend"),
                        Map.entry("intent", "PRODUCT_RECOMMENDATION"),
                        Map.entry("description", "核实分析并推荐商品"),
                        Map.entry("target_agent", "product"),
                        Map.entry("operation", "RECOMMEND_PRODUCT"),
                        Map.entry("depends_on", List.of("discover", "analysis")),
                        Map.entry("required", false),
                        Map.entry("merge_policy", "REPLACE"),
                        Map.entry("output_schema", "recommendation.v1"),
                        Map.entry("input_bindings", Map.of(
                                "analysisResult", "$.nodes.analysis.data.analysis")))));

        var plan = RouteExecutionService.buildExecutionPlan(
                "分析候选并推荐", analysis, "req-contract");

        assertNotNull(plan);
        var recommendation = plan.nodes().get(2);
        assertFalse(recommendation.required());
        assertEquals(com.example.smartassistant.router.model.ExecutionPlan.MergePolicy.REPLACE,
                recommendation.mergePolicy());
        assertEquals("recommendation.v1", recommendation.outputSchema());
        assertEquals(Map.of("analysisResult", "$.nodes.analysis.data.analysis"),
                recommendation.inputBindings());

        IntentGraph.IntentNode runtimeNode = plan.toIntentGraph().getAllNodes().stream()
                .filter(node -> "recommend".equals(node.getId()))
                .findFirst().orElseThrow();
        assertFalse(runtimeNode.isRequired());
        assertEquals(recommendation.mergePolicy(), runtimeNode.getMergePolicy());
        assertEquals(recommendation.outputSchema(), runtimeNode.getOutputSchema());
        assertEquals(recommendation.inputBindings(), runtimeNode.getInputBindings());
        assertTrue(ExecutionPlanValidator.validate(plan).valid());
    }

    @Test
    void preservesNonAdjacentDependencyByStableNodeId() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("id", "catalog", "intent", "PRODUCT_QUERY", "description", "查询商品",
                        "target_agent", "product", "operation", "QUERY_PRODUCT"),
                Map.of("id", "weather", "intent", "WEATHER_QUERY", "description", "查询天气",
                        "target_agent", "general", "operation", "ANSWER"),
                Map.of("id", "order", "intent", "ORDER_INFO", "description", "说明订单信息",
                        "target_agent", "order", "operation", "QUERY_ORDER",
                        "depends_on", List.of("catalog"))));

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
                Map.of("intent", "PRODUCT_QUERY", "description", "查询商品",
                        "target_agent", "product", "operation", "QUERY_PRODUCT"),
                Map.of("intent", "CREATE_ORDER", "description", "使用完整资料创建订单",
                        "target_agent", "order", "operation", "CREATE_ORDER",
                        "access_mode", "WRITE", "depends_on", "PRODUCT_QUERY")));

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
                Map.of("intent", "PRODUCT_QUERY", "description", "查询热门商品",
                        "target_agent", "product", "operation", "QUERY_PRODUCT"),
                Map.of("intent", "CREATE_ORDER", "description", "选择商品后下单",
                        "target_agent", "order", "operation", "CREATE_ORDER", "access_mode", "WRITE")));

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
    void compilesSingleIntentDirectlyWithoutCallingASecondPlanner() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(Map.of(
                "id", "weather", "intent", "WEATHER_QUERY", "description", "查询北京天气",
                "target_agent", "general", "operation", "ANSWER")));

        IntentGraph graph = RouteExecutionService.buildGraphFromAnalysis("北京天气如何", analysis);

        assertNotNull(graph);
        assertEquals(1, graph.getNodeCount());
        assertEquals("general", graph.getAllNodes().iterator().next().getTargetAgent());
    }

    @Test
    void preservesReadOnlyOrderLifecycleOperation() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(Map.of(
                "id", "order_guidance",
                "intent", "ORDER_GUIDANCE",
                "description", "说明下单后如何查询订单、取消订单和申请售后",
                "target_agent", "order",
                "operation", "EXPLAIN_ORDER_LIFECYCLE",
                "access_mode", "READ")));

        var plan = RouteExecutionService.buildExecutionPlan(
                "只说明流程，不执行任何订单操作", analysis, "req-guidance");

        assertNotNull(plan);
        assertEquals("EXPLAIN_ORDER_LIFECYCLE", plan.nodes().getFirst().operation());
        assertEquals(com.example.smartassistant.router.model.ExecutionPlan.AccessMode.READ,
                plan.nodes().getFirst().accessMode());
        assertFalse(plan.nodes().getFirst().approvalRequired());
        assertNull(plan.nodes().getFirst().idempotencyKey());
    }

    @Test
    void dropsRootSelfBindingWhenOriginalRequestAlreadyProvidesTrustedOrderId() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("ORDER");
        analysis.setEntities(Map.of("order_id", "BULK-0002"));
        analysis.setSubIntents(List.of(
                Map.of("id", "query_order_status", "intent", "QUERY_ORDER",
                        "description", "查询订单 BULK-0002 当前状态",
                        "target_agent", "order", "operation", "QUERY_ORDER",
                        "input_bindings", Map.of("order_id",
                                "$.nodes.query_order_status.data.order_id")),
                Map.of("id", "query_logistics", "intent", "TRACK_LOGISTICS",
                        "description", "查询订单 BULK-0002 物流",
                        "target_agent", "order", "operation", "TRACK_LOGISTICS",
                        "depends_on", List.of("query_order_status"),
                        "input_bindings", Map.of("order_id",
                                "$.nodes.query_order_status.data.order_id"))));

        IntentGraph graph = RouteExecutionService.buildGraphFromAnalysis(
                "只读查询订单 BULK-0002 的状态和物流", analysis);

        assertNotNull(graph);
        List<IntentGraph.IntentNode> nodes = graph.getAllNodes().stream().toList();
        assertTrue(nodes.getFirst().getInputBindings().isEmpty());
        assertEquals(Map.of("order_id", "$.nodes.query_order_status.data.order_id"),
                nodes.get(1).getInputBindings());
        assertEquals("BULK-0002", nodes.getFirst().getInput().get("order_id"));
    }

    @Test
    void stillRejectsInvalidBindingWithoutTrustedExplicitInput() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setIntentCategory("ORDER");
        analysis.setSubIntents(List.of(Map.of(
                "id", "query_order_status", "intent", "QUERY_ORDER",
                "description", "查询订单状态", "target_agent", "order",
                "operation", "QUERY_ORDER", "input_bindings", Map.of(
                        "order_id", "$.nodes.query_order_status.data.order_id"))));

        assertNull(RouteExecutionService.buildGraphFromAnalysis("查询订单状态", analysis));
    }

    @Test
    void compilesFulfillmentLifecycleWithCorrectReadWriteAndApprovalBoundaries() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("id", "stock", "intent", "STOCK_QUERY", "description", "查询商品库存",
                        "target_agent", "product", "operation", "QUERY_PRODUCT"),
                Map.of("id", "create", "intent", "CREATE_ORDER", "description", "创建订单",
                        "target_agent", "order", "operation", "CREATE_ORDER",
                        "depends_on", List.of("stock")),
                Map.of("id", "pay", "intent", "PAY_ORDER", "description", "支付订单",
                        "target_agent", "order", "operation", "PAY_ORDER",
                        "depends_on", List.of("create")),
                Map.of("id", "ship", "intent", "SHIP_ORDER", "description", "订单发货并生成物流",
                        "target_agent", "order", "operation", "SHIP_ORDER",
                        "depends_on", List.of("pay")),
                Map.of("id", "logistics", "intent", "TRACK_LOGISTICS", "description", "查询物流轨迹",
                        "target_agent", "order", "operation", "TRACK_LOGISTICS",
                        "depends_on", List.of("ship")),
                Map.of("id", "complete", "intent", "CONFIRM_DELIVERY", "description", "确认收货完成订单",
                        "target_agent", "order", "operation", "CONFIRM_DELIVERY",
                        "depends_on", List.of("logistics"))));

        var plan = RouteExecutionService.buildExecutionPlan(
                "查询库存后下单、支付、发货、查物流并完成订单", analysis, "req-lifecycle");

        assertNotNull(plan);
        assertEquals(List.of(
                        com.example.smartassistant.router.model.ExecutionPlan.AccessMode.READ,
                        com.example.smartassistant.router.model.ExecutionPlan.AccessMode.WRITE,
                        com.example.smartassistant.router.model.ExecutionPlan.AccessMode.WRITE,
                        com.example.smartassistant.router.model.ExecutionPlan.AccessMode.WRITE,
                        com.example.smartassistant.router.model.ExecutionPlan.AccessMode.READ,
                        com.example.smartassistant.router.model.ExecutionPlan.AccessMode.WRITE),
                plan.nodes().stream().map(node -> node.accessMode()).toList());
        assertFalse(plan.nodes().get(1).approvalRequired());
        assertTrue(plan.nodes().get(2).approvalRequired());
        assertTrue(plan.nodes().get(3).approvalRequired());
        assertFalse(plan.nodes().get(4).approvalRequired());
        assertTrue(plan.nodes().get(5).approvalRequired());
        assertEquals("req-lifecycle:ship", plan.nodes().get(3).idempotencyKey());
        assertEquals("req-lifecycle:complete", plan.nodes().get(5).idempotencyKey());
    }

    @Test
    void riskFlagsNeverTurnReadOnlyNodesIntoApprovalCheckpoints() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setRiskFlags(List.of("涉及订单信息，需要二次确认"));
        analysis.setSubIntents(List.of(Map.of(
                "id", "query", "intent", "QUERY_ORDER", "description", "查询订单 ORD-1",
                "target_agent", "order", "operation", "QUERY_ORDER",
                "access_mode", "READ", "human_approval_required", true)));

        var plan = RouteExecutionService.buildExecutionPlan(
                "只查询订单，不修改", analysis, "req-read-only");

        assertNotNull(plan);
        assertEquals(com.example.smartassistant.router.model.ExecutionPlan.AccessMode.READ,
                plan.nodes().getFirst().accessMode());
        assertFalse(plan.nodes().getFirst().approvalRequired());
    }

    @Test
    void preservesExplicitReadOnlyOrderListOperation() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(Map.of(
                "id", "query_order_list", "intent", "QUERY_ORDER_LIST",
                "description", "查询当前用户的订单列表",
                "target_agent", "order", "operation", "QUERY_ORDER_LIST",
                "access_mode", "READ", "human_approval_required", false)));

        var plan = RouteExecutionService.buildExecutionPlan(
                "查询我的订单列表", analysis, "req-order-list");

        assertNotNull(plan);
        assertEquals("QUERY_ORDER_LIST", plan.nodes().getFirst().operation());
        assertEquals(com.example.smartassistant.router.model.ExecutionPlan.AccessMode.READ,
                plan.nodes().getFirst().accessMode());
        assertFalse(plan.nodes().getFirst().approvalRequired());
    }

    @Test
    void compilesRecommendationToOrderAsEvidenceBackedSerialDag() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("id", "discover_products", "intent", "PRODUCT_DISCOVERY",
                        "description", "查询真实候选商品、价格和库存",
                        "target_agent", "product", "operation", "DISCOVER_PRODUCTS"),
                Map.of("id", "analyze_product_data", "intent", "PRODUCT_ANALYSIS",
                        "description", "分析候选商品与用户约束的匹配度",
                        "target_agent", "product", "operation", "ANALYZE_PRODUCT_DATA",
                        "depends_on", List.of("discover_products")),
                Map.of("id", "recommend_product", "intent", "PRODUCT_RECOMMENDATION",
                        "description", "基于分析选出最匹配商品",
                        "target_agent", "product", "operation", "RECOMMEND_PRODUCT",
                        "depends_on", List.of("discover_products", "analyze_product_data")),
                Map.of("id", "create_order", "intent", "CREATE_ORDER",
                        "description", "用户确认并补齐资料后创建订单",
                        "target_agent", "order", "operation", "CREATE_ORDER",
                        "access_mode", "WRITE", "depends_on", List.of("recommend_product"))));

        var plan = RouteExecutionService.buildExecutionPlan(
                "分析并推荐最合适商品，确认后下单", analysis, "req-recommend-order");

        assertNotNull(plan);
        assertEquals(List.of("DISCOVER_PRODUCTS", "ANALYZE_PRODUCT_DATA",
                        "RECOMMEND_PRODUCT", "CREATE_ORDER"),
                plan.nodes().stream().map(node -> node.operation()).toList());
        assertEquals(List.of("discover_products"), plan.nodes().get(1).dependsOn());
        assertEquals(List.of("discover_products", "analyze_product_data"),
                plan.nodes().get(2).dependsOn());
        assertEquals(com.example.smartassistant.router.model.ExecutionPlan.MergePolicy.REPLACE,
                plan.nodes().get(2).mergePolicy());
        assertEquals(List.of("recommend_product"), plan.nodes().get(3).dependsOn());
        assertEquals(com.example.smartassistant.router.model.ExecutionPlan.AccessMode.WRITE,
                plan.nodes().get(3).accessMode());
        assertTrue(plan.nodes().get(3).approvalRequired());
        assertTrue(ExecutionPlanValidator.validate(plan).valid());
    }

    @Test
    void rejectsRecommendationThatSkipsDataAnalysis() {
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setSubIntents(List.of(
                Map.of("id", "discover_products", "intent", "PRODUCT_DISCOVERY",
                        "description", "查询候选商品", "target_agent", "product",
                        "operation", "DISCOVER_PRODUCTS"),
                Map.of("id", "recommend_product", "intent", "PRODUCT_RECOMMENDATION",
                        "description", "直接推荐商品", "target_agent", "product",
                        "operation", "RECOMMEND_PRODUCT",
                        "depends_on", List.of("discover_products"))));

        var plan = RouteExecutionService.buildExecutionPlan(
                "查询后直接推荐", analysis, "req-invalid-recommendation");

        assertNotNull(plan);
        var validation = ExecutionPlanValidator.validate(plan);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(error -> error.contains("must depend on product analysis")));
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

    @Test
    void multiNodeSingleDomainResultBelongsToBusinessAgent() {
        var status = new SubTaskResult(
                "t1", "查询订单状态", "order", "已发货", true);
        var logistics = new SubTaskResult(
                "t2", "查询物流", "order", "顺丰运输中", true);

        var attribution = RouteExecutionService.determineResultAttribution(List.of(status, logistics));

        assertEquals("order", attribution.agentName());
        assertEquals(RoutingResult.ExecutionMode.SINGLE_AGENT, attribution.executionMode());
        assertEquals(List.of("order"), attribution.participatingAgents());
        assertEquals(RoutingResult.WorkflowStatus.COMPLETED, attribution.workflowStatus());
    }

    @Test
    void crossDomainResultUsesMultiAgentMetadataWithoutSyntheticAgent() {
        var product = new SubTaskResult(
                "t1", "查询商品", "product", "有库存", true);
        var order = new SubTaskResult(
                "t2", "查询订单", "order", "已发货", true);

        var attribution = RouteExecutionService.determineResultAttribution(List.of(product, order));

        assertNull(attribution.agentName());
        assertEquals(RoutingResult.ExecutionMode.MULTI_AGENT, attribution.executionMode());
        assertEquals(List.of("product", "order"), attribution.participatingAgents());
        assertEquals(RoutingResult.WorkflowStatus.COMPLETED, attribution.workflowStatus());
    }

    @Test
    void canonicalizesDiscoveredAgentAliasesInPublicAttribution() {
        var product = new SubTaskResult(
                "t1", "查询商品", "product_agent", "有库存", true);
        var order = new SubTaskResult(
                "t2", "查询订单", "order-service", "已发货", true);

        var attribution = RouteExecutionService.determineResultAttribution(List.of(product, order));

        assertNull(attribution.agentName());
        assertEquals(RoutingResult.ExecutionMode.MULTI_AGENT, attribution.executionMode());
        assertEquals(List.of("product", "order"), attribution.participatingAgents());
        assertEquals(RoutingResult.WorkflowStatus.COMPLETED, attribution.workflowStatus());
    }

    @Test
    void partialCrossDomainFailureUsesDegradedWorkflowStatus() {
        var product = new SubTaskResult(
                "t1", "查询商品", "product", "有库存", true);
        var order = new SubTaskResult(
                "t2", "查询订单", "order", "缺少订单号", false,
                SubTaskResult.ErrorType.FATAL_FAILED);

        var attribution = RouteExecutionService.determineResultAttribution(List.of(product, order));

        assertNull(attribution.agentName());
        assertEquals(RoutingResult.ExecutionMode.MULTI_AGENT, attribution.executionMode());
        assertEquals(List.of("product", "order"), attribution.participatingAgents());
        assertEquals(RoutingResult.WorkflowStatus.DEGRADED, attribution.workflowStatus());
    }

    @Test
    void allFailedResultsUseFailedWorkflowStatus() {
        var product = new SubTaskResult(
                "t1", "查询商品", "product", "服务不可用", false,
                SubTaskResult.ErrorType.RETRYABLE_FAILED);
        var order = new SubTaskResult(
                "t2", "查询订单", "order", "参数非法", false,
                SubTaskResult.ErrorType.FATAL_FAILED);

        var attribution = RouteExecutionService.determineResultAttribution(List.of(product, order));

        assertNull(attribution.agentName());
        assertEquals(RoutingResult.ExecutionMode.MULTI_AGENT, attribution.executionMode());
        assertEquals(List.of("product", "order"), attribution.participatingAgents());
        assertEquals(RoutingResult.WorkflowStatus.FAILED, attribution.workflowStatus());
    }

    @Test
    void approvalResultIsWorkflowMetadataAndNotAnAgent() {
        var approval = new SubTaskResult("approve", "确认支付", null, "请确认", true);
        approval.setSystemNodeType(SubTaskResult.SystemNodeType.APPROVAL);

        var attribution = RouteExecutionService.determineResultAttribution(List.of(approval));

        assertNull(attribution.agentName());
        assertEquals(RoutingResult.ExecutionMode.BUILTIN, attribution.executionMode());
        assertEquals(List.of(), attribution.participatingAgents());
        assertEquals(RoutingResult.WorkflowStatus.AWAITING_APPROVAL, attribution.workflowStatus());
    }

    @Test
    void fallbackPlanMergesSuccessfulResultsWithoutAnotherModelCall() {
        var product = new com.example.smartassistant.router.model.SubTaskResult(
                "t1", "查询热门商品", "product_agent", "目录证据不足，无法判断会议适配性", true);
        var order = new com.example.smartassistant.router.model.SubTaskResult(
                "t2", "说明订单操作", "order_agent", "可查询、取消和申请售后", true);

        String merged = RouteExecutionService.mergeFallbackPlannedResults(List.of(product, order));

        assertFalse(merged.contains("### 查询热门商品"));
        assertTrue(merged.contains("目录证据不足"));
        assertFalse(merged.contains("### 说明订单操作"));
        assertTrue(merged.contains("申请售后"));
    }
}
