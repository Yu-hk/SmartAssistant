package com.example.smartassistant.common.gateway.tool.hook;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SanitizeHook} 单元测试。
 * <p>
 * 验证 postExecute 阶段的正则脱敏逻辑：
 * <ul>
 *   <li>手机号（11位）：中间4位脱敏为 ****</li>
 *   <li>身份证号（18位）：中间8位脱敏为 ********</li>
 *   <li>银行卡号（16-19位）：前4后4保留，中间脱敏</li>
 *   <li>无敏感信息 → 原样返回</li>
 * </ul>
 * </p>
 */
class SanitizeHookTest {

    private final SanitizeHook hook = new SanitizeHook();

    private ToolHookContext buildContext() {
        ToolDefinition def = ToolDefinition.builder()
                .name("sanitize-test-tool")
                .description("脱敏测试工具")
                .build();
        return ToolHookContext.builder()
                .toolName("sanitize-test-tool")
                .toolDefinition(def)
                .scope("test")
                .startTimeMs(System.currentTimeMillis())
                .build();
    }

    // ==================== 手机号脱敏 ====================

    @Test
    @DisplayName("手机号脱敏：13812345678 → 138****5678")
    void phoneMasking() {
        ToolHookContext context = buildContext();
        String result = hook.postExecute(context, "13812345678");

        assertEquals("138****5678", result);
    }

    @Test
    @DisplayName("手机号脱敏：嵌入文本中的手机号也应脱敏")
    void phoneMaskingInText() {
        ToolHookContext context = buildContext();
        String result = hook.postExecute(context, "用户手机号是13812345678，请联系");

        assertEquals("用户手机号是138****5678，请联系", result);
    }

    // ==================== 身份证号脱敏 ====================

    @Test
    @DisplayName("身份证号脱敏：110101199001011234 → 110101********1234")
    void idCardMasking() {
        ToolHookContext context = buildContext();
        String result = hook.postExecute(context, "110101199001011234");

        assertEquals("110101********1234", result);
    }

    @Test
    @DisplayName("身份证号脱敏：嵌入文本中的身份证号也应脱敏")
    void idCardMaskingInText() {
        ToolHookContext context = buildContext();
        String result = hook.postExecute(context, "身份证号:110101199001011234已登记");

        assertEquals("身份证号:110101********1234已登记", result);
    }

    // ==================== 银行卡号脱敏 ====================

    @Test
    @DisplayName("银行卡号脱敏（19位）：6222021234567890123 → 6222****0123")
    void bankCardMasking19Digits() {
        ToolHookContext context = buildContext();
        String result = hook.postExecute(context, "6222021234567890123");

        assertEquals("6222****0123", result);
    }

    @Test
    @DisplayName("银行卡号脱敏（16位）：6222021234567890 → 6222****7890")
    void bankCardMasking16Digits() {
        ToolHookContext context = buildContext();
        String result = hook.postExecute(context, "6222021234567890");

        assertEquals("6222****7890", result);
    }

    @Test
    @DisplayName("银行卡号脱敏：嵌入文本中的银行卡号也应脱敏")
    void bankCardMaskingInText() {
        ToolHookContext context = buildContext();
        String result = hook.postExecute(context, "卡号6222021234567890123已绑定");

        assertEquals("卡号6222****0123已绑定", result);
    }

    // ==================== 混合脱敏 ====================

    @Test
    @DisplayName("混合脱敏：同一字符串中的手机号和身份证号同时脱敏")
    void mixedMasking() {
        ToolHookContext context = buildContext();
        String input = "手机13812345678身份证110101199001011234";
        String result = hook.postExecute(context, input);

        assertEquals("手机138****5678身份证110101********1234", result);
    }

    // ==================== 无敏感信息 ====================

    @Test
    @DisplayName("无敏感信息：原样返回")
    void noSensitiveInfo() {
        ToolHookContext context = buildContext();
        String input = "这是一段普通文本，没有敏感信息。";

        String result = hook.postExecute(context, input);

        assertEquals(input, result);
    }

    @Test
    @DisplayName("纯英文字符串：原样返回")
    void plainEnglishText() {
        ToolHookContext context = buildContext();
        String input = "Hello World, no sensitive data here.";

        assertEquals(input, hook.postExecute(context, input));
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("null 结果应返回 null")
    void nullResultShouldReturnNull() {
        ToolHookContext context = buildContext();

        assertNull(hook.postExecute(context, null));
    }

    @Test
    @DisplayName("空字符串应返回空字符串")
    void emptyResultShouldReturnEmpty() {
        ToolHookContext context = buildContext();

        assertEquals("", hook.postExecute(context, ""));
    }

    @Test
    @DisplayName("短数字串（少于11位）不应被脱敏")
    void shortDigitStringShouldNotBeMasked() {
        ToolHookContext context = buildContext();

        assertEquals("12345", hook.postExecute(context, "12345"));
    }

    @Test
    @DisplayName("10位数字串不应被手机号模式匹配（需恰好11位）")
    void tenDigitStringShouldNotMatchPhonePattern() {
        ToolHookContext context = buildContext();
        // 10 digits - too short for phone pattern (needs exactly 11)
        String input = "1381234567";

        assertEquals(input, hook.postExecute(context, input));
    }

    @Test
    @DisplayName("12位数字串不应被手机号模式匹配（需恰好11位）")
    void twelveDigitStringShouldNotMatchPhonePattern() {
        ToolHookContext context = buildContext();
        // 12 digits - too long for phone pattern
        String input = "138123456789";

        assertEquals(input, hook.postExecute(context, input));
    }

    @Test
    @DisplayName("15位数字串不应被身份证模式匹配（需恰好18位）")
    void fifteenDigitStringShouldNotMatchIdCardPattern() {
        ToolHookContext context = buildContext();
        // 15 digits - too short for ID card (needs exactly 18)
        String input = "110101199001011";

        assertEquals(input, hook.postExecute(context, input));
    }

    // ==================== Hook 基础方法 ====================

    @Test
    @DisplayName("preExecute 应为空操作（不抛异常）")
    void preExecuteShouldBeNoOp() {
        ToolHookContext context = buildContext();

        assertDoesNotThrow(() -> hook.preExecute(context));
    }

    @Test
    @DisplayName("onError 应为空操作")
    void onErrorShouldBeNoOp() {
        ToolHookContext context = buildContext();

        assertDoesNotThrow(() -> hook.onError(context, new RuntimeException("test")));
    }

    @Test
    @DisplayName("getName 应返回 SanitizeHook")
    void getNameShouldReturnSanitizeHook() {
        assertEquals("SanitizeHook", hook.getName());
    }
}
