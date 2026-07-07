/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OllamaVisionImageCaptionerTest {

    @Test
    void notAvailableWhenModelNull() {
        OllamaVisionImageCaptioner cap = new OllamaVisionImageCaptioner(null);
        assertFalse(cap.isAvailable());
        assertEquals("", cap.caption(new ImageReference("a.png", new byte[]{1, 2, 3}, "image/png")));
    }

    @Test
    void captionNullReturnsEmpty() {
        OllamaVisionImageCaptioner cap = new OllamaVisionImageCaptioner(null);
        assertEquals("", cap.caption(null));
    }

    @Test
    void captionDelegatesToDoCaption() {
        // 用 mock ChatModel 构造（build 不会真正调用模型），再以 spy 桩接实际调用
        OllamaVisionImageCaptioner real =
                new OllamaVisionImageCaptioner(Mockito.mock(ChatModel.class));
        OllamaVisionImageCaptioner spy = Mockito.spy(real);
        Mockito.doReturn("这是一张机票行程单，含航班号 CA1234")
                .when(spy).doCaption(Mockito.anyString(), Mockito.any());

        assertTrue(spy.isAvailable());
        String result = spy.caption(new ImageReference("ticket.png", new byte[]{9, 9, 9}, "image/png"));
        assertEquals("这是一张机票行程单，含航班号 CA1234", result);
    }

    @Test
    void emptyBytesReturnsEmpty() {
        OllamaVisionImageCaptioner cap = new OllamaVisionImageCaptioner(null);
        assertEquals("", cap.caption(new ImageReference("a.png", new byte[0], "image/png")));
    }
}
