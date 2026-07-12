package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 连接 registry MCP server 的发现客户端（T2c-1）。
 *
 * <p>职责：把 registry 暴露的 MCP {@link Tool}（含 T2a 写入 {@code _meta} 的扩展字段）解析回中心目录
 * {@link ToolDefinition}。提供两条发现路径：
 * <ul>
 *   <li>{@link #listTools(Set)} — 调 MCP {@code tools/list}，客户端按 tag 过滤（MCP 协议无 tag 参数）；</li>
 *   <li>{@link #searchTools(McpSearchFilter)} — 调 registry 提供的 {@code search_tools} 能力检索。</li>
 * </ul>
 *
 * <p><b>endpoint 缺口处理（设计 §1.3 / §7.3）：</b>纯 MCP 发现的 {@code ToolDefinition.endpoint}
 * 为 null（T2a 未写入 {@code _meta}），无法直连后端。该缺口由上层
 * {@code ToolRegistryClient.mergeByEndpoint} 按 name 从 REST 返回的权威执行信息叠加解决，本类不越界改 T2a。</p>
 *
 * <p><b>可测性：</b>{@link McpRegistryClientFactory} 可注入（生产 = SSE 真实 client；测试 = stub），
 * 本类不依赖真实 registry。连接懒加载（首次 {@code listTools}/{@code searchTools} 才建立），
 * {@link #close()} 显式关闭（{@code @PreDestroy}）。发现失败向上抛受检异常，由 {@code ToolRegistryClient}
 * 捕获降级，本类不吞。</p>
 *
 * @see McpRegistryClientFactory
 */
public class McpRegistryDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(McpRegistryDiscoveryClient.class);

    /** registry MCP server 暴露的检索工具名（与 T2a {@code McpToolRegistryAdapter.SEARCH_TOOLS_NAME} 对齐）。 */
    static final String SEARCH_TOOLS = "search_tools";

    private final McpRegistryClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final ToolRegistryProperties properties;
    private final boolean enabled;

    /** 懒加载的 registry MCP client（单连接，SDK SSE transport 自带重连）。 */
    private McpSyncClient registryClient;

    public McpRegistryDiscoveryClient(McpRegistryClientFactory clientFactory,
                                      ObjectMapper objectMapper,
                                      ToolRegistryProperties properties) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.enabled = properties.isMcpDiscoveryEnabled();
    }

    /**
     * 列出 registry 暴露的全部工具定义，按 {@code tags} 客户端过滤（MCP {@code tools/list} 无 tag 参数）。
     *
     * @param tags 过滤标签集合（空 / null = 不过滤，返回全部）
     * @return 解析后的工具定义列表（endpoint 为 null，待上层叠加）
     * @throws Exception 任意连接 / 列出异常（由上层降级，本类不吞）
     */
    public List<ToolDefinition> listTools(Set<String> tags) throws Exception {
        McpSyncClient client = getOrCreateClient();
        ListToolsResult result = client.listTools();
        List<Tool> tools = (result != null && result.tools() != null) ? result.tools() : List.of();
        List<ToolDefinition> defs = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            ToolDefinition def = toToolDefinition(tool);
            if (tags == null || tags.isEmpty() || matchesAnyTag(def, tags)) {
                defs.add(def);
            }
        }
        log.debug("[McpRegistryDiscoveryClient] listTools: 发现 {} 个工具（按 tags={} 过滤后 {} 个）",
                tools.size(), tags, defs.size());
        return defs;
    }

    /**
     * 调 registry 的 {@code search_tools} 做能力检索，解析返回的 JSON 数组为 {@link ToolDefinition} 列表。
     *
     * @param filter 检索条件（functionalCapabilities / keyword / matchMode / tier / status / limit）
     * @return 匹配的工具定义列表（解析失败安全回退空列表）
     * @throws Exception 任意连接 / 调用异常（由上层降级，本类不吞）
     */
    public List<ToolDefinition> searchTools(McpSearchFilter filter) throws Exception {
        McpSyncClient client = getOrCreateClient();
        Map<String, Object> args = filter.toArgs();
        CallToolRequest request = CallToolRequest.builder().name(SEARCH_TOOLS).arguments(args).build();
        CallToolResult result = client.callTool(request);
        return parseSearchResult(result);
    }

    /**
     * 将 registry 返回的 {@link Tool} 解析回中心目录 {@link ToolDefinition}。
     *
     * <p>权威来源为 {@code tool.meta()}（T2a 写入的 {@code x-*} 扩展字段 + 双写
     * {@code functionalCapabilities}/{@code toolTier}）；{@code riskLevel} 优先取
     * {@code x-risk-level}，缺失时由 {@code annotations().readOnlyHint()} 推导 READ。</p>
     *
     * <p>包级可见，便于单测直接验证映射逻辑。</p>
     */
    ToolDefinition toToolDefinition(Tool tool) {
        String name = tool.name();
        String description = tool.description();
        Map<String, Object> inputSchemaMap = tool.inputSchema();
        String inputSchemaJson = toInputSchemaJson(inputSchemaMap);
        Boolean readOnlyHint = (tool.annotations() != null) ? tool.annotations().readOnlyHint() : null;
        Map<String, Object> meta = tool.meta();
        return buildDefinition(name, description, inputSchemaJson, readOnlyHint, meta);
    }

    // ==================== 内部：映射 ====================

    private ToolDefinition buildDefinition(String name, String description, String inputSchemaJson,
                                            Boolean readOnlyHint, Map<String, Object> meta) {
        List<String> functionalCapabilities = parseMetaList(meta, "x-functional-capabilities", "functionalCapabilities");
        String tierStr = parseMetaString(meta, "x-tool-tier", "toolTier");
        ToolTier tier = parseTier(tierStr);
        List<String> tags = parseMetaList(meta, "x-tags", null);
        String riskStr = parseMetaString(meta, "x-risk-level", null);
        ToolRiskLevel riskLevel = parseRisk(riskStr);
        if (riskLevel == null && Boolean.TRUE.equals(readOnlyHint)) {
            riskLevel = ToolRiskLevel.READ;
        }
        if (riskLevel == null) {
            riskLevel = ToolRiskLevel.READ;
        }
        return ToolDefinition.builder()
                .name(name)
                .description(description != null ? description : "")
                .inputSchema(inputSchemaJson)
                .functionalCapabilities(functionalCapabilities)
                .toolTier(tier)
                .tags(tags != null ? tags.toArray(new String[0]) : new String[0])
                .riskLevel(riskLevel)
                .status(ToolStatus.ACTIVE)
                .namespace(deriveNamespace(name))
                .endpoint(null) // 由 ToolRegistryClient.mergeByEndpoint 从 REST 叠加
                .version("1.0.0")
                .needsApproval(false)
                .maxRetries(0)
                .rateLimit(0)
                .scopes(new String[0])
                .build();
    }

    private String deriveNamespace(String name) {
        if (name == null) {
            return null;
        }
        int idx = name.indexOf('.');
        return idx > 0 ? name.substring(0, idx) : null;
    }

    private String toInputSchemaJson(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(inputSchema);
        } catch (Exception e) {
            log.warn("[McpRegistryDiscoveryClient] inputSchema 序列化失败，置 null: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 内部：search_tools 结果解析 ====================

    private List<ToolDefinition> parseSearchResult(CallToolResult result) {
        if (result == null || result.content() == null) {
            return List.of();
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc) {
                sb.append(tc.text());
            }
        }
        String json = sb.toString().trim();
        if (json.isEmpty()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<ToolDefinition> defs = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) {
                ToolDefinition def = toToolDefinitionFromMap(m);
                if (def != null) {
                    defs.add(def);
                }
            }
            return defs;
        } catch (Exception e) {
            // 安全回退：解析任意异常均不向上抛（registry search_tools 设计即如此），返回空列表由上层兜底
            log.warn("[McpRegistryDiscoveryClient] search_tools 结果解析失败（安全回退空列表）: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private ToolDefinition toToolDefinitionFromMap(Map<String, Object> toolMap) {
        if (toolMap == null) {
            return null;
        }
        String name = asString(toolMap.get("name"));
        String description = asString(toolMap.get("description"));
        Map<String, Object> inputSchemaMap = asMap(toolMap.get("inputSchema"));
        String inputSchemaJson = toInputSchemaJson(inputSchemaMap);
        Boolean readOnlyHint = parseReadOnlyHint(asMap(toolMap.get("annotations")));
        Map<String, Object> meta = asMap(toolMap.get("meta"));
        return buildDefinition(name, description, inputSchemaJson, readOnlyHint, meta);
    }

    @SuppressWarnings("unchecked")
    private Boolean parseReadOnlyHint(Map<String, Object> annMap) {
        if (annMap == null) {
            return null;
        }
        return asBoolean(annMap.get("readOnlyHint"));
    }

    // ==================== 内部：meta 解析 ====================

    @SuppressWarnings("unchecked")
    private List<String> parseMetaList(Map<String, Object> meta, String primary, String fallback) {
        if (meta == null) {
            return List.of();
        }
        Object v = meta.get(primary);
        if (v == null && fallback != null) {
            v = meta.get(fallback);
        }
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        return List.of();
    }

    private String parseMetaString(Map<String, Object> meta, String primary, String fallback) {
        if (meta == null) {
            return null;
        }
        Object v = meta.get(primary);
        if (v == null && fallback != null) {
            v = meta.get(fallback);
        }
        return v == null ? null : v.toString();
    }

    private ToolTier parseTier(String s) {
        if (s == null || s.isBlank()) {
            return ToolTier.SHARED;
        }
        try {
            return ToolTier.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return ToolTier.SHARED;
        }
    }

    private ToolRiskLevel parseRisk(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return ToolRiskLevel.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== 内部：工具方法 ====================

    private boolean matchesAnyTag(ToolDefinition def, Set<String> tags) {
        if (def.getTags() == null) {
            return false;
        }
        for (String t : def.getTags()) {
            if (tags.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Boolean asBoolean(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(o.toString());
    }

    // ==================== 连接生命周期 ====================

    private McpSyncClient getOrCreateClient() throws Exception {
        McpSyncClient client = this.registryClient;
        if (client == null) {
            synchronized (this) {
                client = this.registryClient;
                if (client == null) {
                    this.registryClient = clientFactory.createClient();
                    client = this.registryClient;
                }
            }
        }
        return client;
    }

    /**
     * 关闭 registry MCP client（{@code @PreDestroy} 生命周期）。
     */
    @PreDestroy
    public void close() {
        if (registryClient != null) {
            try {
                registryClient.close();
            } catch (Exception e) {
                log.debug("[McpRegistryDiscoveryClient] 关闭 registry MCP client 异常: {}", e.getMessage());
            }
            registryClient = null;
        }
    }

    // ==================== search_tools 检索过滤条件 ====================

    /**
     * {@code search_tools} 检索过滤条件，序列化为 callTool 入参 Map。
     * 字段与 T2a {@code McpToolRegistryAdapter.buildSearchToolsInputSchema()} 对齐。
     */
    public static class McpSearchFilter {
        private List<String> functionalCapabilities = List.of();
        private String keyword;
        private String matchMode = "OR";
        private String tier;
        private String status = "ACTIVE";
        private int limit = 20;

        public McpSearchFilter functionalCapabilities(List<String> v) {
            this.functionalCapabilities = v != null ? v : List.of();
            return this;
        }

        public McpSearchFilter keyword(String v) {
            this.keyword = v;
            return this;
        }

        public McpSearchFilter matchMode(String v) {
            this.matchMode = v;
            return this;
        }

        public McpSearchFilter tier(String v) {
            this.tier = v;
            return this;
        }

        public McpSearchFilter status(String v) {
            this.status = v;
            return this;
        }

        public McpSearchFilter limit(int v) {
            this.limit = v;
            return this;
        }

        public List<String> getFunctionalCapabilities() {
            return functionalCapabilities;
        }

        public String getKeyword() {
            return keyword;
        }

        public String getMatchMode() {
            return matchMode;
        }

        public String getTier() {
            return tier;
        }

        public String getStatus() {
            return status;
        }

        public int getLimit() {
            return limit;
        }

        /** 序列化为 {@code search_tools} 的入参 Map。 */
        public Map<String, Object> toArgs() {
            Map<String, Object> args = new LinkedHashMap<>();
            if (functionalCapabilities != null && !functionalCapabilities.isEmpty()) {
                args.put("functionalCapabilities", new ArrayList<>(functionalCapabilities));
            }
            if (keyword != null) {
                args.put("keyword", keyword);
            }
            if (matchMode != null) {
                args.put("matchMode", matchMode);
            }
            if (tier != null) {
                args.put("tier", tier);
            }
            if (status != null) {
                args.put("status", status);
            }
            args.put("limit", limit);
            return args;
        }
    }
}
