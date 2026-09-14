package com.example.smartassistant.service.quality;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductBudgetClaimVerifierTest {
    private static final String CONTEXT = "{price=5299.0, stock=充足}";
    private static final String QUESTION = "预算6000元以内，请推荐手机";

    @Test void normalizesFinancialNotationWithoutChangingProductNames() {
        assertEquals("小米Ａ版 ￥5,299.00元 <= 6000元", ProductMoneySyntax.normalize("小米Ａ版 ￥５，２９９．００元 ＜＝ ６０００元"));
        for (String phrase : new String[]{"5299元＜6000元", "￥５，２９９．００元 ≤ ￥６，０００元",
                "价格5299元 <= 预算6000元", "预算6000元 >= 售价5299元", "6000元＞5299元"}) {
            var checked = ProductBudgetClaimVerifier.verify(phrase, CONTEXT, QUESTION);
            assertTrue(checked.errors().isEmpty(), phrase);
            assertEquals("价格与预算比较已核实", checked.answer(), phrase);
        }
    }

    @Test void preservesPunctuationAroundIndependentRestatementsAndCalculations() {
        for (String phrase : new String[]{"预算≤6000元，5299≤6000", "预算≤6000元,5299≤6000",
                "符合6000元内预算，价格5299元，价差701元。", "符合6000元内预算,6000-5299=701",
                "符合预算：5299元＜6000元"}) {
            var checked = ProductBudgetClaimVerifier.verify(phrase, CONTEXT, QUESTION);
            assertTrue(checked.errors().isEmpty(), () -> checked.errors().toString());
            assertFalse(checked.answer().contains("6000"), checked.answer());
            assertFalse(checked.answer().contains("701"), checked.answer());
            var verdict = new com.example.smartassistant.common.rag.eval.FaithfulnessGuard().check(checked.answer(), CONTEXT);
            assertFalse(verdict.hallucination(), () -> verdict.claims().toString());
        }
    }

    @Test void checksAllOperatorsInBothDirectionsAndAtEquality() {
        for (String op : new String[]{"<", "<=", "≤", ">", ">=", "≥", "="}) {
            boolean less = op.equals("<") || op.equals("<=") || op.equals("≤");
            boolean greater = op.equals(">") || op.equals(">=") || op.equals("≥");
            assertEquals(less, ProductBudgetClaimVerifier.verify("5299元" + op + "6000元", CONTEXT, QUESTION).errors().isEmpty(), op);
            assertEquals(greater, ProductBudgetClaimVerifier.verify("6000元" + op + "5299元", CONTEXT, QUESTION).errors().isEmpty(), op);
            assertEquals(!op.equals("<") && !op.equals(">"), ProductBudgetClaimVerifier.verify(
                    "6000元" + op + "6000元", "price=6000", QUESTION).errors().isEmpty(), op);
        }
    }

    @Test void recognizesOmittedBudgetOnlyInKnownRequirementPhrases() {
        for (String phrase : new String[]{"若坚持6000元内且重视拍照", "如果坚持6000元以内", "仍按6000元内",
                "希望6000元以下", "要求6000元以内", "限定在6000元内", "控制在6000元以内"}) {
            var checked = ProductBudgetClaimVerifier.verify(phrase, CONTEXT, QUESTION);
            assertTrue(checked.errors().isEmpty());
            assertFalse(checked.answer().contains("6000"), phrase);
        }
        assertTrue(ProductBudgetClaimVerifier.verify("若坚持7000元内", CONTEXT, QUESTION).answer().contains("7000"));
        assertTrue(ProductBudgetClaimVerifier.verify("若坚持6000元内", CONTEXT, "推荐手机").answer().contains("6000"));
        assertTrue(ProductBudgetClaimVerifier.verify("售价6000元以内", CONTEXT, QUESTION).answer().contains("6000"));
    }

    @Test void rejectsWrongRolesAndArithmeticBeforeMaskingExplicitBudgets() {
        for (String phrase : new String[]{"售价5299元＞预算6000元", "预算6000元＜售价5299元",
                "商品售价6000元≥5299元", "价格6000元=5299元", "5299元≤售价6000元"}) {
            assertFalse(ProductBudgetClaimVerifier.verify(phrase, CONTEXT, QUESTION).errors().isEmpty(), phrase);
        }
        var checked = ProductBudgetClaimVerifier.verify("预算6000元与5299元差额700元", CONTEXT, QUESTION);
        assertFalse(checked.errors().isEmpty());
    }

    @Test void calculatedNumbersNeverBecomeGlobalPriceEvidence() {
        var checked = ProductBudgetClaimVerifier.verify("６０００－５２９９＝７０１。商品售价701元。", CONTEXT, QUESTION);
        assertTrue(checked.errors().isEmpty());
        assertTrue(checked.answer().contains("商品售价701元"));
        assertFalse(checked.answer().contains("6000"));
        assertTrue(ProductBudgetClaimVerifier.verify("商品售价6000-5299=701", CONTEXT, QUESTION).answer().contains("6000"));
    }

    @Test void percentagesUnrelatedOperandsAndProductCodesRemainUnmasked() {
        for (String phrase : new String[]{"5299%＜6000%", "4999元＜6000元", "5299元＜7000元",
                "SKU5299<6000", "$5299<6000", "$ 5299<6000", "售价6000元预算"}) {
            var checked = ProductBudgetClaimVerifier.verify(phrase, CONTEXT, QUESTION);
            assertFalse(checked.answer().contains("价格与预算比较已核实"), phrase);
            assertTrue(checked.answer().contains("6000") || checked.answer().contains("7000"), phrase);
        }
    }
}
