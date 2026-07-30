package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.pipeline.RagSearchPipeline;
import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductRagServiceTest {

    @Test
    void exactBusinessDataBypassesSlowGenericPipeline() {
        RagSearchPipeline pipeline = mock(RagSearchPipeline.class);
        ProductBackend backend = mock(ProductBackend.class);
        String question =
                "Aurora 无线降噪耳机 E2E-PROD-0001 现在多少钱，杭州仓还有多少可售库存？";
        when(backend.queryProductInfo(question)).thenReturn(
                "商品编码：E2E-PROD-0001\n"
                        + "商品名称：Aurora 无线降噪耳机\n"
                        + "价格：¥1299.00\n"
                        + "仓库库存：\n- 杭州仓：可售 86");
        ProductRagService service = new ProductRagService(pipeline, backend);

        RetrievalQualityResult result = service.retrieveWithQualityResult(question);

        assertThat(result.isHighQuality()).isTrue();
        assertThat(result.isRejected()).isFalse();
        assertThat(result.getNormalizedScore()).isEqualTo(1.0);
        assertThat(result.getContent())
                .contains("E2E-PROD-0001")
                .contains("¥1299.00")
                .contains("可售 86");
        verify(pipeline, never()).execute(org.mockito.ArgumentMatchers.any());
    }
}
