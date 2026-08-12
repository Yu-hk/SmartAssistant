package com.example.smartassistant.common.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CacheVersionManagerTest {

    @Test
    void refreshBypassesLocalVersionSnapshot() {
        AtomicLong sharedVersion = new AtomicLong(7L);
        CacheVersionManager manager = new CacheVersionManager(
                sharedVersion::get,
                sharedVersion::set);

        assertThat(manager.getCurrentVersion()).isEqualTo(7L);
        sharedVersion.set(8L);

        assertThat(manager.getCurrentVersion()).isEqualTo(7L);
        assertThat(manager.refreshCurrentVersion()).isEqualTo(8L);
        assertThat(manager.getCurrentVersion()).isEqualTo(8L);
    }
}
