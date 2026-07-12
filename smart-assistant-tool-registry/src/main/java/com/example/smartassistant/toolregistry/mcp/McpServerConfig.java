package com.example.smartassistant.toolregistry.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.toolregistry.service.RegistryService;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry MCP 暴露层配置（T2a）。
 * <p>
 * 启用 MCP server（WebMvc/SSE 传输，由 {@code spring.ai.mcp.server.*} 控制），将中心目录
 * （{@link RegistryService#listActiveTools()}）中的每个 ACTIVE 工具注册为「可发现但拒绝执行」
 * 的 MCP 工具规格（经 {@link McpToolRegistryAdapter} 映射），并注册唯一可真正调用的
 * {@code search_tools} 检索工具。
 * </p>
 *
 * <p><b>设计硬边界：</b>registry 的 MCP server 只做「发现」，不实现传递式 {@code tools/call}。
 * 因此中心目录工具被注册为拒绝执行的规格（其 call 处理器返回明确错误，指引调用方经各 Agent 的
 * ToolGateway 适配层执行）；仅 {@code search_tools} 是真正可调用（服务端检索）的工具。</p>
 *
 * <p>为严格保证上述边界，{@code application.yml} 中已禁用 MCP 注解扫描器
 * （{@code spring.ai.mcp.server.annotation-scanner.enabled=false}），确保仅本配置显式注册的
 * 工具规格被暴露，避免任何 {@code @Tool} Bean 被自动暴露为可调用工具。</p>
 *
 * @see McpToolRegistryAdapter
 * @see RegistryService
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    /**
     * 注册 Registry MCP server 的全部工具规格：
     * <ul>
     *   <li>中心目录每个 ACTIVE 工具 → 可发现但拒绝执行的规格；</li>
     *   <li>{@code search_tools} → 可调用（服务端检索）规格。</li>
     * </ul>
     *
     * @param registryService 注册中心服务（提供 {@code listActiveTools} / {@code search}）
     * @param adapter         ToolDefinition → MCP Tool 适配器
     * @return 工具规格列表
     */
    @Bean
    public List<McpServerFeatures.SyncToolSpecification> registryToolSpecifications(
            RegistryService registryService, McpToolRegistryAdapter adapter) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();

        List<ToolDefinition> activeTools = registryService.listActiveTools();
        for (ToolDefinition def : activeTools) {
            specs.add(adapter.toDiscoveryToolSpecification(def));
        }
        log.info("[McpServerConfig] 注册 {} 个中心目录工具为 MCP 可发现规格（拒绝执行）",
                activeTools.size());

        specs.add(adapter.searchToolsSpecification(registryService));
        log.info("[McpServerConfig] 注册可调用 MCP 工具: {}", McpToolRegistryAdapter.SEARCH_TOOLS_NAME);
        return specs;
    }
}
