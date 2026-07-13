package com.example.smartassistant.common.agent;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.common.error.RecoveryAction;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import com.example.smartassistant.common.observability.OpsMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AgentToolExecutor} 单元测试：覆盖静态工具方法与执行主路径
 * （未知工具 / 成功 / 不可重试错误 / 可重试错误重试）。
 */
class AgentToolExecutorTest {

    private AgentToolExecutor serialExecutor() {
        return new AgentToolExecutor(ReActProfile.DEFAULT, new AgentMetricsCollector() {},
                ErrorRecoveryService.DEFAULT, new OpsMetrics(), false);
    }

    private AssistantMessage.ToolCall call(String name, String args) {
        return new AssistantMessage.ToolCall("call_1", "function", name, args);
    }

    // ==================== 静态工具方法 ====================

    @Test
    @DisplayName("extractErrorCode: 合法 JSON 提取 error_code")
    void extractErrorCode_valid() {
        assertEquals("TOOL_TIMEOUT", AgentToolExecutor.extractErrorCode("{\"error_code\":\"TOOL_TIMEOUT\",\"m\":1}"));
    }

    @Test
    @DisplayName("extractErrorCode: 无 error_code 字段返回 null")
    void extractErrorCode_noField() {
        assertNull(AgentToolExecutor.extractErrorCode("{\"foo\":1}"));
    }

    @Test
    @DisplayName("extractErrorCode: null / 非 JSON 返回 null")
    void extractErrorCode_nullOrPlain() {
        assertNull(AgentToolExecutor.extractErrorCode(null));
        assertNull(AgentToolExecutor.extractErrorCode("just text"));
    }

    @Test
    @DisplayName("argHash64: 同参数同指纹、异参数异指纹、null→0")
    void argHash64_stableAndDistinct() {
        assertEquals(AgentToolExecutor.argHash64("{\"a\":1}"), AgentToolExecutor.argHash64("{\"a\":1}"));
        assertNotEquals(AgentToolExecutor.argHash64("{\"a\":1}"), AgentToolExecutor.argHash64("{\"a\":2}"));
        assertEquals(0L, AgentToolExecutor.argHash64(null));
    }

    @Test
    @DisplayName("truncateResult: 短文本原样返回（同一引用）")
    void truncateResult_short_unchanged() {
        String s = "short";
        assertSame(s, AgentToolExecutor.truncateResult(s, "t"));
    }

    @Test
    @DisplayName("truncateResult: 长文本保头保尾截断且含省略标记")
    void truncateResult_long_truncated() {
        String big = "x".repeat(20000);
        String r = AgentToolExecutor.truncateResult(big, "t");
        assertTrue(r.length() < big.length());
        assertTrue(r.contains("中间省略"));
    }

    // ==================== 执行主路径 ====================

    @Test
    @DisplayName("未知工具 → 返回 UNKNOWN_TOOL 结构化错误")
    void execute_unknownTool() {
        var res = serialExecutor().execute(List.of(call("nope", "{}")), Map.of());
        assertEquals(1, res.size());
        assertTrue(res.get(0).responseData().contains("UNKNOWN_TOOL"));
    }

    @Test
    @DisplayName("未知工具 → 触发向用户澄清（clarification 非空且含工具名, resolution=asked_user）")
    void execute_unknownTool_clarifies() {
        var res = serialExecutor().execute(List.of(call("nope", "{}")), Map.of());
        String data = res.get(0).responseData();
        assertTrue(data.contains("UNKNOWN_TOOL"));
        assertTrue(data.contains("\"clarification\""), "应携带向用户澄清的话术");
        assertTrue(data.contains("nope"), "澄清话术应包含被臆造的工具名");
    }

    @Test
    @DisplayName("数据未找到(ORDER_NOT_FOUND) → 记录 EMPTY_RESULT 缺口且不重试、原样返回")
    void execute_dataNotFound_recordsEmptyResultGap() {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.call("{}")).thenReturn("{\"error_code\":\"ORDER_NOT_FOUND\",\"message\":\"未找到该订单\"}");
        var res = serialExecutor().execute(List.of(call("queryOrder", "{}")), Map.of("queryOrder", tool));
        // 非可重试 → 立即返回，且内容透传（不被截断/改写）
        assertTrue(res.get(0).responseData().contains("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("成功工具 → 原样返回结果")
    void execute_successTool() {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.call("{}")).thenReturn("{\"ok\":true}");
        var res = serialExecutor().execute(List.of(call("echo", "{}")), Map.of("echo", tool));
        assertEquals("{\"ok\":true}", res.get(0).responseData());
    }

    @Test
    @DisplayName("不可重试错误码 → 立即返回原错误（不重试）")
    void execute_nonRetryableError_returnsImmediately() {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.call("{}")).thenReturn("{\"error_code\":\"TOOL_VERSION_INCOMPATIBLE\",\"message\":\"x\"}");
        var res = serialExecutor().execute(List.of(call("v", "{}")), Map.of("v", tool));
        assertTrue(res.get(0).responseData().contains("TOOL_VERSION_INCOMPATIBLE"));
    }

    @Test
    @DisplayName("可重试错误码 → 按 recovery 策略重试后返回（调用 3 次）")
    void execute_retryableError_retriesThenReturns() {
        // 自定义 recovery：前 2 次重试、第 3 次不再重试，延迟 0 避免睡眜
        ErrorRecoveryService retry = new ErrorRecoveryService() {
            @Override
            public boolean shouldRetry(AgentErrorCode code, int attempt) {
                return attempt < 3;
            }

            @Override
            public long getRetryDelayMs(AgentErrorCode code, int attempt) {
                return 0;
            }
        };
        ToolCallback tool = mock(ToolCallback.class);
        AtomicInteger calls = new AtomicInteger();
        when(tool.call("{}")).thenAnswer(inv -> {
            calls.incrementAndGet();
            return "{\"error_code\":\"RAG_EMBEDDING_UNAVAILABLE\",\"message\":\"down\"}";
        });
        var ex = new AgentToolExecutor(ReActProfile.DEFAULT, new AgentMetricsCollector() {}, retry, new OpsMetrics(), false);
        var res = ex.execute(List.of(call("v", "{}")), Map.of("v", tool));
        assertEquals(3, calls.get(), "应重试至第 3 次才停止");
        assertTrue(res.get(0).responseData().contains("RAG_EMBEDDING_UNAVAILABLE"));
    }
}
