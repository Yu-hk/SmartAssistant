package com.example.smartassistant.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class OpsMetricsBeanTest {

    @Test
    void usesApplicationMeterRegistryWhenManagedBySpring() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var registry = new SimpleMeterRegistry();
            context.getBeanFactory().registerSingleton("meterRegistry", registry);
            context.register(OpsMetrics.class);
            context.refresh();

            var metrics = context.getBean(OpsMetrics.class);
            metrics.recordAnswer("router", "test");

            assertThat(registry.get("a2a_answers_total").counter().count()).isEqualTo(1.0);
        }
    }
}
