package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryChatMemoryTest {

    @Test
    void springAiMessageWindowShouldEvictOldestMessages() {
        InMemoryChatMemory memory = new InMemoryChatMemory(2);

        memory.add("conversation", new UserMessage("one"));
        memory.add("conversation", new UserMessage("two"));
        memory.add("conversation", new UserMessage("three"));

        var messages = memory.get("conversation", 0);
        assertEquals(2, messages.size());
        assertEquals("two", messages.get(0).getText());
        assertEquals("three", messages.get(1).getText());

        memory.clear("conversation");
        assertTrue(memory.get("conversation", 0).isEmpty());
    }

    @Test
    void projectAdapterShouldStillSupportLastNView() {
        InMemoryChatMemory memory = new InMemoryChatMemory(5);
        memory.add("conversation", new UserMessage("one"));
        memory.add("conversation", new UserMessage("two"));
        memory.add("conversation", new UserMessage("three"));

        var messages = memory.get("conversation", 1);
        assertEquals(1, messages.size());
        assertEquals("three", messages.getFirst().getText());
    }
}
