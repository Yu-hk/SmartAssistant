package com.example.smartassistant.user;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserServiceApplicationComponentScanTest {

    @Test
    void scansUserServiceAndCommonPackages() {
        ComponentScan componentScan = AnnotationUtils.findAnnotation(
                UserServiceApplication.class, ComponentScan.class);

        assertNotNull(componentScan);
        assertEquals(
                Set.of("com.example.smartassistant.user", "com.example.smartassistant.common"),
                Set.copyOf(Arrays.asList(componentScan.basePackages())));
    }
}
