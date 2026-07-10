package com.example.smartassistant.common.gateway.tool.hook;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolExecutionException;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ApprovalHook} 单元测试。
 * <p>
 * 验证审批拦截逻辑：
 * <ul>
 *   <li>needsApproval=true → 抛 ToolExecutionException(APPROVAL_REJECTED)</li>
 *   <li>needsApproval=false → 正常放行</li>
 * </ul>
 * </p>
 */
class ApprovalHookTest {

    private final ApprovalHook hook = new ApprovalHook();

    private ToolHookContext buildContext(boolean needsApproval) {
        ToolDefinition def = ToolDefinition.builder()
                .name("approval-test-tool")
                .description("审批测试工具")
                .riskLevel(ToolRiskLevel.HIGH)
                .needsApproval(needsApproval)
                .build();
        return ToolHookContext.builder()
                .toolName("approval-test-tool")
                .toolDefinition(def)
                .scope("admin")
                .idempotencyKey(null)
                .startTimeMs(System.currentTimeMillis())
                .build();
    }

    @Test
    @DisplayName("needsApproval=true 应抛 ToolExecutionException(APPROVAL_REJECTED)")
    void needsApprovalTrueShouldThrow() {
        ToolHookContext context = buildContext(true);

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> hook.preExecute(context));

        assertEquals("approval-test-tool", ex.getToolName());
        assertEquals(AgentErrorCode.APPROVAL_REJECTED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("approval-test-tool"));
    }

    @Test
    @DisplayName("needsApproval=false 应正常放行")
    void needsApprovalFalseShouldPass() {
        ToolHookContext context = buildContext(false);

        assertDoesNotThrow(() -> hook.preExecute(context));
    }

    @Test
    @DisplayName("postExecute 应原样返回结果")
    void postExecuteShouldReturnResultAsIs() {
        ToolHookContext context = buildContext(false);
        String result = "执行结果";

        assertEquals(result, hook.postExecute(context, result));
    }

    @Test
    @DisplayName("onError 应为空操作")
    void onErrorShouldBeNoOp() {
        ToolHookContext context = buildContext(false);

        assertDoesNotThrow(() -> hook.onError(context, new RuntimeException("test")));
    }

    @Test
    @DisplayName("getName 应返回 ApprovalHook")
    void getNameShouldReturnApprovalHook() {
        assertEquals("ApprovalHook", hook.getName());
    }
}
