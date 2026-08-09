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
import static org.mockito.ArgumentMatchers.argThat;

class GeneralAgentControllerWeatherRegressionTest {

    @Test
    void weatherQueryWithoutCityAsksForCity() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        GeneralAgentController controller = new GeneralAgentController(agent);

        String response = controller.process(Map.of("question", "查询天气"), null).getBody();

        assertTrue(response.contains("哪个城市"));
        verify(agent, never()).execute("查询天气");
    }

    @Test
    void weatherQueryWithBeijingReturnsWeatherData() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        when(agent.execute("北京天气")).thenReturn("北京当前晴，温度 28°C");
        GeneralAgentController controller = new GeneralAgentController(agent);

        String response = controller.process(Map.of("question", "北京天气"), null).getBody();

        assertEquals("北京当前晴，温度 28°C", response);
        verify(agent).execute("北京天气");
    }

    @Test
    void weatherQueryUsesAuthorizedDeviceLocationWithoutAskingForCity() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        when(agent.execute(argThat(question -> question.contains("39.904200,116.407400"))))
                .thenReturn("当前位置晴，温度 28°C");
        GeneralAgentController controller = new GeneralAgentController(agent);

        String response = controller.process(Map.of(
                "question", "查询天气",
                "deviceLocation", Map.of(
                        "latitude", 39.9042,
                        "longitude", 116.4074,
                        "accuracyMeters", 1000,
                        "capturedAt", System.currentTimeMillis())), null).getBody();

        assertEquals("当前位置晴，温度 28°C", response);
        verify(agent).execute(argThat(question -> question.contains("不要在回答中暴露精确坐标")));
    }
}
