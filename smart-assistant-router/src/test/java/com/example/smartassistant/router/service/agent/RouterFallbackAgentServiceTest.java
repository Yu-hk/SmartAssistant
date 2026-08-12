package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.location.DeviceLocation;
import com.example.smartassistant.toolregistry.general.tool.WeatherTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterFallbackAgentServiceTest {

    @Test
    void weatherUsesToolRegistryToolWithoutCallingRemoteGeneralService() {
        WeatherTool weather = mock(WeatherTool.class);
        when(weather.queryWeather("北京")).thenReturn("北京 25°C");
        RouterFallbackAgentService service = org.mockito.Mockito.mock(
                RouterFallbackAgentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "weatherTool", weather);

        assertThat(service.execute("查询北京天气", 7L, null)).isEqualTo("北京 25°C");
        verify(weather).queryWeather("北京");
    }

    @Test
    void weatherUsesAuthorizedDeviceLocation() {
        WeatherTool weather = mock(WeatherTool.class);
        DeviceLocation location = new DeviceLocation(39.9, 116.4, 30.0, System.currentTimeMillis());
        when(weather.queryWeather(location.coordinateQuery())).thenReturn("当前位置 25°C");
        RouterFallbackAgentService service = org.mockito.Mockito.mock(
                RouterFallbackAgentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "weatherTool", weather);

        assertThat(service.execute("查询天气", 7L, location)).isEqualTo("当前位置 25°C");
    }
}
