package com.example.smartassistant.embedding;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingApplicationContextTest {

    @TempDir
    Path directory;

    @Test
    void componentScanIsRestrictedToTheEmbeddingService() {
        ComponentScan scan = AnnotationUtils.findAnnotation(EmbeddingApplication.class, ComponentScan.class);

        assertNotNull(scan);
        assertEquals(Set.of("com.example.smartassistant.embedding"),
                Set.copyOf(Arrays.asList(scan.basePackages())));
    }

    @Test
    void startsTheRealApplicationWithOneModelBeanAndConfiguredPaths() {
        String modelPath = directory.resolve("not-a-real-model.onnx").toString();
        String vocabPath = directory.resolve("not-a-real-tokenizer.json").toString();
        try (MockedConstruction<BgeEmbeddingModel> construction = mockConstruction(
                BgeEmbeddingModel.class, (model, invocation) -> {
                    assertEquals(List.of(modelPath, vocabPath), invocation.arguments());
                    when(model.isAvailable()).thenReturn(true);
                    when(model.dimensions()).thenReturn(1024);
                });
             ConfigurableApplicationContext context = startIsolatedApplication(
                     WebApplicationType.NONE, modelPath, vocabPath)) {
            assertTrue(context.isActive());
            assertEquals(1, construction.constructed().size());
            assertEquals(Set.of("bgeEmbeddingModel"), context.getBeansOfType(BgeEmbeddingModel.class).keySet());
            BgeEmbeddingModel model = construction.constructed().getFirst();
            assertSame(model, context.getBean(BgeEmbeddingModel.class));
            assertSame(model, ReflectionTestUtils.getField(context.getBean(EmbeddingController.class), "embeddingModel"));
            assertEquals(1024, context.getBean(EmbeddingController.class).dimensions().get("dimensions"));
        }
    }

    @Test
    void startsWithoutImportingLocalSecretsOrRegisteringExternalClientsAndSiblingComponents() {
        try (MockedConstruction<BgeEmbeddingModel> construction = mockConstruction(BgeEmbeddingModel.class);
             ConfigurableApplicationContext context = startIsolatedApplication(
                     WebApplicationType.NONE, directory.resolve("absent.onnx").toString(),
                     directory.resolve("absent-tokenizer.json").toString())) {
            assertEquals(1, construction.constructed().size());
            assertEquals("optional:classpath:/embedding-contract-test-isolated.properties",
                    context.getEnvironment().getProperty("spring.config.location"));
            assertEquals("", context.getEnvironment().getProperty("spring.config.import"));
            assertEquals("false", context.getEnvironment().getProperty("spring.cloud.nacos.discovery.enabled"));
            assertEquals("false", context.getEnvironment().getProperty("spring.cloud.nacos.config.enabled"));
            assertEquals("false", context.getEnvironment().getProperty("spring.cloud.service-registry.auto-registration.enabled"));
            assertFalse(context.containsBean("dataSource"));

            for (String beanName : context.getBeanDefinitionNames()) {
                Class<?> type = context.getType(beanName);
                if (type == null) continue;
                String className = type.getName();
                // Shared @AutoConfiguration imports are legitimate; this boundary concerns component scanning.
                if (context.getBeanFactory().getBeanDefinition(beanName) instanceof ScannedGenericBeanDefinition) {
                    assertTrue(className.startsWith("com.example.smartassistant.embedding."),
                            () -> "Unexpected scanned shared/sibling component: " + beanName + " (" + className + ")");
                }
                assertFalse(className.endsWith("NacosServiceRegistry") || className.endsWith("NacosDiscoveryClient"),
                        () -> "External service client was registered: " + className);
            }
        }
    }

    @Test
    void unavailableModelKeepsTheApplicationRunningInDegradedMode() {
        try (MockedConstruction<BgeEmbeddingModel> construction = mockConstruction(
                BgeEmbeddingModel.class, (model, invocation) -> {
                    when(model.isAvailable()).thenReturn(false);
                    when(model.dimensions()).thenReturn(384);
                });
             ConfigurableApplicationContext context = startIsolatedApplication(
                     WebApplicationType.NONE, directory.resolve("absent.onnx").toString(),
                     directory.resolve("absent-tokenizer.json").toString())) {
            assertTrue(context.isActive());
            assertEquals(1, construction.constructed().size());
            assertEquals("DOWN", context.getBean(EmbeddingController.class).health().get("status"));
            assertEquals(false, context.getBean(EmbeddingController.class).health().get("available"));
            assertEquals(384, context.getBean(EmbeddingController.class).dimensions().get("dimensions"));
        }
    }

    /** Command-line properties outrank application.yml, environment model paths and .env imports. */
    static ConfigurableApplicationContext startIsolatedApplication(
            WebApplicationType webType, String modelPath, String vocabPath) {
        return new SpringApplicationBuilder(EmbeddingApplication.class)
                .web(webType)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .run(
                        "--spring.config.location=optional:classpath:/embedding-contract-test-isolated.properties",
                        "--spring.config.import=",
                        "--spring.main.banner-mode=off",
                        "--spring.cloud.nacos.discovery.enabled=false",
                        "--spring.cloud.nacos.config.enabled=false",
                        "--spring.cloud.nacos.config.import-check.enabled=false",
                        "--spring.cloud.discovery.enabled=false",
                        "--spring.cloud.service-registry.auto-registration.enabled=false",
                        "--spring.cloud.compatibility-verifier.enabled=false",
                        "--otel.sdk.disabled=true",
                        "--otel.traces.exporter=none",
                        "--otel.metrics.exporter=none",
                        "--otel.logs.exporter=none",
                        "--management.otlp.metrics.export.enabled=false",
                        "--management.tracing.enabled=false",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--server.shutdown=immediate",
                        "--bge.model.path=" + modelPath,
                        "--bge.vocab.path=" + vocabPath);
    }
}
