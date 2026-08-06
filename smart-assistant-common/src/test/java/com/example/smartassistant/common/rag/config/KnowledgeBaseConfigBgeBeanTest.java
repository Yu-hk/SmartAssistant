package com.example.smartassistant.common.rag.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseConfigBgeBeanTest {

    @Test
    void missingOnnxFileCreatesUnavailableModelInsteadOfFailingBeanCreation() {
        var configuration = new KnowledgeBaseConfig();

        try (var model = configuration.bgeEmbeddingModel(
                "missing-test-model.onnx",
                "missing-test-tokenizer.json")) {
            assertThat(model).isNotNull();
            assertThat(model.isAvailable()).isFalse();
        }
    }
}
