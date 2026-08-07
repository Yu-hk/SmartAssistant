package com.example.smartassistant.common.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainQualityResultTest {

    @Test
    void parsesAndSanitizesTransportHeaders() {
        DomainQualityResult result = DomainQualityResult.fromHeaders(
                "warn", "0.62", "missing evidence,order-id_mismatch");

        assertTrue(result.isWarn());
        assertEquals(0.62, result.getScore(), 0.001);
        assertEquals("MISSING_EVIDENCE,ORDER-ID_MISMATCH", result.reasonCodesHeaderValue());
    }

    @Test
    void malformedHeadersFailBackToUnknown() {
        DomainQualityResult result = DomainQualityResult.fromHeaders("invalid", "not-a-number", "reason");

        assertTrue(result.isUnknown());
        assertEquals("NOT_EVALUATED", result.reasonCodesHeaderValue());
    }

    @Test
    void worstKeepsMostConservativeDecision() {
        DomainQualityResult combined = DomainQualityResult.pass(0.9, "PASS")
                .worst(DomainQualityResult.unknown())
                .worst(DomainQualityResult.warn(0.5, "WARN"))
                .worst(DomainQualityResult.fail("FAIL"));

        assertTrue(combined.isFail());
        assertEquals("FAIL", combined.reasonCodesHeaderValue());
    }
}
