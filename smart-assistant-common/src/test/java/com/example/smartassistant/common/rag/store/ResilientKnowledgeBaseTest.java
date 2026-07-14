/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.store;

import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResilientKnowledgeBase 单元测试——验证 PG 不可用时降级内存、检索链路不中断（红线：PgVector 不可用不中断）。
 */
@ExtendWith(MockitoExtension.class)
class ResilientKnowledgeBaseTest {

    @Mock
    private KnowledgeBase primary;

    @Mock
    private KnowledgeBase fallback;

    private static KnowledgeDocument doc(String id) {
        return new KnowledgeDocument(id, "t", "c", "cat", "k", -1, -1);
    }

    @Test
    void searchDegradesToFallbackWhenPrimaryFails() {
        when(primary.search(anyString(), anyInt(), any(AclContext.class)))
                .thenThrow(new RuntimeException("PG down"));
        KnowledgeHit hit = new KnowledgeHit(doc("d1"), 0.9);
        when(fallback.search(anyString(), anyInt(), any(AclContext.class)))
                .thenReturn(List.of(hit));

        ResilientKnowledgeBase rkb = new ResilientKnowledgeBase("rkb", primary, fallback);
        List<KnowledgeHit> result = rkb.search("q", 5, AclContext.forTenant("t"));

        assertFalse(result.isEmpty(), "PG 不可用时应从内存降级返回结果，不中断调用方");
        assertEquals("d1", result.get(0).getDocument().getId());
    }

    @Test
    void searchReturnsEmptyGracefullyWhenBothFail() {
        when(primary.search(anyString(), anyInt(), any(AclContext.class)))
                .thenThrow(new RuntimeException("PG down"));

        ResilientKnowledgeBase rkb = new ResilientKnowledgeBase("rkb", primary, null);
        List<KnowledgeHit> result = rkb.search("q", 5, AclContext.forTenant("t"));

        assertTrue(result.isEmpty(), "主库与降级库均不可用时，应优雅返回空而非抛异常中断链路");
    }

    @Test
    void addDocumentMirrorsToFallbackWhenPrimaryFails() {
        KnowledgeDocument d = doc("d1");
        doThrow(new RuntimeException("PG write fail")).when(primary).addDocument(any());

        ResilientKnowledgeBase rkb = new ResilientKnowledgeBase("rkb", primary, fallback);
        rkb.addDocument(d); // 不应抛异常

        verify(fallback).addDocument(d);
    }

    @Test
    void forceDegradedAfterConsecutiveFailures() {
        when(primary.search(anyString(), anyInt(), any(AclContext.class)))
                .thenThrow(new RuntimeException("down"));

        ResilientKnowledgeBase rkb = new ResilientKnowledgeBase("rkb", primary, fallback, true, 3);
        for (int i = 0; i < 3; i++) {
            rkb.search("q", 5, AclContext.forTenant("t"));
        }
        assertTrue(rkb.isDegraded(), "连续失败达到阈值应进入强制降级（只读内存快照）");
    }
}
