package com.example.smartassistant.common.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextFaithfulnessChecker 单元测试。
 */
class ContextFaithfulnessCheckerTest {

    private final ContextFaithfulnessChecker checker = new ContextFaithfulnessChecker();

    // ==================== 冲突标记 ====================

    @Test
    @DisplayName("无冲突上下文应原样返回")
    void markConflicts_noConflict_shouldReturnOriginal() {
        List<String> items = List.of("退货政策支持7天无理由", "换货需要保持商品完好");
        List<String> result = checker.markConflicts(items);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("检测到 是/否 冲突应添加冲突标记")
    void markConflicts_yesNoConflict_shouldAddWarning() {
        List<String> items = List.of("该商品支持7天无理由退货", "该商品不支持7天无理由退货");
        List<String> result = checker.markConflicts(items);
        assertTrue(result.size() > 2, "应添加冲突警告");
        assertTrue(result.get(0).contains("冲突"), "首行应为冲突警告");
    }

    @Test
    @DisplayName("检测到 有效/无效 冲突")
    void markConflicts_validInvalidConflict_shouldAddWarning() {
        List<String> items = List.of("该优惠券仍然有效", "该优惠券已失效");
        List<String> result = checker.markConflicts(items);
        assertTrue(result.get(0).contains("冲突"), "应添加冲突警告");
    }

    @Test
    @DisplayName("空列表应返回空")
    void markConflicts_empty_shouldReturnEmpty() {
        assertTrue(checker.markConflicts(List.of()).isEmpty());
    }

    // ==================== Faithfulness 校验 ====================

    @Test
    @DisplayName("答案引用存在性校验：所有CID有效应通过")
    void checkFaithfulness_allCidValid_shouldPass() {
        String context = "[CID:POLICY-RETURN-v2] 退货政策\n[CID:POLICY-EXCHANGE-v1] 换货政策";
        String answer = "根据[CID:POLICY-RETURN-v2]的规定，支持7天无理由退货。";
        var result = checker.checkFaithfulness(answer, context);
        assertTrue(result.passed(), "所有CID都存在应通过");
        assertEquals(1.0, result.score(), 0.01);
    }

    @Test
    @DisplayName("答案引用存在性校验：无效CID应失败")
    void checkFaithfulness_invalidCid_shouldFail() {
        String context = "[CID:POLICY-RETURN-v2] 退货政策";
        String answer = "根据[CID:NONEXISTENT-DOC]的规定...";
        var result = checker.checkFaithfulness(answer, context);
        assertFalse(result.passed(), "引用不存在的CID应失败");
        assertTrue(result.score() < 1.0);
        assertFalse(result.invalidReferences().isEmpty());
    }

    @Test
    @DisplayName("无CID引用的答案应直接通过")
    void checkFaithfulness_noCid_shouldPass() {
        String context = "[CID:POLICY-RETURN-v2] 退货政策";
        String answer = "好的，我明白了。";
        var result = checker.checkFaithfulness(answer, context);
        assertTrue(result.passed());
    }

    @Test
    @DisplayName("空答案应通过")
    void checkFaithfulness_emptyAnswer_shouldPass() {
        var result = checker.checkFaithfulness("", "[CID:test] content");
        assertTrue(result.passed());
    }

    @Test
    @DisplayName("多CID引用中部分无效应降低分数")
    void checkFaithfulness_partialInvalid_shouldLowerScore() {
        String context = "[CID:VALID-001] 有效文档";
        String answer = "引用[CID:VALID-001]和[CID:INVALID-001]和[CID:INVALID-002]";
        var result = checker.checkFaithfulness(answer, context);
        assertFalse(result.passed());
        assertEquals(1.0 / 3.0, result.score(), 0.01);
    }
}
