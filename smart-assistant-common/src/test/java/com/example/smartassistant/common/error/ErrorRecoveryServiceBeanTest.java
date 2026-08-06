package com.example.smartassistant.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorRecoveryServiceBeanTest {

    @Test
    void isAvailableAsSpringBean() {
        try (var context = new AnnotationConfigApplicationContext(ErrorRecoveryService.class)) {
            assertThat(context.getBean(ErrorRecoveryService.class)).isNotNull();
        }
    }
}
