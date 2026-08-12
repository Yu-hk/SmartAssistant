package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.general.tool.WeatherTool;
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
        WeatherTool weatherTool = mock(WeatherTool.class);
        GeneralAgentController controller = new GeneralAgentController(agent, weatherTool);

        String response = controller.process(Map.of("question", "查询天气"), null).getBody();

        assertTrue(response.contains("哪个城市"));
        verify(agent, never()).execute("查询天气");
        verify(weatherTool, never()).queryWeather(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void weatherQueryWithBeijingReturnsWeatherData() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        WeatherTool weatherTool = mock(WeatherTool.class);
        when(weatherTool.queryWeather("北京")).thenReturn("北京当前晴，温度 28°C");
        GeneralAgentController controller = new GeneralAgentController(agent, weatherTool);

        String response = controller.process(Map.of("question", "北京天气"), null).getBody();

        assertEquals("北京当前晴，温度 28°C", response);
        verify(weatherTool).queryWeather("北京");
        verify(agent, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void weatherQueryUsesAuthorizedDeviceLocationWithoutAskingForCity() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        WeatherTool weatherTool = mock(WeatherTool.class);
        when(weatherTool.queryWeather("39.904200,116.407400"))
                .thenReturn("当前位置晴，温度 28°C");
        GeneralAgentController controller = new GeneralAgentController(agent, weatherTool);

        String response = controller.process(Map.of(
                "question", "查询天气",
                "deviceLocation", Map.of(
                        "latitude", 39.9042,
                        "longitude", 116.4074,
                        "accuracyMeters", 1000,
                        "capturedAt", System.currentTimeMillis())), null).getBody();

        assertEquals("当前位置晴，温度 28°C", response);
        verify(weatherTool).queryWeather("39.904200,116.407400");
        verify(agent, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void unifiedWeatherResponseCarriesPassingDeterministicQuality() {
        SmartReActAgent agent = mock(SmartReActAgent.class);
        WeatherTool weatherTool = mock(WeatherTool.class);
        when(weatherTool.queryWeather("北京")).thenReturn("北京当前晴，温度 28°C");
        GeneralAgentController controller = new GeneralAgentController(agent, weatherTool);

        var response = controller.execute(
                com.example.smartassistant.common.agent.protocol.AgentExecutionRequest.answer(
                        "req-weather", "1", "请查询北京天气", null),
                "req-weather");

        assertEquals("PASS", response.getBody().quality().status());
        assertTrue(response.getBody().quality().reasonCodes()
                .contains("DETERMINISTIC_WEATHER_RESPONSE"));
    }
}
