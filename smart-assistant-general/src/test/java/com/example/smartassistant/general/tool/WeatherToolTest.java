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
}
