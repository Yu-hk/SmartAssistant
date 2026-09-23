package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductClarificationScopeTest {
    @Test void portableRecommendationOnlyAsksForItsUnresolvedWeight() {
        var backend = mock(ProductBackend.class);
        when(backend.listProductCategories()).thenReturn(List.of("笔记本电脑"));
        var service = new ProductDiscoveryService(backend);
        var result = service.discover("推荐便携笔记本，预算3000元", 5);
        assertTrue(result.clarificationRequired());
        assertEquals(List.of("weight"), result.missingFields());
        assertTrue(service.discover("推荐笔记本，重量不超过3公斤，预算3000元", 5).missingFields().isEmpty());
    }
    @Test void unsupportedFeatureQuestionsStayTextInsteadOfInventingFields() {
        assertTrue(ProductFeatureRequest.parse("推荐长续航耳机").missingFields().isEmpty());
        assertTrue(ProductFeatureRequest.parse("推荐便携长续航笔记本").missingFields().isEmpty());
    }
}
