package com.example.smartassistant.toolregistry.general.tool;

import com.example.smartassistant.common.correction.CorrectionService;
import com.example.smartassistant.common.tool.spi.GeneralDataProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GeneralToolsTest {

    private final GeneralTools tools = new GeneralTools(
            mock(CorrectionService.class), mock(GeneralDataProvider.class));

    @Test
    void calculatesExpressions() {
        assertEquals("5", tools.calculate("2 + 3"));
        assertEquals("35", tools.calculate("(12 + 8) * 3.5 / 2"));
        assertEquals("12", tools.calculate("sqrt(144)"));
        assertTrue(tools.calculate("abc").contains("error_code"));
    }

    @Test
    void convertsTemperatureLengthAndWeight() {
        assertEquals("32°F", tools.convertTemperature(0, TemperatureUnit.C, TemperatureUnit.F));
        assertEquals("0°C", tools.convertTemperature(32, TemperatureUnit.F, TemperatureUnit.C));
        assertEquals("1 km", tools.convertLength(1000, LengthUnit.M, LengthUnit.KM));
        assertEquals("2.54 cm", tools.convertLength(1, LengthUnit.IN, LengthUnit.CM));
        assertEquals("1000 g", tools.convertWeight(1, WeightUnit.KG, WeightUnit.G));
        assertTrue(tools.convertWeight(1, WeightUnit.LB, WeightUnit.KG).startsWith("0.453"));
    }

    @Test
    void sameCurrencyDoesNotNeedNetwork() {
        assertEquals("100 CNY", tools.convertCurrency(100, "CNY", "CNY"));
    }
}
