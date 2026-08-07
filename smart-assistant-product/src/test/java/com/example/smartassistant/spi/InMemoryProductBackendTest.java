package com.example.smartassistant.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryProductBackendTest {

    private final InMemoryProductBackend backend = new InMemoryProductBackend();

    @Test
    void matchesCodeCaseInsensitively() {
        assertThat(backend.queryProductInfo("macbook-air-m3"))
                .contains("MacBook Air M3")
                .contains("8999");
    }

    @Test
    void matchesProductNameInsideNaturalLanguage() {
        assertThat(backend.searchProduct("请查询一下 MacBook Air M3 的价格和库存"))
                .contains("MacBook Air M3")
                .doesNotContain("未找到");
    }
}
