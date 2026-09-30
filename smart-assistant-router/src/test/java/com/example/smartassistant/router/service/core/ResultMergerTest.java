package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.ExecutionPlan;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultMergerTest {
    private static SubTaskResult multiResult(String id, String question, String answer) {
        var result = new SubTaskResult(id, "只读商品查询", "product", answer, true);
        result.setDomainQuality(DomainQualityResult.pass(1, "MULTI_PRODUCT_CATALOG_FACTS"));
        result.setStructuredData(Map.of("multiProductQueryVersion", 1, "deterministic", true,
                "queryPlan", Map.of("originalQuestion", question, "products", List.of("A", "B")),
                "productEvidence", List.of(Map.of("requestedProduct", "A"), Map.of("requestedProduct", "B"))));
        return result;
    }

    @Test
    void completeMultiProductEvidenceAppearsOnceWithoutModelParaphrase() {
        String question = "A和B分别多少钱？";
        var first = multiResult("first", question, "A售价10元；B资料未知");
        var second = multiResult("second", question, first.getResult());
        var prose = new SubTaskResult("summary", "总结", "general", "模型改写的结果", true);
        var ai = org.mockito.Mockito.mock(com.example.smartassistant.common.rag.advisor.AiChatService.class);
        var merger = new ResultMerger(org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class), ai);
        assertThat(merger.merge(question, List.of(first, second, prose))).isEqualTo(first.getResult());
        prose.setAgentName("router_fallback"); // GraphNodeExecutionService's actual in-process general result.
        assertThat(merger.merge(question, List.of(first, second, prose))).isEqualTo(first.getResult());
        assertThat(List.of(first, second, prose)).hasSize(3);
    }

    @Test
    void genericSummaryCannotHideFailureOrStructuredFacts() {
        String question = "A和B分别多少钱？";
        var product = multiResult("product", question, "已核实价格");
        var general = new SubTaskResult("summary", "总结", "general", "总结", false);
        assertThat(ResultMerger.completeMultiProductReply(question, List.of(product, general))).isNull();
        general.setSuccess(true);
        general.setStructuredData(Map.of("otherFact", "new evidence"));
        assertThat(ResultMerger.completeMultiProductReply(question, List.of(product, general))).isNull();
        general.setStructuredData(Map.of());
        general.setDomainQuality(DomainQualityResult.fail("FAILED"));
        assertThat(ResultMerger.completeMultiProductReply(question, List.of(product, general))).isNull();
    }

    @Test
    void multiProductScopeCannotHideOtherDomainsOrDifferentQuestions() {
        var result = multiResult("first", "A和B分别多少钱？", "已核实价格");
        assertThat(ResultMerger.completeMultiProductReply("A和B有货吗？", List.of(result))).isNull();
        assertThat(ResultMerger.completeMultiProductReply("A和B分别多少钱？", List.of(result,
                new SubTaskResult("order", "订单", "order", "订单结果", true)))).isNull();
        result.setDomainQuality(DomainQualityResult.fail("FAILED"));
        assertThat(ResultMerger.completeMultiProductReply("A和B分别多少钱？", List.of(result))).isNull();
    }

    @Test
    void contradictoryMultiProductRepliesAreNotSilentlyDeduplicated() {
        String question = "A和B分别多少钱？";
        assertThat(ResultMerger.completeMultiProductReply(question, List.of(
                multiResult("one", question, "价格10元"), multiResult("two", question, "价格20元"))))
                .contains("不一致");
    }

    @Test
    void identicalForwardedBrowseRepliesAppearOnceWithoutHidingOtherResults() {
        var browse = new SubTaskResult("discover", "热门商品", "product", "已核实的商品列表", true);
        browse.setStructuredData(Map.of("browsingOnly", true));
        var forwarded = new SubTaskResult("analysis", "浏览结果透传", "product", "已核实的商品列表", true);
        forwarded.setStructuredData(Map.of("browsingOnly", true, "analysis", "已核实的商品列表"));
        var other = new SubTaskResult("other", "其他商品", "product", "不同商品列表", true);
        other.setStructuredData(Map.of("browsingOnly", true));
        var order = new SubTaskResult("order", "订单结果", "order", "已核实的商品列表", true);
        assertThat(ResultMerger.deduplicateBrowseReplies(List.of(browse, forwarded, other, order)))
                .containsExactly(browse, other, order);
        assertThat(List.of(browse, forwarded)).hasSize(2); // audit objects remain intact
        var ai = org.mockito.Mockito.mock(com.example.smartassistant.common.rag.advisor.AiChatService.class);
        var merger = new ResultMerger(org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class), ai);
        assertThat(merger.merge("热门商品", List.of(browse, forwarded))).isEqualTo("已核实的商品列表");
    }

    @Test
    void browseDeduplicationUsesTheSameAgentAliasesAsExecution() {
        var browse = new SubTaskResult("discover", "热门商品", "product_agent", "已核实的商品列表", true);
        browse.setStructuredData(Map.of("browsingOnly", true));
        var forwarded = new SubTaskResult("analysis", "浏览结果透传", "product-agent-service", "已核实的商品列表", true);
        forwarded.setStructuredData(Map.of("browsingOnly", true));
        var ai = org.mockito.Mockito.mock(com.example.smartassistant.common.rag.advisor.AiChatService.class);
        var merger = new ResultMerger(org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class), ai);
        assertThat(merger.merge("热门商品", List.of(browse, forwarded))).isEqualTo("已核实的商品列表");
    }

    @Test
    void publicFailureNeverIncludesInternalCodesNodeLabelsOrRawErrors() {
        for (SubTaskResult.ErrorType type : List.of(SubTaskResult.ErrorType.FATAL_FAILED,
                SubTaskResult.ErrorType.RETRYABLE_FAILED, SubTaskResult.ErrorType.NEED_REPLAN)) {
            SubTaskResult failed = new SubTaskResult("recommend_product", "internal_function_name",
                    "product", "SQLException password=secret", false, type);
            assertThat(ResultMerger.requiredFailureReply(List.of(failed)))
                    .contains("没能完成", "避免重复提交")
                    .doesNotContain(type.name(), "internal_function_name", "SQLException", "secret");
        }
    }

    @Test
    void rejectedRecommendationProvidesCuratedExplanationWithoutRepeatingBudgetQuestion() {
        SubTaskResult failed = new SubTaskResult("recommend", "推荐平板电脑", "product",
                "internal audit details", false, SubTaskResult.ErrorType.FATAL_FAILED);
        failed.setDomainQuality(DomainQualityResult.fail("PRODUCT_ANALYSIS_AUDIT_REJECTED"));
        assertThat(ResultMerger.requiredFailureReply(List.of(failed)))
                .contains("还没能核实", "具体型号")
                .doesNotContain("FATAL_FAILED", "PRODUCT_ANALYSIS_AUDIT_REJECTED",
                        "internal audit details", "请提供预算");
    }

    @Test
    void emptyCatalogConclusionReplacesIntermediateAnalysis() {
        SubTaskResult analysis = successful("analysis", "分析候选", "暂无匹配候选");
        SubTaskResult recommendation = successful("recommend", "推荐商品", "当前目录暂无符合查询条件的候选商品");
        recommendation.setDomainQuality(DomainQualityResult.warn(0.5, "PRODUCT_CATALOG_EVIDENCE_LIMITED"));
        recommendation.setMergePolicy(ExecutionPlan.MergePolicy.REPLACE);
        assertThat(ResultMerger.requiredFailures(List.of(analysis, recommendation))).isEmpty();
        assertThat(ResultMerger.applyMergePolicies(List.of(analysis, recommendation)))
                .containsExactly(recommendation);
    }

    @Test
    void recognizesSuccessfulSingleDomainOrderWorkflow() {
        List<SubTaskResult> results = List.of(
                verifiedOrderResult("query", "查询订单", "状态：待付款"),
                verifiedOrderResult("pending", "查询确认项", "存在支付确认项"));

        assertThat(ResultMerger.isDeterministicOrderWorkflow(results)).isTrue();
    }

    @Test
    void keepsModelMergeForUnstructuredOrderResults() {
        List<SubTaskResult> results = List.of(
                new SubTaskResult("query", "查询订单", "order", "状态：待付款", true),
                new SubTaskResult("pending", "查询确认项", "order", "存在支付确认项", true));

        assertThat(ResultMerger.isDeterministicOrderWorkflow(results)).isFalse();
    }

    @Test
    void keepsModelMergeForMixedDomains() {
        List<SubTaskResult> results = List.of(
                new SubTaskResult("product", "查询商品", "product", "库存充足", true),
                new SubTaskResult("order", "查询订单", "order", "状态：待付款", true));

        assertThat(ResultMerger.isDeterministicOrderWorkflow(results)).isFalse();
    }

    @Test
    void replacePolicySupersedesEarlierParallelConclusions() {
        SubTaskResult analysis = successful("analysis", "分析结果", "评分第一：SKU-OLD");
        analysis.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        SubTaskResult verifiedRecommendation = successful(
                "recommend", "核实推荐", "最终推荐：SKU-NEW");
        verifiedRecommendation.setMergePolicy(ExecutionPlan.MergePolicy.REPLACE);

        assertThat(ResultMerger.applyMergePolicies(List.of(analysis, verifiedRecommendation)))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("recommend");
    }

    @Test
    void finalRecommendationDoesNotExposePlanningNodeLabels() {
        SubTaskResult discovery = successful("discover", "获取当前热门商品候选列表", "候选商品 A");
        discovery.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        SubTaskResult analysis = successful("analysis", "分析销量、性价比和口碑", "商品 A 综合领先");
        analysis.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        SubTaskResult recommendation = successful("recommend", "核实分析并推荐", "推荐商品 A");
        recommendation.setMergePolicy(ExecutionPlan.MergePolicy.REPLACE);

        List<SubTaskResult> selected = ResultMerger.applyMergePolicies(
                List.of(discovery, analysis, recommendation));

        assertThat(selected).singleElement().extracting(SubTaskResult::getResult)
                .isEqualTo("推荐商品 A");
        assertThat(selected.getFirst().getResult())
                .doesNotContain("获取当前热门商品候选列表", "分析销量、性价比和口碑");
    }

    @Test
    void distinguishesRequiredAndOptionalFailures() {
        SubTaskResult required = new SubTaskResult(
                "inventory", "查询库存", "product", "超时", false,
                SubTaskResult.ErrorType.RETRYABLE_FAILED);
        SubTaskResult optional = new SubTaskResult(
                "reviews", "查询补充口碑", "product", "不可用", false,
                SubTaskResult.ErrorType.FATAL_FAILED);
        optional.setRequired(false);

        assertThat(ResultMerger.requiredFailures(List.of(required, optional)))
                .extracting(SubTaskResult::getTaskId)
                .containsExactly("inventory");
    }

    @Test
    void acceptsOnlyVerifiedStructuredResultsForDeterministicMerge() {
        SubTaskResult first = successful("sales", "销量分析", "销量事实");
        first.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        first.setStructuredData(Map.of("sales", 100));
        first.setDomainQuality(DomainQualityResult.pass(1.0, "VERIFIED"));
        SubTaskResult second = successful("reviews", "口碑分析", "口碑事实");
        second.setMergePolicy(ExecutionPlan.MergePolicy.STRUCTURED);
        second.setStructuredData(Map.of("score", 4.8));
        second.setDomainQuality(DomainQualityResult.pass(1.0, "VERIFIED"));

        assertThat(ResultMerger.isStructuredWorkflow(List.of(first, second))).isTrue();
        second.setDomainQuality(DomainQualityResult.unknown());
        assertThat(ResultMerger.isStructuredWorkflow(List.of(first, second))).isFalse();
    }

    @Test
    void detectsConflictingFactsForSameSchemaAndEntity() {
        SubTaskResult inventoryA = successful("inventory_a", "库存查询 A", "库存 10");
        inventoryA.setOutputSchema("inventory.v1");
        inventoryA.setStructuredData(Map.of("sku", "SKU-100", "stock", 10));
        SubTaskResult inventoryB = successful("inventory_b", "库存查询 B", "库存 8");
        inventoryB.setOutputSchema("inventory.v1");
        inventoryB.setStructuredData(Map.of("sku", "SKU-100", "stock", 8));

        assertThat(ResultMerger.structuredConflicts(List.of(inventoryA, inventoryB)))
                .singleElement()
                .asString().contains("inventory.v1.stock", "inventory_a", "inventory_b");
    }

    private static SubTaskResult verifiedOrderResult(
            String taskId, String description, String result) {
        SubTaskResult value = new SubTaskResult(
                taskId, description, "order", result, true);
        value.setDomainQuality(DomainQualityResult.pass(1.0, "DETERMINISTIC_ORDER_QUERY"));
        value.setStructuredData(Map.of("verified", true, "criteriaSatisfied", true));
        return value;
    }

    private static SubTaskResult successful(String taskId, String description, String result) {
        return new SubTaskResult(taskId, description, "product", result, true);
    }
}
