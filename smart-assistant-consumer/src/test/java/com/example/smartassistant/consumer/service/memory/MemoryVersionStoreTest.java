/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryVersionStore 冲突版本留存测试（对标文章①）。
 */
class MemoryVersionStoreTest {

    @TempDir
    Path tempDir;

    private MemoryVersionStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryVersionStore();
        ReflectionTestUtils.setField(store, "basePath", tempDir.toString());
    }

    @Test
    @DisplayName("新增记忆 → ACTIVE v1")
    void testAddNew() {
        String id = store.add(1L, "FOOD_PREF", "辣", "偏好", MemorySource.INFERRED);
        assertNotNull(id);
        List<MemoryEntry> active = store.getActive(1L, "FOOD_PREF");
        assertEquals(1, active.size());
        assertEquals("辣", active.get(0).key());
        assertEquals(1, active.get(0).version());
        assertEquals(MemoryStatus.ACTIVE, active.get(0).status());
    }

    @Test
    @DisplayName("相同取值重复写入 → 不产生新版本（仍为 v1）")
    void testAddSameValueNoNewVersion() {
        store.add(1L, "FOOD_PREF", "辣", "偏好", MemorySource.INFERRED);
        store.add(1L, "FOOD_PREF", "辣", "偏好", MemorySource.INFERRED);
        assertEquals(1, store.getHistory(1L).size());
        assertEquals(1, store.getActive(1L, null).size());
    }

    @Test
    @DisplayName("冲突：INFERRED 旧值 被 EXPLICIT 新值覆盖 → 旧版 SUPERSEDED，新版 ACTIVE v2")
    void testConflictHigherSourceWins() {
        store.add(1L, "FOOD_PREF", "辣", "偏好", MemorySource.INFERRED);
        String newId = store.add(1L, "FOOD_PREF", "辣", "不偏好", MemorySource.EXPLICIT);

        // 仅 1 条 ACTIVE（新版）
        List<MemoryEntry> active = store.getActive(1L, null);
        assertEquals(1, active.size());
        assertEquals(newId, active.get(0).id());
        assertEquals("不偏好", active.get(0).value());
        assertEquals(2, active.get(0).version());

        // 血缘内共 2 个版本，旧版已 SUPERSEDED（版本留存，未物理删除）
        List<MemoryEntry> versions = store.getAllVersions(1L, active.get(0).lineageId());
        assertEquals(2, versions.size());
        assertTrue(versions.stream().anyMatch(e -> e.status() == MemoryStatus.SUPERSEDED
                && "偏好".equals(e.value()) && e.supersededBy().equals(newId)));
    }

    @Test
    @DisplayName("冲突：EXPLICIT 旧值 优先于 INFERRED 新值 → 旧版保留，新版被丢弃（不污染历史）")
    void testConflictLowerSourceLoses() {
        store.add(1L, "BUDGET", "预算范围", "经济实惠", MemorySource.EXPLICIT);
        store.add(1L, "BUDGET", "预算范围", "高端消费", MemorySource.INFERRED);

        List<MemoryEntry> active = store.getActive(1L, "BUDGET");
        assertEquals(1, active.size());
        assertEquals("经济实惠", active.get(0).value());
        // 低优先级写入被丢弃，历史仅 1 条
        assertEquals(1, store.getHistory(1L).size());
    }

    @Test
    @DisplayName("时间优先：同来源取值改变 → 新值覆盖，旧版 SUPERSEDED，版本递增")
    void testTimePrioritySameSource() {
        store.add(1L, "TRAVEL_PREF", "自然风光", "偏好", MemorySource.INFERRED);
        String newId = store.add(1L, "TRAVEL_PREF", "自然风光", "不偏好", MemorySource.INFERRED);

        List<MemoryEntry> active = store.getActive(1L, null);
        assertEquals(1, active.size());
        assertEquals(newId, active.get(0).id());
        assertEquals("不偏好", active.get(0).value());
        assertEquals(2, active.get(0).version());

        List<MemoryEntry> versions = store.getAllVersions(1L, active.get(0).lineageId());
        assertEquals(2, versions.size());
    }
}
