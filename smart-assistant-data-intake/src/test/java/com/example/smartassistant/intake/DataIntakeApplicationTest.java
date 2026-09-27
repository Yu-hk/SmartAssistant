package com.example.smartassistant.intake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.example.smartassistant.intake.controller.AdminProductFeatureController;
import com.example.smartassistant.intake.controller.AdminProductIntakeController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:intake_boot;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class DataIntakeApplicationTest {
    @Autowired ApplicationContext context;

    @Test
    void startsWithExtractedControllers() {
        assertThat(context.getBean(AdminProductIntakeController.class)).isNotNull();
        assertThat(context.getBean(AdminProductFeatureController.class)).isNotNull();
    }
}
