package com.example.smartassistant.common.gateway.tool.hook;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolExecutionException;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolStatusHook} 单元测试。
 * <p>
 * 验证工具生命周期状态拦截逻辑：
 * <ul>
 *   <li>DISABLED / REMOVED → 抛 ToolExecutionException(TOOL_STATUS_DISABLED)</li>
 *   <li>DEPRECATED → WARN 日志，正常放行</li>
 *   <li>ACTIVE / EXPERIMENTAL → 正常放行</li>
 * </ul>
 * </p>
 */
class ToolStatusHookTest {

    private final ToolStatusHook hook = new ToolStatusHook();

    private ToolHookContext buildContext(ToolStatus status) {
        ToolDefinition def = ToolDefinition.builder()
                .name("test-tool")
                .description("测试工具")
                .status(status)
                .build();
        return ToolHookContext.builder()
                .toolName("test-tool")
                .toolDefinition(def)
                .scope("test-scope")
                .idempotencyKey(null)
                .startTimeMs(System.currentTimeMillis())
                .build();
    }

    @Test
    @DisplayName("DISABLED 状态应抛 ToolExecutionException(TOOL_STATUS_DISABLED)")
    void disabledStatusShouldThrow() {
        ToolHookContext context = buildContext(ToolStatus.DISABLED);

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> hook.preExecute(context));

        assertEquals("test-tool", ex.getToolName());
        assertEquals(AgentErrorCode.TOOL_STATUS_DISABLED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("test-tool"));
        assertTrue(ex.getMessage().contains("DISABLED"));
    }

    @Test
    @DisplayName("REMOVED 状态应抛 ToolExecutionException(TOOL_STATUS_DISABLED)")
    void removedStatusShouldThrow() {
        ToolHookContext context = buildContext(ToolStatus.REMOVED);

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> hook.preExecute(context));

        assertEquals("test-tool", ex.getToolName());
        assertEquals(AgentErrorCode.TOOL_STATUS_DISABLED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("REMOVED"));
    }

    @Test
    @DisplayName("DEPRECATED 状态应正常放行（不抛异常）")
    void deprecatedStatusShouldPass() {
        ToolHookContext context = buildContext(ToolStatus.DEPRECATED);

        assertDoesNotThrow(() -> hook.preExecute(context));
    }

    @Test
    @DisplayName("ACTIVE 状态应正常放行")
    void activeStatusShouldPass() {
        ToolHookContext context = buildContext(ToolStatus.ACTIVE);

        assertDoesNotThrow(() -> hook.preExecute(context));
    }

    @Test
    @DisplayName("EXPERIMENTAL 状态应正常放行")
    void experimentalStatusShouldPass() {
        ToolHookContext context = buildContext(ToolStatus.EXPERIMENTAL);

        assertDoesNotThrow(() -> hook.preExecute(context));
    }

    @Test
    @DisplayName("postExecute 应原样返回结果")
    void postExecuteShouldReturnResultAsIs() {
        ToolHookContext context = buildContext(ToolStatus.ACTIVE);
        String result = "原始结果数据";

        String processed = hook.postExecute(context, result);

        assertEquals(result, processed);
    }

    @Test
    @DisplayName("postExecute 对 null 结果应返回 null")
    void postExecuteShouldHandleNull() {
        ToolHookContext context = buildContext(ToolStatus.ACTIVE);

        String processed = hook.postExecute(context, null);

        assertNull(processed);
    }

    @Test
    @DisplayName("onError 应为空操作（不抛异常）")
    void onErrorShouldBeNoOp() {
        ToolHookContext context = buildContext(ToolStatus.ACTIVE);
        Exception ex = new RuntimeException("测试异常");

        assertDoesNotThrow(() -> hook.onError(context, ex));
    }

    @Test
    @DisplayName("getName 应返回 ToolStatusHook")
    void getNameShouldReturnToolStatusHook() {
        assertEquals("ToolStatusHook", hook.getName());
    }
}
