package com.example.smartassistant.router.service.checkpoint;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangGraphRedisCheckpointSaverTest {

    @Test
    void storesLoadsUpdatesAndReleasesNativeCheckpoints() throws Exception {
        LangGraphRedisCheckpointSaver saver = new LangGraphRedisCheckpointSaver(null);
        RunnableConfig config = RunnableConfig.builder().threadId("thread-1").build();
        Checkpoint checkpoint = Checkpoint.builder()
                .state(Map.of("completedIds", java.util.List.of("a")))
                .nodeId("a").nextNodeId("b").build();

        RunnableConfig stored = saver.put(config, checkpoint);

        assertThat(saver.get(config)).get().satisfies(restored -> {
            assertThat(restored.getNodeId()).isEqualTo("a");
            assertThat(restored.getNextNodeId()).isEqualTo("b");
        });
        Checkpoint updated = checkpoint.updateState(
                Map.of("phase", "resumed"), Map.of(), "c");
        saver.put(stored, updated);
        assertThat(saver.get(stored)).get()
                .extracting(Checkpoint::getNextNodeId).isEqualTo("c");

        saver.release(config);
        assertThat(saver.get(config)).isEmpty();
    }
}
