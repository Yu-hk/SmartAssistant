package com.example.smartassistant.toolregistry.general;

import com.example.smartassistant.common.gateway.tool.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GeneralToolCatalogRegistrarTest {
    @Test
    void registersExactCatalogAndRepeatedRegistrationDoesNotDuplicateNames() {
        var registry = new ToolRegistry();
        var registrar = new GeneralToolCatalogRegistrar(registry);
        registrar.register();
        registrar.register();
        assertEquals(11, registry.size());
        assertEquals(Set.of("calculate", "convertTemperature", "convertLength", "convertWeight",
                "getHotNews", "webSearch", "convertCurrency", "queryCorrections", "executeScript",
                "analyzeImage", "generateImage"), registry.getAll().stream()
                .map(ToolDefinition::getName).collect(Collectors.toSet()));
    }

    @Test
    void readToolsRetainSharedReadOnlyRiskAndFunctionalCapabilities() {
        var registry = new ToolRegistry();
        new GeneralToolCatalogRegistrar(registry).register();
        Map<String, String> expected = Map.of("calculate", "calculation", "convertTemperature", "unit-conversion",
                "convertLength", "unit-conversion", "convertWeight", "unit-conversion", "getHotNews", "web-search",
                "webSearch", "web-search", "convertCurrency", "currency", "queryCorrections", "memory",
                "analyzeImage", "image-analysis");
        expected.forEach((name, capability) -> {
            var definition = registry.get(name);
            assertEquals(ToolTier.SHARED, definition.getToolTier(), name);
            assertEquals(ToolRiskLevel.READ, definition.getRiskLevel(), name);
            assertArrayEquals(new String[]{"GENERAL", "READ_ONLY"}, definition.getTags(), name);
            assertEquals(List.of(capability), definition.getFunctionalCapabilities(), name);
            assertFalse(definition.isNeedsApproval(), name);
            assertEquals(ToolStatus.ACTIVE, definition.getStatus(), name);
        });
    }

    @Test
    void scriptAndImageGenerationCannotBeMislabelledReadOnly() {
        var registry = new ToolRegistry();
        new GeneralToolCatalogRegistrar(registry).register();
        for (String name : List.of("executeScript", "generateImage")) {
            var definition = registry.get(name);
            assertEquals(ToolRiskLevel.MEDIUM, definition.getRiskLevel());
            assertEquals(ToolTier.SHARED, definition.getToolTier());
            assertFalse(List.of(definition.getTags()).contains("READ_ONLY"));
            assertEquals(0, definition.getMaxRetries(), "side effects must not gain automatic retries");
        }
        assertArrayEquals(new String[]{"GENERAL", "SANDBOXED"}, registry.get("executeScript").getTags());
        assertArrayEquals(new String[]{"GENERAL", "IMAGE"}, registry.get("generateImage").getTags());
    }
}
