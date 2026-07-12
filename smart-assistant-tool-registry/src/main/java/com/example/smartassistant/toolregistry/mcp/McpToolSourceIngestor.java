package com.example.smartassistant.toolregistry.mcp;

import com.example.smartassistant.common.gateway.tool.ToolCapability;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.toolregistry.service.RegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将后端 MCP server（如 consumer / order 的 {@code executeQuery} / {@code getTableSchema}）
 * 同步进中心目录，修复"数据分裂 #4"。
 *
 * <p>T2b 仅做 registry 侧「源接入 + 目录注册」，不实现 {@code tools/call} 转发（那是 T2c）。
 * 本组件负责：把后端 MCP {@link Tool} 反向映射为 {@link ToolDefinition}，并经
 * {@link RegistryService#register} 注册。</p>
 *
 * <p><b>失败隔离（设计硬边界）：</b>单源同步失败仅 WARN + 跳过，绝不向外抛；
 * registry 即使后端全挂也必须正常启动。初始同步在独立线程执行，避免阻塞上下文刷新。</p>
 *
 * <p><b>可测性：</b>「创建 SSE client 并拉取工具」封装为可注入的
 * {@link McpBackendClientFactory}（生产由 {@link McpToolSourceConfig#mcpBackendClientFactory()} 提供真实
 * SSE 实现；测试注入返回固定工具列表的 stub，从而不依赖真实后端）。</p>
 *
 * @see McpToolSourceConfig
 * @see RegistryService
 */
@Component
public class McpToolSourceIngestor {

    private static final Logger log = LoggerFactory.getLogger(McpToolSourceIngestor.class);

    private final McpToolSourceConfig sourceConfig;
    private final RegistryService registryService;
    private final ObjectMapper objectMapper;
    private final McpBackendClientFactory clientFactory;
    private final TaskScheduler taskScheduler;

    /**
     * 构造器注入（Spring 单构造器自动装配）。
     *
     * @param sourceConfig     MCP 源配置（{@code @ConfigurationProperties} 绑定）
     * @param registryService 中心注册服务（接收注册）
     * @param objectMapper    JSON 序列化器（inputSchema Map -> JSON String）
     * @param clientFactory   后端 MCP 客户端工厂（可注入替换为 stub，便于测试）
     * @param taskScheduler   周期同步调度器（{@link McpToolSourceConfig#mcpToolSourceScheduler()}）
     */
    @Autowired
    public McpToolSourceIngestor(McpToolSourceConfig sourceConfig,
                                  RegistryService registryService,
                                  ObjectMapper objectMapper,
                                  McpBackendClientFactory clientFactory,
                                  TaskScheduler taskScheduler) {
        this.sourceConfig = sourceConfig;
        this.registryService = registryService;
        this.objectMapper = objectMapper;
        this.clientFactory = clientFactory;
        this.taskScheduler = taskScheduler;
    }

    // ==================== 可注入的客户端工厂（可测性关键） ====================

    /**
     * 后端 MCP 客户端工厂：封装「创建 SSE client 并 {@code listTools()}」的可替换行为。
     * <p>生产环境由 {@link McpToolSourceConfig#mcpBackendClientFactory()} 提供真实 SSE 实现；
     * 测试中可注入返回固定 {@link Tool} 列表的 stub，从而不依赖真实后端。</p>
     */
    @FunctionalInterface
    public interface McpBackendClientFactory {
        /**
         * 拉取指定源暴露的后端工具列表。
         *
         * @param source 工具源配置（提供 endpoint 等）
         * @return 后端工具列表（不应返回 {@code null}）
         * @throws Exception 任何拉取 / 连接异常
         */
        List<Tool> fetch(McpToolSourceConfig.McpToolSource source) throws Exception;
    }

    // ==================== 反向映射：McpSchema.Tool -> ToolDefinition ====================

    /**
     * 将后端 MCP {@link Tool} 反向映射为注册中心 {@link ToolDefinition}。
     * <p>命名：{@code <namespace>.<backendToolName>}（如 "consumer.executeQuery"）。
     * 仅设置注册所需元数据；execution endpoint 持久化供后续 T2c 转发使用，但 T2b 不做转发。</p>
     *
     * @param backendTool 后端 MCP Tool
     * @param source      源配置（提供命名空间 / 风险 / 分层 / 种子能力等）
     * @return 中心目录 {@link ToolDefinition}
     */
    public ToolDefinition toToolDefinition(Tool backendTool, McpToolSourceConfig.McpToolSource source) {
        Objects.requireNonNull(backendTool, "backendTool must not be null");
        Objects.requireNonNull(source, "source must not be null");

        // namespace：缺失时回退 sourceId，避免 "null." 工具名
        String namespace = source.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            namespace = (source.getSourceId() != null) ? source.getSourceId() : "";
        }

        // inputSchema：Map -> JSON String；null/空则 null。
        // 注：MCP SDK 的 Tool.Builder 未显式设置 inputSchema 时默认填 {"type":"object"}，
        // 这与 T2a 适配器「def.inputSchema 为 null 时回退空对象 schema」互逆；
        // 故仅含 type=object、无实际 properties 的 schema 视为「无入参」→ null。
        String inputSchemaJson = null;
        Map<String, Object> inputSchema = backendTool.inputSchema();
        if (inputSchema != null && !inputSchema.isEmpty()) {
            Object props = inputSchema.get("properties");
            boolean hasRealProps = props instanceof Map && !((Map<?, ?>) props).isEmpty();
            if (hasRealProps) {
                try {
                    inputSchemaJson = objectMapper.writeValueAsString(inputSchema);
                } catch (Exception e) {
                    log.warn("[McpToolSourceIngestor] inputSchema 序列化失败，置 null: {}", e.getMessage());
                    inputSchemaJson = null;
                }
            }
        }

        // capabilities 由 riskLevel 推导
        String[] capabilities = capabilitiesForRisk(source.getDefaultRisk());

        // tags：基础标签 + 确保含 mcp-source:<sourceId>（去重）
        Set<String> tagSet = new LinkedHashSet<>();
        if (source.getTags() != null) {
            tagSet.addAll(source.getTags());
        }
        if (source.getSourceId() != null) {
            tagSet.add("mcp-source:" + source.getSourceId());
        }

        return ToolDefinition.builder()
                .name(namespace + "." + backendTool.name())
                .description(backendTool.description() != null ? backendTool.description() : "")
                .riskLevel(source.getDefaultRisk())
                .capabilities(capabilities)
                .functionalCapabilities(source.getSeedFunctionalCapabilities())
                .toolTier(source.getDefaultTier())
                .tags(tagSet.toArray(new String[0]))
                .namespace(namespace)
                .status(ToolStatus.ACTIVE)
                .endpoint(source.getEndpoint())
                .version("1.0.0") // 稳定版本号：重复同步非破坏性
                .needsApproval(false)
                .maxRetries(0)
                .rateLimit(0)
                .scopes(new String[0])
                .outputSchema(null)
                .inputSchema(inputSchemaJson)
                .build();
    }

    /**
     * 由风险等级推导能力标签。
     * <ul>
     *   <li>READ → ["read-only"]</li>
     *   <li>HIGH → ["mutate-state", "payment"]</li>
     *   <li>其他 → ["mutate-state"]</li>
     * </ul>
     */
    private static String[] capabilitiesForRisk(ToolRiskLevel level) {
        if (level == ToolRiskLevel.READ) {
            return new String[]{ToolCapability.READ_ONLY.getValue()};
        }
        if (level == ToolRiskLevel.HIGH) {
            return new String[]{ToolCapability.MUTATE_STATE.getValue(),
                    ToolCapability.PAYMENT.getValue()};
        }
        return new String[]{ToolCapability.MUTATE_STATE.getValue()};
    }

    // ==================== 单源同步（失败隔离） ====================

    /**
     * 同步单个工具源（失败隔离）。
     * <p>关闭的源直接跳过；拉取或注册任意异常仅 WARN + 记录，绝不向外抛。</p>
     *
     * @param source 工具源配置
     */
    public void syncSource(McpToolSourceConfig.McpToolSource source) {
        if (source == null || !source.isEnabled()) {
            return;
        }
        try {
            List<Tool> backendTools = clientFactory.fetch(source);
            if (backendTools == null || backendTools.isEmpty()) {
                log.info("[McpToolSourceIngestor] 源 {} 返回 0 个工具，跳过注册", source.getSourceId());
                return;
            }
            int registered = 0;
            for (Tool backendTool : backendTools) {
                try {
                    ToolDefinition def = toToolDefinition(backendTool, source);
                    registryService.register(def);
                    registered++;
                } catch (Exception e) {
                    log.warn("[McpToolSourceIngestor] 源 {} 单工具注册失败 name={}: {}",
                            source.getSourceId(), backendTool.name(), e.getMessage());
                }
            }
            log.info("[McpToolSourceIngestor] 源 {} 同步完成：{} 个工具已注册", source.getSourceId(), registered);
        } catch (Exception e) {
            log.warn("[McpToolSourceIngestor] 源 {} 同步失败（失败隔离，不影响其他源与 registry 启动）：{}",
                    source.getSourceId(), e.getMessage());
        }
    }

    // ==================== 初始同步（启动，独立线程） ====================

    /**
     * 应用上下文刷新后执行初始同步：遍历所有 enabled 源，并注册每源的周期调度。
     * <p>整体 try-catch，registry 即使后端全挂也必须正常启动。初始同步在独立线程执行，
     * 避免阻塞 Spring 上下文刷新 / 应用启动。</p>
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        try {
            // 初始同步：独立线程，避免阻塞上下文刷新
            new Thread(this::syncAllEnabled, "mcp-tool-source-init").start();
            // 周期同步：为每个 enabled 源注册独立 CronTrigger
            registerPeriodicSchedules();
        } catch (Exception e) {
            // 整体兜底：任何意外都不应影响 registry
            log.warn("[McpToolSourceIngestor] 初始/周期调度注册异常（已隔离）：{}", e.getMessage());
        }
    }

    /** 初始同步：遍历所有源（enabled 守卫在 syncSource 内部）。 */
    private void syncAllEnabled() {
        try {
            List<McpToolSourceConfig.McpToolSource> sources = sourceConfig.getMcpSources();
            if (sources == null) {
                return;
            }
            for (McpToolSourceConfig.McpToolSource source : sources) {
                if (source == null) {
                    continue;
                }
                syncSource(source);
            }
        } catch (Exception e) {
            log.warn("[McpToolSourceIngestor] 初始同步整体异常（已隔离）：{}", e.getMessage());
        }
    }

    /** 为每个 enabled 源注册独立 CronTrigger（每源独立超时/周期，互不影响）。 */
    private void registerPeriodicSchedules() {
        List<McpToolSourceConfig.McpToolSource> sources = sourceConfig.getMcpSources();
        if (sources == null) {
            return;
        }
        for (McpToolSourceConfig.McpToolSource source : sources) {
            if (source == null || !source.isEnabled()) {
                continue;
            }
            String cron = (source.getSync() != null && source.getSync().getCron() != null)
                    ? source.getSync().getCron() : "0 */5 * * * ?";
            McpToolSourceConfig.McpToolSource src = source; // effectively final
            try {
                taskScheduler.schedule(() -> syncSource(src), new CronTrigger(cron));
            } catch (Exception e) {
                log.warn("[McpToolSourceIngestor] 源 {} 周期调度注册失败（已隔离）：{}",
                        source.getSourceId(), e.getMessage());
            }
        }
    }
}
