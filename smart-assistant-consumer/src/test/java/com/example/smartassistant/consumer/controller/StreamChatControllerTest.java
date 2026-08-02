package com.example.smartassistant.consumer.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamChatControllerTest {

    @Test
    void explicitRequestIdIsPreserved() {
        assertEquals("request-123",
                StreamChatController.resolveDecisionKey("request-123", "session-1"));
    }

    @Test
    void eachTurnGetsUniqueDecisionKeyWithinSameSession() {
        String first = StreamChatController.resolveDecisionKey(null, "session-1");
        String second = StreamChatController.resolveDecisionKey(null, "session-1");

        assertTrue(first.startsWith("session-1-"));
        assertTrue(second.startsWith("session-1-"));
        assertNotEquals(first, second);
    }
}
