package com.example.smartassistant.service.quality;

import com.example.smartassistant.common.error.CustomerMessages;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductDomainQualityValidatorTest {

    @Test
    void guardRefusalIsNotVerifiedEvenWhenItContainsNoUnsupportedClaims() {
        var result = new ProductDomainQualityValidator().evaluate(
                "⚠️ 您的输入已被内容安全策略拦截：检测到注入模式",
                RetrievalQualityResult.highQuality("[E1] 1999元", 0.9), checked(false, 0));
        assertTrue(result.isFail());
    }

    private final ProductDomainQualityValidator validator = new ProductDomainQualityValidator();

    @ParameterizedTest
    @MethodSource("operationalRecoveryReplies")
    void operationalRecoveryIsNeverVerifiedByEvidenceShortcuts(String answer) {
        for (RetrievalQualityResult retrieval : new RetrievalQualityResult[]{
                RetrievalQualityResult.highQuality("[E1] 库存状态包括可售和锁定。", 0.9),
                RetrievalQualityResult.noData("库存计算规则"), null}) {
            for (boolean hasToolEvidence : new boolean[]{false, true}) {
                DomainQualityResult result = validator.evaluate(
                        "  " + answer + "\n", retrieval, checked(false, 0), hasToolEvidence);

                assertTrue(result.isFail(), "Operational recovery must not pass: " + answer);
                assertTrue(result.getReasonCodes().contains("PRODUCT_EXECUTION_FAILURE"));
            }
        }
    }

    static Stream<String> operationalRecoveryReplies() {
        return Stream.of(
                "检测到 Agent 报告被阻塞，无法继续。请提供更多信息或重新描述需求。",
                "检测到基础设施故障，已暂停以避免持续重试。请稍后再试。",
                "循环守卫暂停",
                CustomerMessages.UNAVAILABLE,
                "处理失败：上游服务连接超时",
                "❌ 执行失败");
    }

    @ParameterizedTest
    @MethodSource("knowledgeInsufficiencyReplies")
    void knowledgeInsufficiencyIsNotAnOperationalFailure(String answer) {
        DomainQualityResult result = validator.evaluate(answer,
                RetrievalQualityResult.highQuality("[E1] 库存状态包括可售和锁定。", 0.9),
                checked(false, 0));

        assertTrue(result.isPass());
        assertTrue(result.getReasonCodes().contains("PRODUCT_FACTS_VERIFIED"));
    }

    static Stream<String> knowledgeInsufficiencyReplies() {
        return Stream.of(
                "知识库仅列出库存状态，暂时无法确定可售库存计算公式。请提供相关规则后核实。",
                "现有资料信息不足，没有说明锁定库存和质检库存如何处理；不能据此推测公式。",
                "文档中的历史故障提示是“检测到基础设施故障，已暂停以避免持续重试。请稍后再试。”，并非本次请求失败。");
    }

    @Test
    void passesAnswerBackedByHighQualityEvidence() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "型号 A 的屏幕为 14 英寸，内存为 16GB。", 0.88);

        DomainQualityResult result = validator.evaluate(
                "型号 A 配备 14 英寸屏幕和 16GB 内存。", retrieval, checked(false, 0.0));

        assertTrue(result.isPass());
    }

    @Test
    void warnsOnFaithfulnessViolation() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "型号 A 的屏幕为 14 英寸。", 0.88);

        DomainQualityResult result = validator.evaluate(
                "型号 A 的屏幕为 16 英寸。", retrieval, checked(true, 0.8));

        assertTrue(result.isWarn());
        assertTrue(result.getReasonCodes().contains("UNSUPPORTED_PRODUCT_CLAIMS"));
    }

    @Test
    void warnsWhenFactsHaveNoEvidence() {
        DomainQualityResult result = validator.evaluate("该商品现货价格为 3999 元。", null, null);

        assertTrue(result.isWarn());
        assertTrue(result.getReasonCodes().contains("UNVERIFIED_PRODUCT_FACTS"));
    }

    @Test
    void acceptsStructuredNoEvidenceRefusal() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.noData("商品 A");

        DomainQualityResult result = validator.evaluate(retrieval.getRejectionMessage(), retrieval, null);

        assertTrue(result.isPass());
        assertTrue(result.getReasonCodes().contains("SAFE_NO_EVIDENCE_RESPONSE"));
    }

    @Test
    void rejectsEmptyAnswer() {
        DomainQualityResult result = validator.evaluate(" ", null, null);

        assertTrue(result.isFail());
    }

    private static FaithfulnessGuard.FaithfulnessVerdict checked(boolean hallucination, double score) {
        return new FaithfulnessGuard.FaithfulnessVerdict(true, hallucination, score, List.of(), null);
    }
}
