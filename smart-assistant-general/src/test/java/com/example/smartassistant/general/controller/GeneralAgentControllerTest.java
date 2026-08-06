package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
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
        assertEquals("hi", controller.process(Map.of("question", "hello")));
        verify(agent).execute("hello");
    }

    @Test
    void rejectsBlankQuestion() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        GeneralAgentController controller = new GeneralAgentController(agent);
        assertEquals("Question must not be blank", controller.process(Map.of("question", " ")));
    }
}
