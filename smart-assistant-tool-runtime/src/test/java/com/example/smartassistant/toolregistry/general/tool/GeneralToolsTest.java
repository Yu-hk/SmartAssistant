package com.example.smartassistant.toolregistry.general.tool;

import com.example.smartassistant.common.correction.CorrectionService;
import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.tool.spi.GeneralDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneralToolsTest {

    private final CorrectionService corrections = mock(CorrectionService.class);
    private final GeneralDataProvider scripts = mock(GeneralDataProvider.class);
    private final GeneralTools tools = new GeneralTools(corrections, scripts);

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

    @ParameterizedTest
    @CsvSource({"1/3, 0.333333", "-2.5*2, -5", "2^3, 8", "abs(-3), 3",
            "exp(1000), 结果无穷大", "sqrt(-1), 结果不是有效数字"})
    void formatsMathWithoutInventingFiniteResults(String expression, String expected) {
        assertEquals(expected, tools.calculate(expression));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "2(3)", "unknown(2)", "1/0"})
    void invalidMathHasMachineReadableNonRetryableError(String expression) throws Exception {
        assertError(tools.calculate(expression), AgentErrorCode.VALIDATION_EXPRESSION_PARSE);
    }

    @ParameterizedTest
    @CsvSource({"0,C,C,0°C", "0,C,F,32°F", "0,C,K,273.15K",
            "32,F,C,0°C", "32,F,F,32°F", "32,F,K,273.15K",
            "273.15,K,C,0°C", "273.15,K,F,32°F", "273.15,K,K,273.15K"})
    void supportsAllTemperatureDirections(double value, TemperatureUnit from,
                                          TemperatureUnit to, String expected) {
        assertEquals(expected, tools.convertTemperature(value, from, to));
    }

    @ParameterizedTest
    @CsvSource({"M,1,m", "KM,1000,km", "CM,0.01,cm", "MM,0.001,mm",
            "FT,0.3048,ft", "IN,0.0254,in", "MI,1609.344,mi"})
    void lengthFactorsAreAnchoredInMetres(LengthUnit unit, double metres, String symbol) {
        assertEquals(metres, unit.toMeters(1), 1e-9);
        assertEquals(1, unit.fromMeters(metres), 1e-9);
        assertEquals(symbol, unit.getSymbol());
        assertEquals("1 " + symbol, tools.convertLength(metres, LengthUnit.M, unit));
    }

    @ParameterizedTest
    @CsvSource({"KG,1,kg", "G,0.001,g", "MG,0.000001,mg", "LB,0.45359237,lb",
            "OZ,0.028349523125,oz", "T,1000,t"})
    void weightFactorsAreAnchoredInKilograms(WeightUnit unit, double kilograms, String symbol) {
        assertEquals(kilograms, unit.toKg(1), 1e-12);
        assertEquals(1, unit.fromKg(kilograms), 1e-9);
        assertEquals(symbol, unit.getSymbol());
        assertEquals("1 " + symbol, tools.convertWeight(kilograms, WeightUnit.KG, unit));
    }

    @Test
    void missingConversionUnitsReturnErrorsRatherThanThrowing() throws Exception {
        assertError(tools.convertTemperature(1, null, TemperatureUnit.C), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
        assertError(tools.convertTemperature(1, TemperatureUnit.C, null), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
        assertError(tools.convertLength(1, null, LengthUnit.M), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
        assertError(tools.convertLength(1, LengthUnit.M, null), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
        assertError(tools.convertWeight(1, null, WeightUnit.KG), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
        assertError(tools.convertWeight(1, WeightUnit.KG, null), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
        assertError(tools.convertCurrency(1, null, "CNY"), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
        assertError(tools.convertCurrency(1, "CNY", null), AgentErrorCode.VALIDATION_CONVERSION_ERROR);
    }

    @Test
    void normalizesSameCurrencyAndPreservesNegativeAndFractionalAmounts() {
        assertEquals("-12.345679 CNY", tools.convertCurrency(-12.3456789, " cny ", "CNY"));
        assertEquals("0 USD", tools.convertCurrency(0, "usd", " USD "));
    }

    @Test
    void correctionLookupKeepsGeneralScopeAndOriginalTopic() {
        when(corrections.queryCorrections("general", "重量")).thenReturn("已核实的修正");
        assertEquals("已核实的修正", tools.queryCorrections("重量"));
        verify(corrections).queryCorrections("general", "重量");
    }

    @Test
    void missingCorrectionTopicUsesEmptyTopicAndNoResultFallback() {
        when(corrections.queryCorrections("general", "")).thenReturn(" \n");
        assertEquals("未找到相关的修正记录，可以按正常流程回答。", tools.queryCorrections(null));
        verify(corrections).queryCorrections("general", "");
    }

    @Test
    void scriptSuccessIsPassedThroughUnchanged() {
        when(scripts.executeScript("x=2")).thenReturn(new GeneralDataProvider.ScriptResult(
                true, "x = 2", null, null, null));
        assertEquals("x = 2", tools.executeScript("x=2"));
        verify(scripts).executeScript("x=2");
    }

    @Test
    void scriptFailurePreservesCodeAndEscapesMessageAndHint() throws Exception {
        var code = AgentErrorCode.SECURITY_SCRIPT_REJECTED;
        when(scripts.executeScript(null)).thenReturn(new GeneralDataProvider.ScriptResult(
                false, null, code.name(), "拒绝\"内容\"\n", "请改用数学\n表达式"));
        var error = new ObjectMapper().readTree(tools.executeScript(null));
        assertEquals(code.getCode(), error.path("error_code").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertEquals("拒绝\"内容\"\n", error.path("message").asText());
        assertEquals("请改用数学\n表达式", error.path("hint").asText());
        assertEquals(error.get("hint"), error.get("suggestion"));
    }

    private static void assertError(String output, AgentErrorCode code) throws Exception {
        var json = new ObjectMapper().readTree(output);
        assertEquals(code.getCode(), json.path("error_code").asText());
        assertEquals(code.isRetryable(), json.path("retryable").asBoolean());
        assertFalse(json.path("message").asText().isBlank());
    }
}
