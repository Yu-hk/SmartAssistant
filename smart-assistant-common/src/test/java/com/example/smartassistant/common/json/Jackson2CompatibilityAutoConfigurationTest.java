package com.example.smartassistant.common.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class Jackson2CompatibilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Jackson2CompatibilityAutoConfiguration.class));

    @Test
    void createsJackson2MapperWhenBootDoesNotProvideOne() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ObjectMapper.class));
    }

    @Test
    void backsOffWhenApplicationProvidesJackson2Mapper() {
        contextRunner
                .withBean("applicationObjectMapper", ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectMapper.class);
                    assertThat(context).hasBean("applicationObjectMapper");
                    assertThat(context).doesNotHaveBean("jackson2ObjectMapper");
                });
    }
}
