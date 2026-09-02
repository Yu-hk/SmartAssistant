package com.example.smartassistant.consumer.service.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationGateServiceTest {

    @Test
    void parsesAcquiredLeaseWithFencingToken() {
        ConversationGateService.GateDecision decision = ConversationGateService.GateDecision.parse(
                "ACQUIRED|session-a|0|lease-1", "42", "session-a", "request-a");

        assertTrue(decision.acquired());
        assertEquals("42", decision.userId());
        assertEquals("session-a", decision.activeSessionId());
        assertEquals("lease-1", decision.leaseToken());
    }

    @Test
    void distinguishesSuspendedSessionFromBlockedRequest() {
        ConversationGateService.GateDecision otherSession = ConversationGateService.GateDecision.parse(
                "SESSION_SUSPENDED|session-active|3|", "42", "session-b", "request-b");
        ConversationGateService.GateDecision sameSession = ConversationGateService.GateDecision.parse(
                "REQUEST_BLOCKED|session-active|1|", "42", "session-active", "request-c");

        assertFalse(otherSession.acquired());
        assertEquals(ConversationGateService.GateStatus.SESSION_SUSPENDED, otherSession.status());
        assertEquals(3, otherSession.queuePosition());
        assertEquals(ConversationGateService.GateStatus.REQUEST_BLOCKED, sameSession.status());
    }

    @Test
    void malformedRedisReplyFailsClosed() {
        ConversationGateService.GateDecision decision = ConversationGateService.GateDecision.parse(
                "unexpected", "42", "session-a", "request-a");

        assertEquals(ConversationGateService.GateStatus.UNAVAILABLE, decision.status());
        assertFalse(decision.acquired());
    }

    @Test
    void closePromotesOldestSuspendedSession() {
        ConversationGateService.CloseDecision decision =
                ConversationGateService.CloseDecision.parse("CLOSED|session-next");

        assertEquals(ConversationGateService.CloseStatus.CLOSED, decision.status());
        assertEquals("session-next", decision.activatedSessionId());
    }
}
