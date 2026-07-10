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

    // ==================== withProjectContext 测试 ====================

    @Test
    @DisplayName("withProjectContext() 注入顺序：projectContext 在 servicePrompt 之前")
    void projectContextOrderBeforeService() {
        String result = PromptBuilder.build()
                .withProjectContext("PROJECT_CONTEXT_MARKER")
                .withServicePrompt("SERVICE_PROMPT_MARKER")
                .assemble();

        int projectIdx = result.indexOf("PROJECT_CONTEXT_MARKER");
        int serviceIdx = result.indexOf("SERVICE_PROMPT_MARKER");

        assertTrue(projectIdx >= 0, "projectContext 应出现在结果中");
        assertTrue(serviceIdx >= 0, "servicePrompt 应出现在结果中");
        assertTrue(projectIdx < serviceIdx, "projectContext 应在 servicePrompt 之前");
    }

    @Test
    @DisplayName("withProjectContext() 注入顺序：projectContext 在 dynamicContext 之前")
    void projectContextOrderBeforeDynamic() {
        String result = PromptBuilder.build()
                .withProjectContext("PROJECT_CTX")
                .withDynamicContext("DYNAMIC_CTX")
                .assemble();

        int projectIdx = result.indexOf("PROJECT_CTX");
        int dynamicIdx = result.indexOf("DYNAMIC_CTX");

        assertTrue(projectIdx >= 0, "projectContext 应出现在结果中");
        assertTrue(dynamicIdx >= 0, "dynamicContext 应出现在结果中");
        assertTrue(projectIdx < dynamicIdx, "projectContext 应在 dynamicContext 之前");
    }

    @Test
    @DisplayName("withProjectContext() 完整层级顺序：base → projectContext → service → sections → dynamic")
    void fullLayerOrder() {
        String result = PromptBuilder.build()
                .withProjectContext("LAYER_PROJECT")
                .withServicePrompt("LAYER_SERVICE")
                .withSection("Section", "LAYER_SECTION")
                .withDynamicContext("LAYER_DYNAMIC")
                .assemble();

        int projectIdx = result.indexOf("LAYER_PROJECT");
        int serviceIdx = result.indexOf("LAYER_SERVICE");
        int sectionIdx = result.indexOf("LAYER_SECTION");
        int dynamicIdx = result.indexOf("LAYER_DYNAMIC");

        assertTrue(projectIdx >= 0 && serviceIdx >= 0 && sectionIdx >= 0 && dynamicIdx >= 0,
                "所有层级都应出现在结果中");
        assertTrue(projectIdx < serviceIdx, "projectContext 应在 service 之前");
        assertTrue(serviceIdx < sectionIdx, "service 应在 sections 之前");
        assertTrue(sectionIdx < dynamicIdx, "sections 应在 dynamic 之前");
    }

    @Test
    @DisplayName("projectContext 超过 8000 字符时截断 + 追加截断标记")
    void projectContextTruncation() {
        String longContext = "A".repeat(8001);
        String result = PromptBuilder.build()
                .withProjectContext(longContext)
                .assemble();

        assertTrue(result.contains("...[project-context 已截断]"),
                "截断后应包含截断标记");
        // 验证截断后的内容正好是 8000 个 A + 截断标记
        String expectedTruncated = "A".repeat(8000) + "...[project-context 已截断]";
        assertTrue(result.contains(expectedTruncated),
                "截断后的内容应为 8000 字符 + 截断标记");
        // 验证不包含第 8001 个 A（紧跟截断标记后的 A 不存在）
        assertFalse(result.contains("A".repeat(8001)),
                "不应包含完整的 8001 字符原文");
    }

    @Test
    @DisplayName("projectContext 恰好 8000 字符时不截断")
    void projectContextExactLimit() {
        String exactContext = "B".repeat(8000);
        String result = PromptBuilder.build()
                .withProjectContext(exactContext)
                .assemble();

        assertFalse(result.contains("...[project-context 已截断]"),
                "恰好 8000 字符不应截断");
        assertTrue(result.contains(exactContext),
                "恰好 8000 字符应完整保留");
    }

    @Test
    @DisplayName("projectContext 为 null 时跳过（不注入）")
    void projectContextNullShouldBeSkipped() {
        String result = PromptBuilder.build()
                .withProjectContext(null)
                .withServicePrompt("SERVICE_ONLY")
                .assemble();

        assertFalse(result.contains("...[project-context 已截断]"),
                "projectContext 为 null 时不应有截断标记");
        assertTrue(result.contains("SERVICE_ONLY"));
    }

    @Test
    @DisplayName("projectContext 为空字符串时跳过")
    void projectContextBlankShouldBeSkipped() {
        String result = PromptBuilder.build()
                .withProjectContext("   ")
                .withServicePrompt("SERVICE_ONLY")
                .assemble();

        assertFalse(result.contains("...[project-context 已截断]"),
                "projectContext 为空白时不应有截断标记");
    }

    @Test
    @DisplayName("projectContext 中的模板变量应被替换")
    void projectContextVariableSubstitution() {
        String result = PromptBuilder.build()
                .withProjectContext("项目名称: ${projectName}")
                .withVar("projectName", "SmartAssistant")
                .assemble();

        assertTrue(result.contains("项目名称: SmartAssistant"));
        assertFalse(result.contains("${projectName}"));
    }
}
