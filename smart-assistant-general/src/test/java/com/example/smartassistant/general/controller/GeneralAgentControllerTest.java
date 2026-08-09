package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneralAgentControllerTest {
    @Test
    void delegatesQuestionToGeneralAgent() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        when(agent.execute("hello")).thenReturn("hi");
        GeneralAgentController controller = new GeneralAgentController(agent);
        assertEquals("hi", controller.process(Map.of("question", "hello"), null).getBody());
        verify(agent).execute("hello");
    }

    @Test
    void rejectsBlankQuestion() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        GeneralAgentController controller = new GeneralAgentController(agent);
        assertEquals("Question must not be blank", controller.process(Map.of("question", " "), null).getBody());
    }

    @Test
    void returnsMeasuredTokenUsageToRouter() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        when(agent.execute("hello")).thenReturn("hi");
        TokenUsageCache.record("req-token", 12, 7, 19);

        var response = new GeneralAgentController(agent)
                .process(Map.of("question", "hello"), "req-token");

        assertEquals("12", response.getHeaders().getFirst(TokenUsageHeaders.PROMPT_TOKENS));
        assertEquals("7", response.getHeaders().getFirst(TokenUsageHeaders.COMPLETION_TOKENS));
        assertEquals("19", response.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS));
        var toolUsage = ToolUsageHeaders.decode(
                response.getHeaders().getFirst(ToolUsageHeaders.TOOL_USAGE));
        assertEquals(true, toolUsage.complete());
        assertEquals(0, toolUsage.calls().size());
    }
}
