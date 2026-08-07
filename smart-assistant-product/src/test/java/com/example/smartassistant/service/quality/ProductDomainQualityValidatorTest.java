package com.example.smartassistant.service.quality;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductDomainQualityValidatorTest {

    private final ProductDomainQualityValidator validator = new ProductDomainQualityValidator();

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
