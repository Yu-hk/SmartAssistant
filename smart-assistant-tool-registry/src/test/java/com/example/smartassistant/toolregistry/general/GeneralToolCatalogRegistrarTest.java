package com.example.smartassistant.toolregistry.general;

import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.toolregistry.service.RegistryService;
import com.example.smartassistant.toolregistry.service.ToolManifestValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralToolCatalogRegistrarTest {

    @Test
    void registersAllMigratedGeneralToolsInRegistryCatalog() {
        ToolRegistry registry = new ToolRegistry();

        new GeneralToolCatalogRegistrar(registry).register();

        assertThat(registry.getAll()).extracting("name").contains(
                "calculate", "queryWeather", "webSearch", "executeScript",
                "analyzeImage", "generateImage")
                .doesNotContain("savePreference", "recallMemories");

        RegistryService registryService = new RegistryService(
                org.mockito.Mockito.mock(
                        com.example.smartassistant.common.gateway.tool.compat.ToolCompatibilityChecker.class),
                org.mockito.Mockito.mock(ToolManifestValidator.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                registryService, "toolRegistry", registry);
        assertThat(registryService.query(
                new String[]{"GENERAL"}, ToolStatus.ACTIVE, null))
                .extracting("name")
                .contains("calculate", "queryWeather", "webSearch");
    }
}
