package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.hook.ApprovalHook;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ToolGateway} 治理链测试。
 * <p>覆盖 P0 修复点：status 拦截（DISABLED/REMOVED）+ 审批钩子（needsApproval）。</p>
 */
class ToolGatewayTest {

    private ToolGateway gatewayWithApproval() {
        ToolRegistry registry = new ToolRegistry();
        return new ToolGateway(registry, List.of(new ApprovalHook()));
    }

    @Test
    void activeToolWithoutApprovalShouldExecute() {
        ToolGateway gw = gatewayWithApproval();
        ToolDefinition def = ToolDefinition.read("calc", "计算");
        String result = gw.execute(def, () -> "ok", null, null);
        assertEquals("ok", result);
    }

    @Test
    void disabledToolShouldBeRejected() {
        ToolGateway gw = gatewayWithApproval();
        ToolDefinition def = ToolDefinition.read("calc", "计算").toBuilder()
                .status(ToolStatus.DISABLED).build();
        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> gw.execute(def, () -> "ok", null, null));
        assertEquals(AgentErrorCode.TOOL_STATUS_DISABLED, ex.getErrorCode());
    }

    @Test
    void removedToolShouldBeRejected() {
        ToolGateway gw = gatewayWithApproval();
        ToolDefinition def = ToolDefinition.read("calc", "计算").toBuilder()
                .status(ToolStatus.REMOVED).build();
        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> gw.execute(def, () -> "ok", null, null));
        assertEquals(AgentErrorCode.TOOL_STATUS_DISABLED, ex.getErrorCode());
    }

    @Test
    void toolNeedingApprovalShouldBeRejectedByHook() {
        ToolGateway gw = gatewayWithApproval();
        ToolDefinition def = ToolDefinition.write("refund", "退款", ToolRiskLevel.HIGH);
        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> gw.execute(def, () -> "ok", null, null));
        assertEquals(AgentErrorCode.APPROVAL_REJECTED, ex.getErrorCode());
    }

    @Test
    void stringOverloadShouldResolveFromRegistryThenDelegate() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition def = ToolDefinition.read("calc", "计算");
        registry.register(def);
        ToolGateway gw = new ToolGateway(registry, List.of(new ApprovalHook()));
        String result = gw.execute("calc", () -> "via-name", null, null);
        assertEquals("via-name", result);
    }

    @Test
    void resilienceCircuitBreakerShouldOpenAfterThreeFailures() {
        ToolGateway gw = gatewayWithApproval();
        ToolDefinition def = ToolDefinition.read("unstable", "不稳定工具");
        AtomicInteger executions = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            assertThrows(ToolExecutionException.class, () -> gw.execute(def, () -> {
                executions.incrementAndGet();
                throw new IllegalStateException("down");
            }, null, null));
        }

        ToolExecutionException rejected = assertThrows(ToolExecutionException.class,
                () -> gw.execute(def, () -> {
                    executions.incrementAndGet();
                    return "should-not-run";
                }, null, null));
        assertTrue(rejected.getMessage().contains("熔断已打开"));
        assertEquals(3, executions.get());
    }

    @Test
    void resilienceRateLimiterShouldRejectExcessCalls() {
        ToolGateway gw = gatewayWithApproval();
        ToolDefinition def = ToolDefinition.read("limited", "限流工具").toBuilder()
                .rateLimit(1)
                .build();

        assertEquals("first", gw.execute(def, () -> "first", null, null));
        ToolExecutionException rejected = assertThrows(ToolExecutionException.class,
                () -> gw.execute(def, () -> "second", null, null));
        assertTrue(rejected.getMessage().contains("限流"));
    }

    @Test
    void resilienceTimeLimiterShouldStopWaitingAtConfiguredTimeout() {
        ToolGateway gw = gatewayWithApproval();
        ToolDefinition def = ToolDefinition.read("slow", "慢工具").toBuilder()
                .timeout(Duration.ofMillis(30))
                .build();

        long start = System.nanoTime();
        ToolExecutionException timeout = assertThrows(ToolExecutionException.class,
                () -> gw.execute(def, () -> {
                    Thread.sleep(500);
                    return "late";
                }, null, null));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(timeout.getMessage().contains("超时"));
        assertTrue(elapsedMs < 250, "应按中间件超时预算返回，实际=" + elapsedMs + "ms");
    }
}
