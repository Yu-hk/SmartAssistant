package com.example.smartassistant;

import com.example.smartassistant.config.ProductAgentConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductApplicationScanBoundaryTest {

    @Test
    void applicationDoesNotScanTheSharedRootPackage() {
        var annotation = ProductServiceApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(Set.of(annotation.scanBasePackages()))
                .doesNotContain("com.example.smartassistant")
                .contains("com.example.smartassistant.product", "com.example.smartassistant.common");
        assertThat(ProductAgentConfig.class.isAnnotationPresent(ComponentScan.class)).isFalse();
    }
}
