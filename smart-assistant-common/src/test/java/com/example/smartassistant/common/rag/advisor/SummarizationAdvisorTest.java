package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.agent.ReActProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummarizationAdvisorTest {

    @Test
    void participatesInAdvisorChainWithoutCompressingShortHistory() {
        SummarizationAdvisor advisor = new SummarizationAdvisor(
                mock(ChatModel.class), ReActProfile.DEFAULT, new ArrayList<>());
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(new ChatClientResponse(null, Map.of()));
        Prompt prompt = new Prompt(List.of(new SystemMessage("system"), new UserMessage("hello")));

        advisor.adviseCall(ChatClientRequest.builder().prompt(prompt).build(), chain);

        ArgumentCaptor<ChatClientRequest> request = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(request.capture());
        assertEquals(2, request.getValue().prompt().getInstructions().size());
        assertEquals(Boolean.TRUE, request.getValue().context().get(
                SummarizationAdvisor.class.getName() + ".applied"));
    }
}
