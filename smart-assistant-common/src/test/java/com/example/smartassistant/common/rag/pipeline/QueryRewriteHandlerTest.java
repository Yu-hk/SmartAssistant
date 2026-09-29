package com.example.smartassistant.common.rag.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteHandlerTest {

    @Test
    void retainsExactOriginalAlongsideOneRewrittenQuery() {
        RagSearchContext context = new RagSearchContext("AIRPODS-PRO 有货吗");
        QueryRewriteHandler handler = new QueryRewriteHandler(prompt -> "AirPods Pro 第二代 库存");

        handler.handle(context);
        handler.handle(context);

        assertThat(context.getQueryVariants())
                .containsExactly("AIRPODS-PRO 有货吗", "AirPods Pro 第二代 库存");
        assertThat(context.getAttribute("queryRewrite.original")).isEqualTo("AIRPODS-PRO 有货吗");
    }

    @Test
    void unchangedRewriteDoesNotAddDuplicateVariant() {
        RagSearchContext context = new RagSearchContext("MACBOOK-AIR-M3");
        new QueryRewriteHandler(prompt -> "MACBOOK-AIR-M3").handle(context);

        assertThat(context.getQueryVariants()).containsExactly("MACBOOK-AIR-M3");
    }
}
