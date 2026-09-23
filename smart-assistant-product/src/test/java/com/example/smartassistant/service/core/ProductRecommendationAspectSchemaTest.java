package com.example.smartassistant.service.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRecommendationAspectSchemaTest {
    @Test void onlyGroundedPreferencesAreMentionedAsRelatedSpecifications() {
        var schema = ProductRecommendationAspectSchema.defaultSchema();
        assertThat(schema.relatedLabels("想要轻便的商务笔记本", "净重1.2kg，商务办公设计"))
                .contains("便携性", "商务用途");
        assertThat(schema.relatedLabels("想要轻便的商务笔记本", "16GB 内存"))
                .isEmpty();
    }
}
