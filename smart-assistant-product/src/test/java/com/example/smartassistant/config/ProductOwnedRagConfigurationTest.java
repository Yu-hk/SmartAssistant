package com.example.smartassistant.config;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.common.rag.pipeline.RagSearchPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductOwnedRagConfigurationTest {

    @Test
    void assemblesProductPipelineFromModuleHandlers() {
        RagSearchHandler handler = mock(RagSearchHandler.class);

        RagSearchPipeline pipeline = new ProductRagPipelineConfig()
                .productRagSearchPipeline(List.of(handler));

        assertThat(pipeline.getHandlers()).containsExactly(handler);
    }

    @Test
    void assemblesProductKnowledgeGraphWithManagedAiService() {
        ProductRagGraphConfiguration configuration = new ProductRagGraphConfiguration();

        KnowledgeGraphService graph = configuration.productKnowledgeGraphService(
                mock(ChatModel.class),
                AiChatService.governedDefaults());

        assertThat(graph).isNotNull();
    }
}
