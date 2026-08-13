package com.example.smartassistant.routing.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingKeysTest {

    @Test
    void exposesStableProducerConsumerKeys() {
        assertEquals("a2a:route:full-decision:req-1", RoutingKeys.fullDecision("req-1"));
        assertEquals("a2a:route:full-decision:notify:req-1", RoutingKeys.decisionNotification("req-1"));
        assertEquals("a2a:task-analysis:req-1", RoutingKeys.taskAnalysis("req-1"));
        assertEquals("routing:sse:events:req-1", RoutingKeys.sseEvents("req-1"));
        assertEquals("routing:sse:stream:req-1", RoutingKeys.sseStream("req-1"));
    }

    @Test
    void rejectsBlankRequestIds() {
        assertThrows(IllegalArgumentException.class, () -> RoutingKeys.fullDecision(" "));
    }
}
