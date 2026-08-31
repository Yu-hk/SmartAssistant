package com.example.smartassistant.product.tool;

import com.example.smartassistant.common.tool.spi.ProductDataProvider;
import com.example.smartassistant.service.core.ProductDiscoveryService;
import com.example.smartassistant.spi.InMemoryProductBackend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductToolsTest {

    @Test
    void exposesLiveCatalogCategoriesAsATool() {
        InMemoryProductBackend backend = new InMemoryProductBackend() {
            @Override
            public List<String> listProductCategories() {
                return List.of("投影仪");
            }
        };
        ProductTools tools = new ProductTools(mock(ProductDataProvider.class),
                new ProductDiscoveryService(backend));

        assertThat(tools.listProductCategories()).isEqualTo("当前商品类型：投影仪");
    }
}
