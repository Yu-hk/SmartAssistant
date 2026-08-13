package com.example.smartassistant.common.gateway.tool.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoverToolsToolSchemaTest {

    @Test
    void isOnlyCreatedByMcpAutoConfiguration() {
        org.assertj.core.api.Assertions.assertThat(
                DiscoverToolsTool.class.isAnnotationPresent(Component.class)).isFalse();
    }

    @Test
    void onlyCapabilityQueryIsRequired() throws Exception {
        Method method = DiscoverToolsTool.class.getDeclaredMethod(
                "discoverTools", String.class, String[].class, String.class, Integer.class);
        JsonNode schema = new ObjectMapper().readTree(JsonSchemaGenerator.generateForMethodInput(method));

        assertThat(schema.path("required").findValuesAsText(""))
                .isEmpty();
        assertThat(schema.path("required").toString())
                .isEqualTo("[\"capabilityQuery\"]");
    }
}
