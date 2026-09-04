package com.example.smartassistant.common.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeVersionManagerTest {

    @Test
    void incrementsAndRefreshesSharedVersion() {
        AtomicLong shared = new AtomicLong(3L);
        KnowledgeVersionManager manager = new KnowledgeVersionManager(
                shared::get, shared::incrementAndGet);

        assertThat(manager.refreshCurrentVersion()).isEqualTo(3L);
        assertThat(manager.incrementVersion()).isEqualTo(4L);
        assertThat(shared).hasValue(4L);
    }
}
