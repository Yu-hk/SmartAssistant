package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.hook.ApprovalHook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
