package com.example.smartassistant.order.infrastructure.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TaskLogServiceSpringContextTest {

    @Test
    void selectsProductionConstructorWhenRegisteredAsSpringService() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(StringRedisTemplate.class,
                    () -> mock(StringRedisTemplate.class));
            context.registerBean(DistributedLock.class,
                    () -> mock(DistributedLock.class));
            context.register(TaskLogService.class);

            context.refresh();

            assertThat(context.getBean(TaskLogService.class)).isNotNull();
        }
    }
}
