package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ModelToolInjector} 单元测试：反射注入工具到 ChatModel。
 */
class ModelToolInjectorTest {

    /** 实现 ChatModel 并暴露 setToolCallbacks，用于验证注入路径。 */
    static class TestChatModel implements ChatModel {
        List<ToolCallback> injected;

        @SuppressWarnings("unused")
        public void setToolCallbacks(List<ToolCallback> tools) {
            this.injected = tools;
        }

        @Override
        public ChatResponse call(Prompt request) {
            return null;
        }
    }

    @Test
    @DisplayName("注入带 setToolCallbacks 的 ChatModel → 调用该方法")
    void inject_callsSetToolCallbacks() {
        TestChatModel model = new TestChatModel();
        ToolCallback cb = mock(ToolCallback.class);
        ModelToolInjector.inject(LoggerFactory.getLogger(getClass()), model, List.of(cb));
        assertNotNull(model.injected);
        assertEquals(1, model.injected.size());
    }

    @Test
    @DisplayName("注入无 setToolCallbacks 的 ChatModel → 不抛异常（静默跳过）")
    void inject_noMethod_noException() {
        ChatModel model = mock(ChatModel.class);
        assertDoesNotThrow(() ->
                ModelToolInjector.inject(LoggerFactory.getLogger(getClass()), model, List.of(mock(ToolCallback.class))));
    }
}
