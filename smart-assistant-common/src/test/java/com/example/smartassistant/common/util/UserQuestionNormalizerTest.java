package com.example.smartassistant.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserQuestionNormalizerTest {

    @Test
    void decodesTransportWhitespaceWithoutDecodingMarkup() {
        assertThat(UserQuestionNormalizer.normalize(
                "  我想买一部平板电脑，帮我推荐一款热门的&#x20; \u00a0"))
                .isEqualTo("我想买一部平板电脑，帮我推荐一款热门的");
        assertThat(UserQuestionNormalizer.normalize("查询&lt;script&gt;商品"))
                .isEqualTo("查询&lt;script&gt;商品");
    }
}
