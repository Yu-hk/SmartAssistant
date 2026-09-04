package com.example.smartassistant.common.memory;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryExtractorOptionalModelTest {

    @Test
    void skipsExtractionWhenServiceDoesNotProvideAChatModel() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MemoryExtractor extractor = new MemoryExtractor(provider, memoryService);

        assertDoesNotThrow(() -> extractor.extractFromConversation(
                "user", "user-1", "请记住我偏好简洁回答", "好的"));
        verifyNoInteractions(memoryService);
    }

    @Test
    void usesSpringAiStructuredOutputAndPersistsExtractedPreferences() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        ChatModel model = mock(ChatModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage output = AssistantMessage.builder()
                .content("{\"preferBrand\":\"华为\"}")
                .properties(Map.of())
                .toolCalls(List.of())
                .build();
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(output))));

        AiChatService aiChatService = new AiChatService(null, null, null, null);
        Map<String, String> parsed = aiChatService.entity(
                model, "extract", new ParameterizedTypeReference<>() {});
        org.assertj.core.api.Assertions.assertThat(parsed)
                .containsEntry("preferBrand", "华为");

        MemoryExtractor extractor = new MemoryExtractor(provider, memoryService, aiChatService);
        extractor.extractFromConversation("product", "user-1", "我更喜欢华为", "已记住");

        verify(memoryService).save("product", "user-1", "preferBrand", "华为");
    }
}
