package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneralAgentControllerWeatherRegressionTest {

    @Test
    void weatherQueryWithoutCityAsksForCity() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        GeneralAgentController controller = new GeneralAgentController(agent);

        String response = controller.process(Map.of("question", "查询天气"));

        assertTrue(response.contains("哪个城市"));
        verify(agent, never()).execute("查询天气");
    }

    @Test
    void weatherQueryWithBeijingReturnsWeatherData() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        when(agent.execute("北京天气")).thenReturn("北京当前晴，温度 28°C");
        GeneralAgentController controller = new GeneralAgentController(agent);

        String response = controller.process(Map.of("question", "北京天气"));

        assertEquals("北京当前晴，温度 28°C", response);
        verify(agent).execute("北京天气");
    }
}
