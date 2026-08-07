package com.example.smartassistant.common.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ToolLogAspectConditionalTest {

    @Test
    void isNotRegisteredWhenAspectjIsMissing() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.aspectj"))
                .withUserConfiguration(ToolLogAspect.class)
                .run(context -> assertThat(context).doesNotHaveBean(ToolLogAspect.class));
    }
}
