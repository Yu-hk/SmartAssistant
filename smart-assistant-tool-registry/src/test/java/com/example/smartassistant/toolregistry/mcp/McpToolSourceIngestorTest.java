package com.example.smartassistant.toolregistry.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.common.gateway.tool.compat.ToolCompatibilityChecker;
import com.example.smartassistant.toolregistry.service.RegistryService;
import com.example.smartassistant.toolregistry.service.ToolManifestValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link McpToolSourceIngestor} 单元测试（T2b）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>反向映射 {@code toToolDefinition}：executeQuery / getTableSchema 字段正确
 *       （name = namespace.tool、riskLevel、capabilities 含 read-only、functionalCapabilities、
 *        toolTier、tags 去重含 mcp-source:&lt;sourceId&gt;、inputSchema 序列化为 JSON）；</li>
 *   <li>失败隔离：单源拉取抛异常不影响其他源，且不向外传播；</li>
 *   <li>幂等重同步：同源两次 syncSource，版本稳定（1.0.0）非破坏性，工具不重复；</li>
 *   <li>禁用源：enabled=false 的源不发起拉取、不注册。</li>
 * </ul>
 *
 * <p>使用真实 {@link RegistryService}（内存 Map）+ Mockito 桩 {@link ToolManifestValidator} /
 * {@link ToolCompatibilityChecker}；客户端经可注入的 {@link McpToolSourceIngestor.McpBackendClientFactory}
 * stub 替换，不依赖真实后端。</p>
 */
class McpToolSourceIngestorTest {

    private ObjectMapper objectMapper;
    private RegistryService registryService;
    private McpToolSourceConfig sourceConfig;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ToolCompatibilityChecker compatibilityChecker = mock(ToolCompatibilityChecker.class);
        ToolManifestValidator manifestValidator = mock(ToolManifestValidator.class);
        // validate 默认 no-op；新注册不触发兼容性异常（mock 返回 null → 非 BREAKING）
        doNothing().when(manifestValidator).validate(any(ToolDefinition.class));

        registryService = new RegistryService(compatibilityChecker, manifestValidator);
        sourceConfig = new McpToolSourceConfig();
    }

    // ==================== 辅助构造 ====================

    private McpToolSourceConfig.McpToolSource source(String sourceId, String namespace) {
        McpToolSourceConfig.McpToolSource s = new McpToolSourceConfig.McpToolSource();
        s.setSourceId(sourceId);
        s.setEnabled(true);
        s.setTransport("sse");
        s.setEndpoint("http://" + namespace + ":8081/mcp");
        s.setNamespace(namespace);
        s.setDefaultTier(ToolTier.SHARED);
        s.setDefaultRisk(ToolRiskLevel.READ);
        s.setSeedFunctionalCapabilities(List.of("sql-query"));
        s.setTags(new ArrayList<>(List.of(namespace, "mcp-source:sql")));
        s.setSync(new McpToolSourceConfig.Sync());
        return s;
    }

    private Tool backendTool(String name, Map<String, Object> inputSchema) {
        McpSchema.Tool.Builder b = McpSchema.Tool.builder()
                .name(name)
                .description("backend " + name);
        if (inputSchema != null) {
            b.inputSchema(inputSchema);
        }
        return b.build();
    }

    private McpToolSourceIngestor ingestor(McpToolSourceIngestor.McpBackendClientFactory factory) {
        // TaskScheduler 仅用于周期调度（测试不触发），传入 null 不影响 syncSource/toToolDefinition
        return new McpToolSourceIngestor(sourceConfig, registryService, objectMapper, factory, null);
    }

    // ==================== 反向映射 ====================

    @Test
    @DisplayName("toToolDefinition：executeQuery 反向映射字段正确")
    void mapsExecuteQuery() throws Exception {
        McpToolSourceConfig.McpToolSource src = source("sql-consumer", "consumer");

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sql", Map.of("type", "string"));
        inputSchema.put("properties", props);
        inputSchema.put("required", List.of("sql"));

        Tool backend = backendTool("executeQuery", inputSchema);
        ToolDefinition def = ingestor(s -> List.of()).toToolDefinition(backend, src);

        assertEquals("consumer.executeQuery", def.getName());
        assertEquals(ToolRiskLevel.READ, def.getRiskLevel());
        assertTrue(Arrays.asList(def.getCapabilities()).contains("read-only"));
        assertEquals(List.of("sql-query"), def.getFunctionalCapabilities());
        assertEquals(ToolTier.SHARED, def.getToolTier());
        assertTrue(Arrays.asList(def.getTags()).contains("consumer"));
        assertTrue(Arrays.asList(def.getTags()).contains("mcp-source:sql"));
        assertTrue(Arrays.asList(def.getTags()).contains("mcp-source:sql-consumer"));
        assertEquals("consumer", def.getNamespace());
        assertEquals("http://consumer:8081/mcp", def.getEndpoint());
        assertEquals(ToolStatus.ACTIVE, def.getStatus());
        assertEquals("1.0.0", def.getVersion());
        assertNotNull(def.getInputSchema());

        // inputSchema 为该 map 的 JSON 串（反序列化后与原始等价）
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(
                def.getInputSchema(), new TypeReference<Map<String, Object>>() {});
        assertEquals(inputSchema, parsed);
    }

    @Test
    @DisplayName("toToolDefinition：getTableSchema -> consumer.getTableSchema")
    void mapsGetTableSchema() {
        McpToolSourceConfig.McpToolSource src = source("sql-consumer", "consumer");
        Tool backend = backendTool("getTableSchema", null);
        ToolDefinition def = ingestor(s -> List.of()).toToolDefinition(backend, src);

        assertEquals("consumer.getTableSchema", def.getName());
        assertEquals(ToolRiskLevel.READ, def.getRiskLevel());
        assertTrue(Arrays.asList(def.getCapabilities()).contains("read-only"));
        assertEquals(List.of("sql-query"), def.getFunctionalCapabilities());
        assertEquals(ToolTier.SHARED, def.getToolTier());
        assertEquals("consumer", def.getNamespace());
        assertEquals("1.0.0", def.getVersion());
        assertNull(def.getInputSchema()); // 后端无 inputSchema -> null
    }

    // ==================== 失败隔离 ====================

    @Test
    @DisplayName("失败隔离：源 A 拉取抛异常不影响源 B，且异常不向外传播")
    void failureIsolationBetweenSources() {
        McpToolSourceConfig.McpToolSource srcA = source("A", "consumer");
        McpToolSourceConfig.McpToolSource srcB = source("B", "travel");

        // 源 A 抛异常，源 B 返回工具
        McpToolSourceIngestor.McpBackendClientFactory factory = s -> {
            if ("A".equals(s.getSourceId())) {
                throw new RuntimeException("backend down");
            }
            return List.of(backendTool("executeQuery", null));
        };
        McpToolSourceIngestor ingestor = ingestor(factory);

        // 二者均不应向外抛（失败隔离）
        ingestor.syncSource(srcA);
        ingestor.syncSource(srcB);

        // 源 B 的工具被注册（travel.executeQuery），源 A 未注册
        assertTrue(registryService.isRegistered("travel.executeQuery"),
                "源 B 的工具应被注册");
        assertFalse(registryService.isRegistered("consumer.executeQuery"),
                "源 A 拉取失败，不应注册任何工具");
    }

    // ==================== 幂等重同步 ====================

    @Test
    @DisplayName("幂等重同步：同源两次 syncSource，版本稳定非破坏性，工具不重复")
    void idempotentResync() {
        McpToolSourceConfig.McpToolSource src = source("sql-consumer", "consumer");
        McpToolSourceIngestor.McpBackendClientFactory factory = s -> List.of(
                backendTool("executeQuery", null),
                backendTool("getTableSchema", null));
        McpToolSourceIngestor ingestor = ingestor(factory);

        ingestor.syncSource(src);
        ingestor.syncSource(src); // 第二次：版本稳定（1.0.0）应非破坏性

        assertTrue(registryService.isRegistered("consumer.executeQuery"));
        assertTrue(registryService.isRegistered("consumer.getTableSchema"));
        assertEquals(2, registryService.size()); // 按 name 覆盖，不重复
    }

    // ==================== 禁用源 ====================

    @Test
    @DisplayName("禁用源：enabled=false 不发起拉取、不注册任何工具")
    void disabledSourceSkipsFetch() {
        McpToolSourceConfig.McpToolSource src = source("sql-consumer", "consumer");
        src.setEnabled(false);

        // 若误拉取应直接失败（证明从未调用 fetch）
        McpToolSourceIngestor.McpBackendClientFactory factory = s -> {
            throw new AssertionError("disabled source must not trigger fetch");
        };
        McpToolSourceIngestor ingestor = ingestor(factory);

        ingestor.syncSource(src);

        assertFalse(registryService.isRegistered("consumer.executeQuery"));
    }
}
