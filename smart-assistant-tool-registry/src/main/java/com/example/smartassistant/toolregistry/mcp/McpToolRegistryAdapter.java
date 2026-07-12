package com.example.smartassistant.toolregistry.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.toolregistry.service.RegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Adapter mapping the central {@link ToolDefinition} catalog to the MCP discovery
 * surface exposed by the tool-registry MCP server.
 *
 * <p>Implements the {@code ToolDefinition} -&gt; MCP {@link Tool} mapping defined in the
 * T2' design (&sect;1.2):
 * <ul>
 *   <li>{@code name} / {@code description} 原样；</li>
 *   <li>{@code inputSchema} 来自 {@link ToolDefinition#getInputSchema()}（中心 @Tool 工具为 null，
 *       本期回退空对象 schema；MCP-backed 工具后续由 T2b 填充）；</li>
 *   <li>{@code annotations} 由 riskLevel 推导（readOnlyHint / destructiveHint /
 *       idempotentHint / openWorldHint）；</li>
 *   <li>functionalCapabilities / toolTier / tags / riskLevel 写入 {@code annotations} 的
 *       扩展字段，并双写到 MCP {@code _meta} 以兼容只解析 {@code _meta} 的客户端。</li>
 * </ul>
 *
 * <p><b>发现与执行解耦（设计硬边界）：</b>registry 的 MCP server 只暴露「发现」，
 * 不实现传递式 {@code tools/call}。因此每个中心目录工具被注册为「可发现但拒绝执行」的 MCP 工具，
 * 其 {@code call} 处理器始终返回明确错误，指引调用方经各 Agent 的 ToolGateway 适配层执行；
 * 仅 {@code search_tools} 是一个真正可调用（服务端检索）的 MCP 工具。</p>
 *
 * @see RegistryService
 */
@Component
public class McpToolRegistryAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistryAdapter.class);

    /** 服务端能力检索 MCP 工具名。 */
    public static final String SEARCH_TOOLS_NAME = "search_tools";

    private static final String PASS_THROUGH_REFUSE_MESSAGE =
            "该工具仅经 tool-registry 的 MCP 接口提供「发现」元数据，不支持传递式执行。"
            + "请经各 Agent 模块的 ToolGateway 适配层执行（registry MCP server 不实现 tools/call）。";

    private final ObjectMapper objectMapper;

    public McpToolRegistryAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== ToolDefinition -> MCP Tool ====================

    /**
     * 将单个 {@link ToolDefinition} 映射为 MCP {@link Tool}（仅发现元数据）。
     */
    public Tool toMcpTool(ToolDefinition def) {
        Map<String, Object> inputSchema = parseInputSchema(def.getInputSchema());

        ToolAnnotations annotations = ToolAnnotations.builder()
                .title(firstNonBlank(def.getDescription(), def.getName()))
                .readOnlyHint(def.isReadOnly())
                .destructiveHint(def.getRiskLevel() == ToolRiskLevel.HIGH)
                .idempotentHint(def.isReadOnly())
                .openWorldHint(false)
                .build();

        return Tool.builder()
                .name(def.getName())
                .title(firstNonBlank(def.getDescription(), def.getName()))
                .description(def.getDescription())
                .inputSchema(inputSchema)
                .annotations(annotations)
                .meta(buildMeta(def))
                .build();
    }

    /**
     * 将 {@link ToolDefinition} 集合映射为 MCP {@link Tool} 列表。
     */
    public List<Tool> toMcpTools(java.util.Collection<ToolDefinition> definitions) {
        List<Tool> tools = new ArrayList<>(definitions.size());
        for (ToolDefinition def : definitions) {
            tools.add(toMcpTool(def));
        }
        return tools;
    }

    // ==================== MCP ToolSpecification（发现型 + 检索型） ====================

    /**
     * 为中心目录工具构建「可发现但拒绝执行」的 MCP 工具规格。
     * 其 {@code call} 处理器始终拒绝传递式执行。
     */
    public McpServerFeatures.SyncToolSpecification toDiscoveryToolSpecification(ToolDefinition def) {
        Tool tool = toMcpTool(def);
        return new McpServerFeatures.SyncToolSpecification(tool, this::refusePassThroughCall);
    }

    /**
     * 构建可调用（服务端检索）的 {@code search_tools} MCP 工具规格。
     * 其 {@code call} 处理器委托 {@link RegistryService#search} 并返回匹配工具（经 {@link #toMcpTools} 映射）。
     */
    public McpServerFeatures.SyncToolSpecification searchToolsSpecification(RegistryService registryService) {
        Tool tool = Tool.builder()
                .name(SEARCH_TOOLS_NAME)
                .title("服务端能力检索工具")
                .description("按功能性能力 / 关键词 / 分层 / 状态在服务端检索工具（等价于原 /api/tools/search）")
                .inputSchema(buildSearchToolsInputSchema())
                .annotations(ToolAnnotations.builder()
                        .title("服务端能力检索工具")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, request) -> handleSearchTools(registryService, request));
    }

    // ==================== 内部：call 处理器 ====================

    private CallToolResult refusePassThroughCall(McpSyncServerExchange exchange, CallToolRequest request) {
        log.warn("[McpToolRegistryAdapter] 拒绝传递式 tools/call: tool={}", request.name());
        return CallToolResult.builder()
                .addTextContent(PASS_THROUGH_REFUSE_MESSAGE)
                .isError(true)
                .build();
    }

    private CallToolResult handleSearchTools(RegistryService registryService, CallToolRequest request) {
        try {
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

            String[] functionalCapabilities = toStringArray(args.get("functionalCapabilities"));
            String keyword = asString(args.get("keyword"));
            String matchMode = asString(args.get("matchMode"));
            String tier = asString(args.get("tier"));
            String status = asString(args.get("status"));
            int limit = asInt(args.get("limit"), 20);

            List<ToolDefinition> matched = registryService.search(
                    functionalCapabilities, keyword, matchMode, tier, status, limit);

            List<Tool> mapped = toMcpTools(matched);
            String json = objectMapper.writeValueAsString(mapped);
            return CallToolResult.builder()
                    .addTextContent(json)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            // 安全回退：检索 / 序列化任意异常均不向上抛（registry MCP server 不应因 search_tools 崩溃），
            // 返回空结果集（isError=false，JSON "[]"），由调用方经 ToolGateway 另行重试。
            log.warn("[McpToolRegistryAdapter] search_tools 执行失败，安全回退空结果: {}", e.getMessage());
            return CallToolResult.builder()
                    .addTextContent("[]")
                    .isError(false)
                    .build();
        }
    }

    // ==================== 内部：schema / meta 构造 ====================

    private Map<String, Object> buildSearchToolsInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("functionalCapabilities", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "功能性能力令牌（OR 语义），如 [\"sql-query\"]"));
        properties.put("keyword", Map.of(
                "type", "string",
                "description", "在 name/description 上包含匹配（可选）"));
        properties.put("matchMode", Map.of(
                "type", "string", "enum", List.of("OR", "AND"), "default", "OR"));
        properties.put("tier", Map.of(
                "type", "string", "enum", List.of("CORE", "SHARED", "EXTENSION")));
        properties.put("status", Map.of(
                "type", "string",
                "enum", List.of("ACTIVE", "DEPRECATED", "DISABLED", "REMOVED", "EXPERIMENTAL"),
                "default", "ACTIVE"));
        properties.put("limit", Map.of(
                "type", "integer", "default", 20));

        schema.put("properties", properties);
        schema.put("required", new ArrayList<String>());
        return schema;
    }

    private Map<String, Object> buildMeta(ToolDefinition def) {
        Map<String, Object> meta = new HashMap<>();
        // 扩展字段（annotations 兼容副通道；标准 MCP 客户端忽略未知键）
        meta.put("x-functional-capabilities", new ArrayList<>(def.getFunctionalCapabilities()));
        meta.put("x-tool-tier", def.getToolTier() == null ? null : def.getToolTier().name());
        meta.put("x-tags", def.getTags() == null ? List.of() : Arrays.asList(def.getTags()));
        meta.put("x-risk-level", def.getRiskLevel() == null ? null : def.getRiskLevel().name());
        // 双写：兼容只解析 _meta 的客户端
        meta.put("functionalCapabilities", new ArrayList<>(def.getFunctionalCapabilities()));
        meta.put("toolTier", def.getToolTier() == null ? null : def.getToolTier().name());
        return meta;
    }

    private Map<String, Object> parseInputSchema(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            // 中心 @Tool 工具本期无 inputSchema：给空对象 schema
            return emptyObjectSchema();
        }
        try {
            return objectMapper.readValue(inputSchemaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[McpToolRegistryAdapter] inputSchema 解析失败，回退空 schema: {}", e.getMessage());
            return emptyObjectSchema();
        }
    }

    private Map<String, Object> emptyObjectSchema() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("type", "object");
        empty.put("properties", new HashMap<>());
        return empty;
    }

    // ==================== 内部：参数类型转换 ====================

    @SuppressWarnings("unchecked")
    private String[] toStringArray(Object value) {
        if (value == null) {
            return new String[0];
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    result.add(o.toString());
                }
            }
            return result.toArray(new String[0]);
        }
        if (value instanceof String s) {
            return s.isBlank() ? new String[0] : s.split(",");
        }
        return new String[0];
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private int asInt(Object value, int defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        }
        return defaultVal;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
