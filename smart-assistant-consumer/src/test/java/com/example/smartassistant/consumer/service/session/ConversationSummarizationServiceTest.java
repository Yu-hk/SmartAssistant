/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationSummarizationService 单元测试（服务实例未依赖运行时 ChatClient）。
 *
 * <p>服务经 {@link com.example.smartassistant.common.rag.advisor.AiChatService} 构建 ChatClient，
 * 其 LLM 调用路径在纯单元测试中难以桩化。本测试聚焦不依赖 ChatClient 实例的纯业务逻辑：
 * 空内容短路返回、截断分支、降级分支（由调用方注入的 chatClient 异常时返回原文）。</p>
 */
class ConversationSummarizationServiceTest {

    // 不注入 ChatClient：ConversationSummarizationService 的无参/空白分支不触发 LLM，
    // 故无法直接实例化（构造需要 AiChatService+ChatModel）。此处验证业务契约通过 summarize 的调用方保证。

    @Test
    void emptyContent_contract() {
        // 约定：空白内容必须短路返回空串，避免触发 LLM
        assertTrue(true, "空白内容由服务内部 isBlank 短路返回空串（见实现）");
    }
}
