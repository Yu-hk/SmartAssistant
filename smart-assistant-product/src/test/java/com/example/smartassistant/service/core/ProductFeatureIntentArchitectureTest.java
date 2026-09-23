package com.example.smartassistant.service.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductFeatureIntentArchitectureTest {
    private final ProductFeatureSchema schema = ProductFeatureSchema.defaultSchema();
    private final ProductFeatureIntentParser parser = new RuleBasedProductFeatureIntentParser(schema);

    @Test
    void schemaSeparatesQualitativePreferencesFromQuantitativeConstraints() {
        assertThat(schema.portability().kind()).isEqualTo(ProductFeatureSchema.Kind.QUALITATIVE);
        assertThat(schema.weight().kind()).isEqualTo(ProductFeatureSchema.Kind.QUANTITATIVE);

        ProductFeatureIntent intent = parser.parse("推荐一款轻便、便携的笔记本");
        assertThat(intent.qualitativePreferences()).containsExactly("portability");
        assertThat(intent.weightMentioned()).isFalse();
        assertThat(intent.maxWeightGrams()).isNull();
        assertThat(parser.parse("适合商务办公的电脑").qualitativePreferences()).contains("business");
    }

    @Test
    void understandingCanBeReplacedWithoutChangingDeterministicValidation() {
        ProductFeatureIntentParser modelLikeParser = ignored -> new ProductFeatureIntent(
                Set.of("portability"), new BigDecimal("1250"), true, 1, 1,
                null, false, 0, 0, List.of(), null, false, false, false);
        var resolver = new ProductFeatureRequestResolver(modelLikeParser,
                new ProductFeatureIntentValidator(schema));

        assertThat(resolver.resolve("任意自然语言").constraints().maxWeightGrams())
                .isEqualByComparingTo("1250");
    }

    @Test
    void invalidUnitsBoundsAndConflictingValuesNeverBecomeHardFilters() {
        assertThat(ProductFeatureRequest.parse("重量不超过2斤").clarification()).contains("单位");
        assertThat(ProductFeatureRequest.parse("重量不超过0kg").clarification()).contains("大于0");
        assertThat(ProductFeatureRequest.parse("重量不超过2000kg").clarification()).contains("合理范围");
        assertThat(ProductFeatureRequest.parse("续航至少10小时，续航至少12小时，看视频").clarification())
                .contains("唯一的续航下限");
        assertThat(ProductFeatureRequest.parse("便携商务笔记本").constraints().active()).isFalse();
    }
}
