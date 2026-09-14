package com.example.smartassistant.service.quality;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductBudgetDerivationVerifierTest {
    @Test void verifiesBudgetDifferencesInProseWithoutAuthorizingOtherNumbers() {
        for (String phrase : new String[]{"展示6000元与5299元差额701元", "预算6000元和5299元之间的差额为701元",
                "6000.50元与5299.25元的价差是701.25元"}) {
            var result = ProductBudgetDerivationVerifier.verify(phrase,
                    numbers("6000", "6000.50"), numbers("5299", "5299.25"));
            assertTrue(result.errors().isEmpty());
            assertFalse(result.answer().contains("701"));
        }
        var result = ProductBudgetDerivationVerifier.verify("展示6000元与5299元差额701元，商品售价701元。",
                numbers("6000"), numbers("5299"));
        assertTrue(result.answer().contains("商品售价701元"));
    }

    @Test void proseRejectsIncorrectArithmeticAndUnverifiedOperands() {
        for (String phrase : new String[]{"6000元与5299元差额700元", "6000元与4999元差额1001元",
                "6000元与9499元差额3499元", "6000.50元与5299.25元差额701.3元"}) {
            assertFalse(ProductBudgetDerivationVerifier.verify(phrase,
                    numbers("6000", "6000.50"), numbers("5299", "9499", "5299.25", "700")).errors().isEmpty());
        }
    }

    @Test void proseCannotHideAnInventedPriceOrAuthorizeUnrelatedDifferences() {
        for (String phrase : new String[]{"商品售价6000元与5299元差额701元", "市场价5670元与5299元差额371元",
                "7000元与5299元差额1701元"}) {
            assertEquals(phrase, ProductBudgetDerivationVerifier.verify(phrase,
                    numbers("6000"), numbers("5299")).answer());
        }
    }

    private static Set<BigDecimal> numbers(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new)
                .map(BigDecimal::stripTrailingZeros).collect(Collectors.toSet());
    }

    @Test void exactBudgetDifferenceIsExcludedOnlyInItsOwnPhrase() {
        var result = ProductBudgetDerivationVerifier.verify("价格5299元，价差701元。另一个商品售价701元。",
                numbers("6000"), numbers("5299"));
        assertTrue(result.errors().isEmpty());
        assertFalse(result.answer().contains("价差701"));
        assertTrue(result.answer().contains("另一个商品售价701元"));
    }

    @Test void verifiesEquationsAndDecimalAmountsWithoutRounding() {
        for (String answer : new String[]{"6000.50 - 5299.25 = 701.25", "价格5299.25元，预算余额701.25元"}) {
            var result = ProductBudgetDerivationVerifier.verify(answer, numbers("6000.50"), numbers("5299.25"));
            assertTrue(result.errors().isEmpty());
            assertFalse(result.answer().contains("701.25"));
        }
        assertFalse(ProductBudgetDerivationVerifier.verify("6000.50-5299.25=701.3",
                numbers("6000.50"), numbers("5299.25")).errors().isEmpty());
    }

    @Test void wrongDifferenceIsRejectedEvenWhenItsNumberOccursElsewhere() {
        var result = ProductBudgetDerivationVerifier.verify("价格5299元，价差700元",
                numbers("6000"), numbers("5299", "700"));
        assertEquals("预算差额错误", result.errors().getFirst().type());
    }

    @Test void wrongPriceAndOverBudgetClaimsRemainErrors() {
        assertFalse(ProductBudgetDerivationVerifier.verify("6000-4999=1001",
                numbers("6000"), numbers("5299")).errors().isEmpty());
        assertFalse(ProductBudgetDerivationVerifier.verify("价格4999元，价差1001元",
                numbers("6000"), numbers("5299")).errors().isEmpty());
        assertFalse(ProductBudgetDerivationVerifier.verify("价格9499元，价差3499元",
                numbers("6000"), numbers("9499")).errors().isEmpty());
        assertFalse(ProductBudgetDerivationVerifier.verify("6000-9499=-3499",
                numbers("6000"), numbers("9499")).errors().isEmpty());
    }

    @Test void ambiguousOrUnrelatedAmountsAreNotNewEvidence() {
        String answer = "预算剩余701元";
        assertEquals(answer, ProductBudgetDerivationVerifier.verify(answer,
                numbers("6000"), numbers("5299", "4999")).answer());
        assertEquals(answer, ProductBudgetDerivationVerifier.verify(answer,
                numbers("6000", "7000"), numbers("5299")).answer());
        assertEquals("市场价5670-5299=371", ProductBudgetDerivationVerifier.verify("市场价5670-5299=371",
                numbers("6000"), numbers("5299")).answer());
    }

    @Test void knownSingleProductRemainderAndZeroRemainderAreSupported() {
        assertEquals("预算差额已按实际价格核实", ProductBudgetDerivationVerifier.verify("预算还剩701元",
                numbers("6000"), numbers("5299")).answer());
        assertTrue(ProductBudgetDerivationVerifier.verify("价格6000元，价差0元",
                numbers("6000"), numbers("6000")).errors().isEmpty());
    }
}
