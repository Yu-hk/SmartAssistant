package com.example.smartassistant.router.exception;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerBeanNameTest {

    @Test
    void routerAndCommonHandlersCanCoexistInTheSameApplicationContext() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(
                    GlobalExceptionHandler.class,
                    com.example.smartassistant.common.exception.GlobalExceptionHandler.class);

            context.refresh();

            assertThat(context.containsBean("routerGlobalExceptionHandler")).isTrue();
            assertThat(context.containsBean("globalExceptionHandler")).isTrue();
        }
    }
}
