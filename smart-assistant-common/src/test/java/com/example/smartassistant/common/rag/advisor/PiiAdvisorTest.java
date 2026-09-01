package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.security.PiiPolicyEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PiiAdvisorTest {

    @Test
    void sanitizesPromptAndCompletion() {
        PiiAdvisor advisor = new PiiAdvisor(PiiPolicyEngine.shared());
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatResponse modelResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage("联系 13812345678"))));
        when(chain.nextCall(any())).thenReturn(new ChatClientResponse(modelResponse, Map.of()));

        ChatClientResponse result = advisor.adviseCall(
                ChatClientRequest.builder().prompt(new Prompt("邮箱 user@example.com")).build(), chain);

        ArgumentCaptor<ChatClientRequest> request = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(request.capture());
        assertEquals("邮箱 [EMAIL]", request.getValue().prompt().getContents());
        assertEquals("联系 [PHONE]", result.chatResponse().getResult().getOutput().getText());
    }
}
