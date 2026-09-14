package com.example.smartassistant.common.rag.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductPriceFaithfulnessTest {
    private final FaithfulnessGuard guard = new FaithfulnessGuard();

    @Test void fourAndFiveDigitPricesMustBeVerified() {
        assertTrue(guard.check("售价4999元", "售价1999元").hallucination());
        assertTrue(guard.check("售价12999元", "售价1999元").hallucination());
        assertFalse(guard.check("售价1999元", "售价1999元").hallucination());
    }

    @Test void substringIsNotNumericEvidence() {
        assertTrue(guard.check("售价999元", "售价1999元").hallucination());
        assertTrue(guard.check("优惠50%", "优惠50元").hallucination());
    }

    @Test void equivalentNumericFormatsRemainSupported() {
        assertFalse(guard.check("售价1,999.00元", "售价1999元").hallucination());
        assertFalse(guard.check("售价1999元", "售价1,999.00元").hallucination());
        assertFalse(guard.check("优惠50.0%", "优惠50%").hallucination());
    }
}
