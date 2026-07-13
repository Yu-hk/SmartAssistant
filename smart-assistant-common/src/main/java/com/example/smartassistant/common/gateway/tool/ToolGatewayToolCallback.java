package com.example.smartassistant.common.gateway.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具执行网关装饰器（P0 接线点）。
 * <p>
 * 包装原始的 {@link ToolCallback}，将其 {@code call()} 路由到 {@link ToolGateway}，
 * 使工具调用真正经过统一的治理链：
 * <ul>
 *   <li>status 拦截（DISABLED / REMOVED 拒绝执行）</li>
 *   <li>审批钩子（needsApproval 经 {@code ApprovalHook} 抛 APPROVAL_REJECTED）</li>
 *   <li>scope / tag 鉴权、熔断、限流、超时、幂等、审计</li>
 * </ul>
 *
 * <p>此前 {@code SmartReActAgent} 直接调用 {@code ToolCallback.call()}，完全绕过
 * ToolGateway，导致上述治理从未生效。所有通过 {@code ToolRegistryClient}
 * 返回的工具回调都应先经本装饰器包裹。</p>
 *
 * <p>实现说明：{@link #getToolDefinition()} 必须返回 Spring AI 的
 * {@link ToolDefinition}（{@code ToolCallback} 接口约定），故装饰器本身不持有
 * 项目级 {@code ToolDefinition}。{@link #call(String)} 仅取工具名，交由
 * {@link ToolGateway#execute(String, ToolExecutor, String, String)} 字符串重载，
 * 由网关从本地 {@code ToolRegistry} 解析出项目级定义并执行治理链（查不到则按
 * CORE/ACTIVE 兜底，符合"CORE 常驻不可禁用"的设计约定）。</p>
 *
 * <p><b>T2c def 感知重载：</b>新增 {@code (delegate, gateway, scope, projectDef)} 构造，
 * 允许调用方传入中心目录解析出的完整 {@link com.example.smartassistant.common.gateway.tool.ToolDefinition}
 * （含 DISABLED/REMOVED 状态、rateLimit、scopes 等），直接走
 * {@link ToolGateway#execute(ToolDefinition, ToolExecutor, String, String)} 重载，
 * 避免 MCP-backed 工具退化成 CORE 默认定义而绕过中心治理（P0 不可破）。
 * 旧构造 {@code (delegate, gateway, scope)} 行为完全不变（projectDef = null）。</p>
 *
 * @author Yu-hk
 * @since 2026-07-11
 */
public class ToolGatewayToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayToolCallback.class);

    private final ToolCallback delegate;
    private final ToolGateway gateway;
    private final String scope;
    /** T2c：中心目录完整定义（MCP-backed 工具治理用）；null 时走字符串重载（center 路径不变）。 */
    private final com.example.smartassistant.common.gateway.tool.ToolDefinition projectDef;

    /** 旧构造：center 工具路径，projectDef = null（走字符串重载，行为不变）。 */
    public ToolGatewayToolCallback(ToolCallback delegate, ToolGateway gateway, String scope) {
        this(delegate, gateway, scope, null);
    }

    /**
     * T2c def 感知重载：传入中心目录完整 {@code ToolDefinition}，直接驱动 P0 治理链。
     *
     * @param delegate   被装饰的原始 ToolCallback
     * @param gateway    工具执行网关
     * @param scope      调用方 Scope（null = 不检查权限）
     * @param projectDef 中心目录解析出的完整工具定义（MCP-backed 工具必传；center 工具传 null）
     */
    public ToolGatewayToolCallback(ToolCallback delegate, ToolGateway gateway, String scope,
                                   com.example.smartassistant.common.gateway.tool.ToolDefinition projectDef) {
        this.delegate = delegate;
        this.gateway = gateway;
        this.scope = scope;
        this.projectDef = projectDef;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        if (projectDef != null) {
            // T2c：用真实 def 跑治理链（status 拦截 / 审批 / 限流 / 超时 / 审计 全生效）
            return gateway.execute(projectDef, () -> delegate.call(toolInput), scope, null);
        }
        String toolName = delegate.getToolDefinition().name();
        return gateway.execute(toolName, () -> delegate.call(toolInput), scope, null);
    }
}
