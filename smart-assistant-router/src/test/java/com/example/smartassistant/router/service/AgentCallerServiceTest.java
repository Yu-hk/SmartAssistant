/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service;

import com.example.smartassistant.router.service.agent.AgentCallerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentCallerService 单元测试
 * 验证 cleanThinkingContent 等消息清理逻辑
 */
@ExtendWith(MockitoExtension.class)
class AgentCallerServiceTest {

    @Test
    @DisplayName("空值清理返回空")
    void testCleanThinkingContent_Null() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        // 需要先创建实例
        AgentCallerService service = new AgentCallerService(null, null);
        assertNull(method.invoke(service, new Object[]{null}));
        assertEquals("", method.invoke(service, ""));
    }

    @Test
    @DisplayName("无思考内容时返回原文本")
    void testCleanThinkingContent_NoThinking() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        AgentCallerService service = new AgentCallerService(null, null);
        String text = "北京有什么好玩的景点？";
        assertEquals(text, method.invoke(service, text));
    }

    @Test
    @DisplayName("清理 [ModelThinking] 区块")
    void testCleanThinkingContent_ModelThinking() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        AgentCallerService service = new AgentCallerService(null, null);
        String input = "[ModelThinking]用户想知道北京景点[/ModelThinking]北京有故宫和天坛";
        String result = (String) method.invoke(service, input);
        assertFalse(result.contains("[ModelThinking]"), "应移除 [ModelThinking] 标记");
        assertTrue(result.contains("故宫"), "应保留正文");
    }

    @Test
    @DisplayName("清理 [思考内容] 区块")
    void testCleanThinkingContent_ThinkingContent() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        AgentCallerService service = new AgentCallerService(null, null);
        String input = "[思考内容]推理过程[/思考内容]最终答案";
        String result = (String) method.invoke(service, input);
        assertFalse(result.contains("[思考内容]"));
        assertTrue(result.contains("最终答案"));
    }

    @Test
    @DisplayName("清理 [思考] 区块")
    void testCleanThinkingContent_Thinking() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        AgentCallerService service = new AgentCallerService(null, null);
        String input = "[思考]一些推理内容[/思考]以下是推荐结果";
        String result = (String) method.invoke(service, input);
        assertFalse(result.contains("[思考]"));
        assertTrue(result.contains("推荐结果"));
    }

    @Test
    @DisplayName("清理 [reasoning] 区块")
    void testCleanThinkingContent_Reasoning() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        AgentCallerService service = new AgentCallerService(null, null);
        String input = "[reasoning]step by step[/reasoning]Here is the answer";
        String result = (String) method.invoke(service, input);
        assertFalse(result.contains("[reasoning]"));
        assertTrue(result.contains("answer"));
    }

    @Test
    @DisplayName("清理编号推理前缀（安全回退）")
    void testCleanThinkingContent_NumberedPrefix() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        AgentCallerService service = new AgentCallerService(null, null);
        // 短文本（<20字）不应触发清理
        String shortText = "1. 好的明白了";
        assertEquals(shortText, method.invoke(service, shortText));
    }

    @Test
    @DisplayName("不应误吞正常文本")
    void testCleanThinkingContent_NormalText() throws Exception {
        Method method = AgentCallerService.class
                .getDeclaredMethod("cleanThinkingContent", String.class);
        method.setAccessible(true);

        AgentCallerService service = new AgentCallerService(null, null);
        String[] normalTexts = {
            "推荐几个北京的热门景点：故宫、天坛、颐和园",
            "1月去哈尔滨穿什么衣服好？",
            "1. 先确定需求 2. 然后选择 3. 最后下单",  // 列表格式
        };
        for (String text : normalTexts) {
            String result = (String) method.invoke(service, text);
            assertEquals(text, result, "不应被修改: " + text);
        }
    }
}
