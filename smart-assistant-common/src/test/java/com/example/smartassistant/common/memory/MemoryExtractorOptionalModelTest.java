package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
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
}
