package com.example.smartassistant.general.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherToolTest {

    @Test
    void buildsCoordinateWeatherUriWithoutExposingItAsAQueryParameter() {
        assertEquals(
                "https://wttr.in/39.904200%2C116.407400?format=j1",
                WeatherTool.buildWeatherUri("39.904200,116.407400").toString());
    }

    @Test
    void normalizesCompleteChineseWeatherUtteranceBeforeBuildingUri() {
        assertEquals(
                "https://wttr.in/%E5%8C%97%E4%BA%AC?format=j1",
                WeatherTool.buildWeatherUri(
                        com.example.smartassistant.common.intent.WeatherQuerySupport
                                .normalizeLocation("请查询北京天气"))
                        .toString());
        assertEquals("上海",
                com.example.smartassistant.common.intent.WeatherQuerySupport
                        .normalizeLocation("麻烦帮我查一下上海明天天气"));
        assertEquals("39.9042,116.4074",
                com.example.smartassistant.common.intent.WeatherQuerySupport
                        .normalizeLocation("39.9042, 116.4074"));
    }
}
