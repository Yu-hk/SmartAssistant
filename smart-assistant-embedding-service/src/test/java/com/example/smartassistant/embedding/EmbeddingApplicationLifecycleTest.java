package com.example.smartassistant.embedding;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.CommonAnnotationBeanPostProcessor;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EmbeddingApplicationLifecycleTest {

    @TempDir
    Path directory;

    @Test
    void closingTheRealApplicationReleasesAnAvailableModelExactlyOnce() {
        assertModelReleasedOnShutdown(true);
    }

    @Test
    void closingTheRealApplicationReleasesAnUnavailableModelExactlyOnce() {
        assertModelReleasedOnShutdown(false);
    }

    @Test
    void preDestroyIsInvokedBySpringEvenWhenTheModelWasNeverInitialized() {
        EmbeddingApplication application = spy(new EmbeddingApplication());
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(CommonAnnotationBeanPostProcessor.class);
            context.registerBean(EmbeddingApplication.class, () -> application);
            context.refresh();
            verify(application, never()).cleanup();

            context.close();

            verify(application, times(1)).cleanup();
            verify(application, never()).bgeEmbeddingModel();
            context.close();
            verify(application, times(1)).cleanup();
        }
    }

    private void assertModelReleasedOnShutdown(boolean available) {
        try (MockedConstruction<BgeEmbeddingModel> construction = mockConstruction(
                BgeEmbeddingModel.class, (model, invocation) -> {
                    when(model.isAvailable()).thenReturn(available);
                    when(model.dimensions()).thenReturn(1024);
                });
             ConfigurableApplicationContext context = EmbeddingApplicationContextTest.startIsolatedApplication(
                     WebApplicationType.NONE, directory.resolve("absent.onnx").toString(),
                     directory.resolve("absent-tokenizer.json").toString())) {
            assertEquals(1, construction.constructed().size());
            BgeEmbeddingModel model = construction.constructed().getFirst();
            verify(model, never()).close();

            // The actual Spring shutdown must invoke the production @PreDestroy method.
            context.close();

            verify(model, times(1)).close();
            context.close();
            verify(model, times(1)).close();
        }
    }
}
