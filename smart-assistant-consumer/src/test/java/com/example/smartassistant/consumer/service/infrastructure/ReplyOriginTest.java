package com.example.smartassistant.consumer.service.infrastructure;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReplyOriginTest {
    @Test void acceptsOnlyExplicitBooleanAndDoesNotInferFromUsage() {
        assertNull(ReplyOrigin.fromDecision(null));
        assertNull(ReplyOrigin.fromDecision(Map.of("totalTokens", 0)));
        assertNull(ReplyOrigin.fromDecision(Map.of("fromCache", "true")));
        assertNull(ReplyOrigin.fromDecision(Map.of("fromCache", 0)));
        assertEquals(true, ReplyOrigin.fromDecision(Map.of("fromCache", true, "totalTokens", 7)));
        assertEquals(false, ReplyOrigin.fromDecision(Map.of("fromCache", false, "totalTokens", 0)));
    }

    @Test void roundTripsExplicitOriginsButLeavesLegacyUnknown() {
        for (boolean streaming : new boolean[]{true, false}) {
            for (Boolean flag : new Boolean[]{true, false, null}) {
                assertEquals(flag, ReplyOrigin.fromAuditMethod(ReplyOrigin.auditMethod(streaming, flag)));
            }
        }
        assertNull(ReplyOrigin.fromAuditMethod(null));
        assertNull(ReplyOrigin.fromAuditMethod("CACHE"));
        assertNull(ReplyOrigin.fromAuditMethod("stream_cache"));
    }
}
