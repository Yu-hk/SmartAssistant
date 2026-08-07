package com.example.smartassistant.common.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the runtime tool section from the callbacks that are actually exposed to the model. */
final class ToolPromptFormatter {

    private static final String DISCOVER_TOOLS = "discover_tools";

    private ToolPromptFormatter() {
    }

    static String appendRuntimeTools(String basePrompt, List<ToolCallback> callbacks) {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        if (callbacks != null) {
            for (ToolCallback callback : callbacks) {
                if (callback == null || callback.getToolDefinition() == null) continue;
                ToolDefinition definition = callback.getToolDefinition();
                definitions.put(definition.name(), definition);
            }
        }

        StringBuilder prompt = new StringBuilder(basePrompt != null ? basePrompt.stripTrailing() : "");
        prompt.append("\n\n═══════════════════════════════════════════════════════════════\n")
                .append("🔧 当前可用工具（以运行时实际能力为准）\n")
                .append("═══════════════════════════════════════════════════════════════\n");

        if (definitions.isEmpty()) {
            prompt.append("当前没有可调用的工具。不要臆造工具名。\n");
        } else {
            for (ToolDefinition definition : definitions.values()) {
                prompt.append("- ").append(definition.name()).append(": ")
                        .append(normalizeDescription(definition.description())).append('\n');
            }
        }

        if (definitions.containsKey(DISCOVER_TOOLS)) {
            prompt.append("\n【能力发现】\n")
                    .append("当所需能力不在上述工具中时，可调用 discover_tools(capabilityQuery=能力名) 发现并加载工具；")
                    .append("发现成功后再使用新加载的工具，不要臆造工具名。\n");
        }
        return prompt.toString();
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) return "未提供说明";
        return description.replaceAll("\\s+", " ").trim();
    }
}
