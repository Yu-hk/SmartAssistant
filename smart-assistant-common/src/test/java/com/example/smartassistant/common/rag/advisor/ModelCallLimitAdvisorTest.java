package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.governance.CallBudgetExceededException;
import com.example.smartassistant.common.governance.InvocationBudgetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCallLimitAdvisorTest {

    private final InvocationBudgetRegistry registry = InvocationBudgetRegistry.shared();

    @BeforeEach void reset() { registry.clear(); }

    @Test
    void rejectsThirdCallInSameRequest() {
        ModelCallLimitAdvisor advisor = new ModelCallLimitAdvisor(registry, 2, 10);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(new ChatClientResponse(null, Map.of()));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("hello"))
                .context(Map.of("requestId", "req-1", "sessionId", "session-1"))
                .build();

        assertDoesNotThrow(() -> advisor.adviseCall(request, chain));
        assertDoesNotThrow(() -> advisor.adviseCall(request, chain));
        assertThrows(CallBudgetExceededException.class, () -> advisor.adviseCall(request, chain));
    }

    @Test
    void rejectsAcrossRequestsInSameSession() {
        ModelCallLimitAdvisor advisor = new ModelCallLimitAdvisor(registry, 10, 2);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(new ChatClientResponse(null, Map.of()));
        for (int i = 0; i < 2; i++) {
            ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hello"))
                    .context(Map.of("requestId", "req-" + i, "sessionId", "session-x")).build();
            assertDoesNotThrow(() -> advisor.adviseCall(request, chain));
        }
        ChatClientRequest rejected = ChatClientRequest.builder().prompt(new Prompt("hello"))
                .context(Map.of("requestId", "req-3", "sessionId", "session-x")).build();
        assertThrows(CallBudgetExceededException.class, () -> advisor.adviseCall(rejected, chain));
    }
}
