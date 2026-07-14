/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * KnowledgeIndexMetaService 单元测试——验证 indexVersion 打标 + 过滤生效（REQ-2 / REQ-4）。
 * <p>内存模式（无 JdbcTemplate）退化为 AtomicReference 缓存；有 JdbcTemplate 时持久化。</p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIndexMetaServiceTest {

    @Test
    void inMemoryDefaultAndBump() {
        KnowledgeIndexMetaService svc = new KnowledgeIndexMetaService(); // 无 jdbc → 内存模式
        assertEquals("v1", svc.getActiveVersion(), "默认 active 版本应为 v1");
        svc.bump("v2");
        assertEquals("v2", svc.getActiveVersion(), "bump 后 active 版本应更新");
        assertEquals("v2", svc.stamp(null), "stamp 应返回当前 active 版本");
    }

    @Test
    void setActiveVersionPersistsWhenJdbcPresent(@Mock JdbcTemplate jdbc) {
        KnowledgeIndexMetaService svc = new KnowledgeIndexMetaService(jdbc);
        svc.setActiveVersion("v3");
        verify(jdbc).update(contains("UPDATE knowledge_index_meta"), eq("v3"), anyLong());
    }
}
