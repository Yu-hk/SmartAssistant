package com.example.smartassistant.user;

import com.example.smartassistant.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceApplicationTest {

    @Test
    void scansOnlyUserComponentsAndImportsTheSharedExceptionHandler() {
        assertThat(UserServiceApplication.class.getDeclaredAnnotation(ComponentScan.class)).isNull();

        Import imported = UserServiceApplication.class.getDeclaredAnnotation(Import.class);
        assertThat(imported).isNotNull();
        assertThat(imported.value()).contains(GlobalExceptionHandler.class);
    }
}
