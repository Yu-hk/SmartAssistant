package com.example.smartassistant.common.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PromptBuilder} 增强功能单元测试。
 */
class PromptBuilderTest {

    @Test
    @DisplayName("基本的 service + dynamic 拼接")
    void basicAssembly() {
        String result = PromptBuilder.build()
                .withServicePrompt("你是一个助手。")
                .withDynamicContext("用户偏好：喜欢红色。")
                .assemble();
        assertTrue(result.contains("你是一个助手。"));
        assertTrue(result.contains("用户偏好：喜欢红色。"));
    }

    @Test
    @DisplayName("模板变量替换")
    void variableSubstitution() {
        String result = PromptBuilder.build()
                .withServicePrompt("你好 ${name}，今天是 ${date}。")
                .withVar("name", "张三")
                .withVar("date", "周一")
                .assemble();
        assertTrue(result.contains("你好 张三，今天是 周一。"));
        assertFalse(result.contains("${name}"));
    }

    @Test
    @DisplayName("条件章节注入（条件为真时包含）")
    void conditionalSectionIncluded() {
        String result = PromptBuilder.build()
                .withServicePrompt("基础指令。")
                .withSectionIf("额外信息", "这里是额外内容", true)
                .assemble();
        assertTrue(result.contains("额外信息"));
        assertTrue(result.contains("这里是额外内容"));
    }

    @Test
    @DisplayName("条件章节注入（条件为假时排除）")
    void conditionalSectionExcluded() {
        String result = PromptBuilder.build()
                .withServicePrompt("基础指令。")
                .withSectionIf("不应该出现", "隐藏内容", false)
                .assemble();
        assertFalse(result.contains("不应该出现"));
    }

    @Test
    @DisplayName("模板变量在 dynamic 层也生效")
    void variableInDynamicLayer() {
        String result = PromptBuilder.build()
                .withServicePrompt("基础。")
                .withDynamicContext("用户：${username}")
                .withVar("username", "李四")
                .assemble();
        assertTrue(result.contains("用户：李四"));
    }

    @Test
    @DisplayName("带空格的变量名应被替换")
    void emptyVariableNoError() {
        String result = PromptBuilder.build()
                .withServicePrompt("${}")
                .assemble();
        assertNotNull(result);
    }
}
