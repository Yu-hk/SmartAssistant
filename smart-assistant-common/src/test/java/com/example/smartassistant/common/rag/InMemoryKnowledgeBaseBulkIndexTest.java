package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InMemoryKnowledgeBaseBulkIndexTest {

    @Test
    void bulkInitializationEmbedsEachDocumentExactlyOnce() {
        BgeEmbeddingModel embeddingModel = mock(BgeEmbeddingModel.class);
        when(embeddingModel.embedding(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        InMemoryKnowledgeBase knowledgeBase = new InMemoryKnowledgeBase(
                "test", embeddingModel, null, Reranker.identity());

        List<KnowledgeDocument> documents = List.of(
                new KnowledgeDocument("doc-1", "标题一", "正文一", "测试", "一", 0, 0),
                new KnowledgeDocument("doc-2", "标题二", "正文二", "测试", "二", 0, 0));

        knowledgeBase.addDocumentsAndBuildIndexes(documents);

        assertThat(knowledgeBase.size()).isEqualTo(2);
        verify(embeddingModel, times(2)).embedding(anyString());
    }

    @Test
    void snapshotRefreshReusesUnchangedVectorsAndOnlyEmbedsChanges() {
        BgeEmbeddingModel embeddingModel = mock(BgeEmbeddingModel.class);
        when(embeddingModel.embedding(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        InMemoryKnowledgeBase knowledgeBase = new InMemoryKnowledgeBase(
                "test", embeddingModel, null, Reranker.identity());
        KnowledgeDocument original = new KnowledgeDocument(
                "doc-1", "标题", "正文", "测试", "关键词", 0, 0);

        knowledgeBase.addDocumentsAndBuildIndexes(List.of(original));
        clearInvocations(embeddingModel);
        knowledgeBase.replaceAll(List.of(original));

        verify(embeddingModel, never()).embedding(anyString());

        KnowledgeDocument changed = new KnowledgeDocument(
                "doc-1", "标题", "更新后的正文", "测试", "关键词", 0, 0);
        knowledgeBase.replaceAll(List.of(changed));

        assertThat(knowledgeBase.size()).isEqualTo(1);
        verify(embeddingModel, times(1)).embedding(anyString());
    }
}
