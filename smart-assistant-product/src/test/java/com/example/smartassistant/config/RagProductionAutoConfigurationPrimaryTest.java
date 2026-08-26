package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.Reranker;
import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import com.example.smartassistant.common.rag.store.KnowledgeIndexMetaService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RagProductionAutoConfigurationPrimaryTest {

    @Test
    void productKnowledgeBaseIsThePrimaryKnowledgeBase() {
        var method = Arrays.stream(RagProductionAutoConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("productKnowledgeBase"))
                .findFirst()
                .orElseThrow();

        assertThat(method.getAnnotation(Primary.class)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void productKnowledgeBaseDoesNotEmbedSeedsDuringBeanCreation() {
        RagProductionAutoConfiguration configuration = new RagProductionAutoConfiguration();
        BgeEmbeddingModel embeddingModel = mock(BgeEmbeddingModel.class);
        ChineseTokenizer tokenizer = mock(ChineseTokenizer.class);
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        ObjectProvider<KnowledgeIndexMetaService> indexMetaProvider = mock(ObjectProvider.class);
        ObjectProvider<Reranker> rerankerProvider = mock(ObjectProvider.class);
        RagProductionProperties properties = new RagProductionProperties();
        properties.getRag().getStore().setMode("memory");

        KnowledgeBase result = configuration.productKnowledgeBase(
                embeddingModel, tokenizer, jdbcProvider, indexMetaProvider, rerankerProvider, properties);

        assertThat(result).isNotNull();
        assertThat(result.size()).isZero();
        verifyNoInteractions(embeddingModel);
    }
}
