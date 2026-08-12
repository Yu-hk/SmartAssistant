package com.example.smartassistant.toolregistry.general.tool;

import com.example.smartassistant.common.intent.WeatherQuerySupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherToolTest {

    @Test
    void buildsCoordinateAndNormalizedCityUris() {
        assertEquals("https://wttr.in/39.904200%2C116.407400?format=j1",
                WeatherTool.buildWeatherUri("39.904200,116.407400").toString());
        assertEquals("https://wttr.in/%E5%8C%97%E4%BA%AC?format=j1",
                WeatherTool.buildWeatherUri(
                        WeatherQuerySupport.normalizeLocation("请查询北京天气")).toString());
    }
}
