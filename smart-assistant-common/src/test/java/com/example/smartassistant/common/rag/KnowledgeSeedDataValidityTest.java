package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KnowledgeSeedDataValidityTest {
    @Test void everySeedIsEffectiveThirtyDaysAgoAndExpiresInOneYear() {
        long before = System.currentTimeMillis();
        var docs = Stream.concat(KnowledgeSeedData.orderDocuments().stream(),
                KnowledgeSeedData.productDocuments().stream()).toList();
        long after = System.currentTimeMillis();
        assertThat(docs).hasSize(17);
        for (var doc : docs) {
            assertThat(doc.getEffectiveAt()).as(doc.getId()).isBetween(
                    before - Duration.ofDays(30).toMillis(), after - Duration.ofDays(30).toMillis());
            assertThat(doc.getExpireAt()).as(doc.getId()).isBetween(
                    before + Duration.ofDays(365).toMillis(), after + Duration.ofDays(365).toMillis());
            assertThat(doc.isRetrievable()).as(doc.getId()).isTrue();
        }
    }
    @Test void orderSeedsAreImmediatelySearchable() { assertSearchable(KnowledgeSeedData.orderDocuments()); }
    @Test void productSeedsAreImmediatelySearchable() { assertSearchable(KnowledgeSeedData.productDocuments()); }
    private void assertSearchable(List<KnowledgeDocument> docs) {
        var model = mock(BgeEmbeddingModel.class);
        when(model.embedding(anyString())).thenReturn(new float[]{1, 0});
        var kb = new InMemoryKnowledgeBase("seed-contract", model, null, Reranker.identity());
        kb.addDocumentsAndBuildIndexes(docs);
        assertThat(kb.search("查询", 50)).extracting(h -> h.getDocument().getId())
                .containsExactlyInAnyOrderElementsOf(docs.stream().map(KnowledgeDocument::getId).toList());
    }
}
