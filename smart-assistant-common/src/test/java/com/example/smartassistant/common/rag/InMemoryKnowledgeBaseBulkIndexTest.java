package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
}
