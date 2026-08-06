package com.example.smartassistant.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosServiceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import static org.assertj.core.api.Assertions.assertThat;

class NacosMetadataConfigurerConditionTest {

    @Test
    void loadsOnlyWhenNacosRegistrationInfrastructureExists() {
        ConditionalOnBean condition = NacosMetadataConfigurer.class.getAnnotation(ConditionalOnBean.class);

        assertThat(condition).isNotNull();
        assertThat(condition.value()).containsExactlyInAnyOrder(
                NacosConfigManager.class, NacosServiceRegistry.class, NacosRegistration.class);
    }
}
