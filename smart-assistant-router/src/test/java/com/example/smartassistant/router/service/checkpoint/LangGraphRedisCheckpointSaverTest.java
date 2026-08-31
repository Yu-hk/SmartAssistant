package com.example.smartassistant.router.service.checkpoint;

import com.example.smartassistant.common.interceptor.EnableServiceInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangGraphRedisCheckpointSaverTest {

    @Test
    void isNotProxiedByServiceInterceptorsAndKeepsNativeLockInitialized() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            AopConfigUtils.registerAspectJAutoProxyCreatorIfNecessary(context);
            context.register(CheckpointAopConfiguration.class);
            context.refresh();

            LangGraphRedisCheckpointSaver saver = context.getBean(LangGraphRedisCheckpointSaver.class);
            assertThat(AopUtils.isAopProxy(saver)).isFalse();
            RunnableConfig config = RunnableConfig.builder().threadId("aop-lock-check").build();
            assertThat(saver.get(config)).isEmpty();
        }
    }

    @Test
    void storesLoadsUpdatesAndReleasesNativeCheckpoints() throws Exception {
        LangGraphRedisCheckpointSaver saver = new LangGraphRedisCheckpointSaver(null);
        RunnableConfig config = RunnableConfig.builder().threadId("thread-1").build();
        Checkpoint checkpoint = Checkpoint.builder()
                .state(Map.of("completedIds", java.util.List.of("a")))
                .nodeId("a").nextNodeId("b").build();

        RunnableConfig stored = saver.put(config, checkpoint);

        assertThat(saver.get(config)).get().satisfies(restored -> {
            assertThat(restored.getNodeId()).isEqualTo("a");
            assertThat(restored.getNextNodeId()).isEqualTo("b");
        });
        Checkpoint updated = checkpoint.updateState(
                Map.of("phase", "resumed"), Map.of(), "c");
        saver.put(stored, updated);
        assertThat(saver.get(stored)).get()
                .extracting(Checkpoint::getNextNodeId).isEqualTo("c");
        assertThat(saver.findStale(System.currentTimeMillis(), 10))
                .extracting(LangGraphRedisCheckpointSaver.StaleCheckpoint::threadId)
                .containsExactly("thread-1");
        assertThat(saver.lastUpdated("thread-1")).isPresent();

        saver.release(config);
        assertThat(saver.get(config)).isEmpty();
        assertThat(saver.findStale(System.currentTimeMillis(), 10)).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableServiceInterceptor(basePackages = "com.example.smartassistant.router.service")
    static class CheckpointAopConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LangGraphRedisCheckpointSaver langGraphRedisCheckpointSaver() {
            return new LangGraphRedisCheckpointSaver(null);
        }
    }
}
