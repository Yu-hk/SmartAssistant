package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartReActAgentPhaseGateTest {

    @Mock
    ChatModel chatModel;

    @TempDir
    Path workspace;

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void gatePassesAndAllowsFinalAnswer() throws Exception {
        Files.writeString(workspace.resolve("done.txt"), "done");
        String answer = "任务已经完成，这是足够长且可以直接结束的最终回答。".repeat(3);
        when(chatModel.call(any(Prompt.class))).thenReturn(answer(answer));

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withPhaseGate(List.of(new PhaseGate.Check("done",
                        PhaseGate.Check.TYPE_FILE_EXISTS, "done.txt", "产物存在")),
                        "delivery", workspace.toString());

        assertEquals(answer, agent.execute("执行任务", "sys", List.of()));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void gateFailureVetoesFinalAnswerUntilIterationBudget() {
        String unverifiedAnswer = "任务已经完成，这是足够长但没有通过代码验收的回答。".repeat(3);
        when(chatModel.call(any(Prompt.class))).thenReturn(answer(unverifiedAnswer));
        ReActProfile profile = new ReActProfile(2, 60_000, 0.8, 128_000, 30_000, 2);

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withProfile("phase", new ReActProfileRegistry(Map.of("phase", profile)))
                .withPhaseGate(List.of(new PhaseGate.Check("missing",
                        PhaseGate.Check.TYPE_FILE_EXISTS, "missing.txt", "产物存在")),
                        "delivery", workspace.toString());

        String result = agent.execute("执行任务", "sys", List.of());

        assertNotEquals(unverifiedAnswer, result);
        verify(chatModel, times(2)).call(any(Prompt.class));
    }
}
