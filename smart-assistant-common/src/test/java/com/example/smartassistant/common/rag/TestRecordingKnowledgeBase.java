/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 测试用内存知识库桩——记录治理方法的调用，便于断言非覆盖式版本行为。
 * <p>
 * 关键能力：
 * <ul>
 *   <li>记录 {@code removeDocument}/{@code removeByBaseDocId} 调用（验证非物理删除）</li>
 *   <li>记录 {@code markSupersededByBaseId} 调用（验证隔离/回滚触发）</li>
 *   <li>按文档 ID 追踪 {@link DocumentStatus}</li>
 * </ul>
 * {@code markSupersededByBaseId} 故意委托给接口默认实现，以覆盖默认逻辑。
 * </p>
 */
public class TestRecordingKnowledgeBase implements KnowledgeBase {

    final Map<String, KnowledgeDocument> docs = new ConcurrentHashMap<>();
    final Map<String, DocumentStatus> status = new ConcurrentHashMap<>();
    final List<String> removeByIdCalls = new ArrayList<>();
    final List<String> removeByBaseCalls = new ArrayList<>();
    final List<String> markSupersededBaseCalls = new ArrayList<>();

    @Override
    public String getName() {
        return "recording-kb";
    }

    @Override
    public void addDocument(KnowledgeDocument doc) {
        if (doc == null) return;
        docs.put(doc.getId(), doc);
        status.put(doc.getId(), doc.getDocumentStatus());
    }

    @Override
    public void removeDocument(String id) {
        removeByIdCalls.add(id);
        docs.remove(id);
        status.remove(id);
    }

    @Override
    public void removeByBaseDocId(String baseDocId) {
        removeByBaseCalls.add(baseDocId);
        docs.keySet().removeIf(k -> {
            KnowledgeDocument d = docs.get(k);
            return d != null && baseDocId.equals(d.getBaseDocId());
        });
    }

    @Override
    public List<String> listIdsByBaseDocId(String baseDocId) {
        if (baseDocId == null || baseDocId.isBlank()) return List.of();
        return docs.entrySet().stream()
                .filter(e -> baseDocId.equals(e.getValue().getBaseDocId()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @Override
    public void updateStatus(String docId, DocumentStatus s) {
        if (docId == null || docId.isBlank() || s == null) return;
        KnowledgeDocument old = docs.get(docId);
        if (old == null) return;
        status.put(docId, s);
        docs.put(docId, new KnowledgeDocument(
                old.getId(), old.getTitle(), old.getContent(),
                old.getCategory(), old.getKeywords(),
                old.getEffectiveAt(), old.getExpireAt(),
                old.getTenantId(), old.getVersion(),
                old.getSourceUrl(), old.getChunkIndex(),
                old.getParentDocId(), old.getAuthorityLevel(), s));
    }

    @Override
    public void markSupersededByBaseId(String baseDocId, String keepDocId) {
        markSupersededBaseCalls.add(baseDocId);
        KnowledgeBase.super.markSupersededByBaseId(baseDocId, keepDocId);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        return docs.values().stream()
                .filter(KnowledgeDocument::isRetrievable)
                .map(d -> new KnowledgeHit(d, 1.0))
                .collect(Collectors.toList());
    }

    @Override
    public int size() {
        return docs.size();
    }

    @Override
    public void reindex() {
        // no-op
    }

    /** 读取某文档当前状态（测试断言用） */
    public DocumentStatus statusOf(String id) {
        return status.get(id);
    }
}
