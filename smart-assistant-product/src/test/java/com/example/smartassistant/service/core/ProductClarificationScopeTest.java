package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductClarificationScopeTest {
    @Test void portableRecommendationIsAQualitativePreferenceNotAWeightQuestion() {
        var backend = mock(ProductBackend.class);
        when(backend.listProductCategories()).thenReturn(List.of("笔记本电脑"));
        when(backend.listPopularProducts(any(ProductBackend.ProductDiscoveryCriteria.class))).thenReturn(List.of());
        var service = new ProductDiscoveryService(backend);
        var result = service.discover("推荐便携笔记本，预算3000元", 5);
        assertFalse(result.clarificationRequired());
        assertTrue(result.missingFields().isEmpty());
        verify(backend).listPopularProducts(argThat(criteria -> criteria.keyword().contains("便携")));
        assertTrue(service.discover("推荐笔记本，重量不超过3公斤，预算3000元", 5).missingFields().isEmpty());
    }
    @Test void unsupportedFeatureQuestionsStayTextInsteadOfInventingFields() {
        assertTrue(ProductFeatureRequest.parse("推荐长续航耳机").missingFields().isEmpty());
        assertTrue(ProductFeatureRequest.parse("推荐便携长续航笔记本").missingFields().isEmpty());
    }
}
