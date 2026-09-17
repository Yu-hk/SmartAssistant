package com.example.smartassistant.consumer.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LightChatModelConfigTest {
    @Test void preservesTaskOptionsForBothPathsWithoutLeakingIntoTheNextCall() {
        var delegate = mock(DeepSeekChatModel.class);
        var model = new LightChatModelConfig().lightChatModel(delegate, "light", 0.1);
        var options = DeepSeekChatOptions.builder().model("unwanted-model").disableThinking()
                .maxTokens(2048).temperature(0.3).stop(java.util.List.of("END")).build();
        model.call(new Prompt("profile", options));
        model.stream(new Prompt("profile", options));
        model.call(new Prompt("summary"));
        var calls = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(2)).call(calls.capture());
        var actual = (DeepSeekChatOptions) calls.getAllValues().getFirst().getOptions();
        assertEquals("light", actual.getModel());
        assertEquals(2048, actual.getMaxTokens());
        assertEquals(options.getThinking(), actual.getThinking());
        assertEquals(0.3, actual.getTemperature());
        assertEquals(java.util.List.of("END"), actual.getStop());
        var next = (DeepSeekChatOptions) calls.getAllValues().get(1).getOptions();
        assertNull(next.getMaxTokens());
        assertNull(next.getThinking());
        assertEquals(0.1, next.getTemperature());
        var stream = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).stream(stream.capture());
        assertEquals(actual, stream.getValue().getOptions());
        assertEquals("unwanted-model", options.getModel());
    }
}
