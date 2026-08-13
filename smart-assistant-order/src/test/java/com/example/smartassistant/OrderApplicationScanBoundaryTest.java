package com.example.smartassistant;

import com.example.smartassistant.config.OrderAgentConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderApplicationScanBoundaryTest {

    @Test
    void applicationDoesNotScanTheSharedRootPackage() {
        var annotation = OrderServiceApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(Set.of(annotation.scanBasePackages()))
                .doesNotContain("com.example.smartassistant")
                .contains("com.example.smartassistant.order", "com.example.smartassistant.common");
        assertThat(OrderAgentConfig.class.isAnnotationPresent(ComponentScan.class)).isFalse();
    }
}
