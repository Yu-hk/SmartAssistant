package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmartReActAgentToolCallbackTest {
    @Test
    void passesPreparedCallbacksThroughToolCallbacksApi() {
        ChatModel model = mock(ChatModel.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("sampleTool");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(client.prompt()).thenReturn(request);
        when(request.messages(anyList())).thenReturn(request);
        when(request.toolCallbacks(anyList())).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(call.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))));

        SmartReActAgent agent = new SmartReActAgent(model)
                .withChatClient(client)
                .withPreset("system", List.of(callback));

        assertEquals("ok", agent.execute("hello"));
        verify(request).toolCallbacks(anyList());
        verify(request, never()).tools(callback);
    }
}
