package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolGatewayToolCallback;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 工具回调工厂（T2c-3）：由中心目录 {@link ToolDefinition} 统一生成经 {@link ToolGateway} 治理包裹的
 * {@link ToolCallback}。
 *
 * <ul>
 *   <li><b>MCP-backed 分支</b>（{@link #isMcpBacked} 为真，即 {@code endpoint + inputSchema} 均非空）：
 *       构造内部 {@link McpBackendToolCallback} 转发后端，并以 <b>def 感知重载</b>包裹
 *       （{@code new ToolGatewayToolCallback(backendCb, gateway, null, def)}），确保 P0 治理用中心真实定义；</li>
 *   <li><b>center / 本地分支</b>：使用调用方传入的本地 {@code @Tool} 回调（或兜底），同样以 def 感知重载包裹。</li>
 * </ul>
 *
 * <p><b>P0 治理链 100% 不可破：</b>所有 MCP-backed 工具的 {@code tools/call} 必经由
 * {@link ToolGatewayToolCallback}（def 感知重载）→ {@link ToolGateway}；registry MCP server 不实现传递式
 * {@code tools/call}（沿用 T2a 边界）。</p>
 *
 * @see ToolGatewayToolCallback
 * @see McpBackendToolExecutor
 */
public class McpToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(McpToolCallbackFactory.class);

    private final ToolRegistry toolRegistry;
    private final ToolGateway gateway;
    private final McpBackendToolExecutor backendExecutor;

    public McpToolCallbackFactory(ToolRegistry toolRegistry, ToolGateway gateway,
                                  McpBackendToolExecutor backendExecutor) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.backendExecutor = Objects.requireNonNull(backendExecutor, "backendExecutor");
    }

    /**
     * 由中心目录定义生成经治理包裹的回调。
     *
     * @param def           中心目录完整定义（MCP-backed 必含 endpoint + inputSchema）
     * @param localCallback 本地 {@code @Tool} 回调（center 分支传入；MCP-backed 分支可传 null）
     * @return 经 {@link ToolGatewayToolCallback} 包裹的 {@link ToolCallback}
     */
    public ToolCallback create(ToolDefinition def, @Nullable ToolCallback localCallback) {
        Objects.requireNonNull(def, "def must not be null");
        if (isMcpBacked(def)) {
            // MCP-backed：转发后端，def 感知重载包裹（P0 治理用中心真实定义）
            McpBackendToolCallback backendCb = new McpBackendToolCallback(def, backendExecutor);
            return new ToolGatewayToolCallback(backendCb, gateway, null, def);
        }
        // 本地工具回调（从注册/发现的 localCallback 或 def 构建），def 感知重载包裹
        ToolCallback cb = (localCallback != null) ? localCallback : buildLocalFromDef(def);
        return new ToolGatewayToolCallback(cb, gateway, null, def);
    }

    /**
     * MCP-backed 判定（统一工具类型识别）：
     * {@code endpoint} 与 {@code inputSchema} 均非空（与 T2b 写入对齐；中心 {@code @Tool} 工具二者为 null）。
     */
    public static boolean isMcpBacked(ToolDefinition def) {
        return def != null && def.getEndpoint() != null && def.getInputSchema() != null;
    }

    private ToolCallback buildLocalFromDef(ToolDefinition def) {
        // 兜底：center 工具必须提供 localCallback（本地 @Tool 回调）。正常路径不会触发。
        throw new UnsupportedOperationException(
                "center 工具必须由调用方提供 localCallback（本地 @Tool 回调），def=" + def.getName());
    }

    /**
     * MCP-backed 工具回调：{@link #call(String)} 直接转发到后端 MCP server（外层已由 {@link ToolGateway} 包裹）。
     * 实现 Spring AI {@link ToolCallback} 接口。
     */
    static class McpBackendToolCallback implements ToolCallback {
        private final ToolDefinition def;
        private final McpBackendToolExecutor executor;

        McpBackendToolCallback(ToolDefinition def, McpBackendToolExecutor executor) {
            this.def = def;
            this.executor = executor;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(def.getName())
                    .description(def.getDescription() != null ? def.getDescription() : "")
                    .inputSchema(def.getInputSchema() != null ? def.getInputSchema() : "{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            try {
                return executor.execute(def, toolInput);
            } catch (Exception e) {
                throw new RuntimeException("MCP-backed 工具执行失败: " + def.getName(), e);
            }
        }
    }
}
