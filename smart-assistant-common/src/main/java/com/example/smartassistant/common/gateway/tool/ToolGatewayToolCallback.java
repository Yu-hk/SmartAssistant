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
 * ToolGateway，导致上述治理从未生效。所有经 {@code SpringToolProvider} /
 * {@code ToolRegistryClient} 返回的工具回调都应先经本装饰器包裹。</p>
 *
 * <p>实现说明：{@link #getToolDefinition()} 必须返回 Spring AI 的
 * {@link ToolDefinition}（{@code ToolCallback} 接口约定），故装饰器本身不持有
 * 项目级 {@code ToolDefinition}。{@link #call(String)} 仅取工具名，交由
 * {@link ToolGateway#execute(String, ToolExecutor, String, String)} 字符串重载，
 * 由网关从本地 {@code ToolRegistry} 解析出项目级定义并执行治理链（查不到则按
 * CORE/ACTIVE 兜底，符合"CORE 常驻不可禁用"的设计约定）。</p>
 *
 * @author Yu-hk
 * @since 2026-07-11
 */
public class ToolGatewayToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayToolCallback.class);

    private final ToolCallback delegate;
    private final ToolGateway gateway;
    private final String scope;

    public ToolGatewayToolCallback(ToolCallback delegate, ToolGateway gateway, String scope) {
        this.delegate = delegate;
        this.gateway = gateway;
        this.scope = scope;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        return gateway.execute(toolName, () -> delegate.call(toolInput), scope, null);
    }
}
