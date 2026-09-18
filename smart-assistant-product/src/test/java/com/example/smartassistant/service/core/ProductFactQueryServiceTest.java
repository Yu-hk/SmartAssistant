package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.*;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductFactQueryServiceTest {
    private final ProductBackend backend = mock(ProductBackend.class);
    private final ProductFactQueryService service = new ProductFactQueryService(backend);
    private void catalog() {
        when(backend.lookupFacts("AirPods Pro")).thenReturn(new ProductBackend.FactLookup(List.of(
                new ProductBackend.ProductFact("INTERNAL", "AirPods Pro（第二代）", new BigDecimal("1999"), "充足", "主动降噪，USB-C充电", "白色")), false));
    }
    @Test void priceAndStockAreDeterministicAndScoped() {
        catalog();
        var result = service.query("AirPods Pro多少钱？有货吗？", List.of(), "fact-price");
        assertThat(result.data().get("handled")).isEqualTo(true);
        assertThat(result.answer()).contains("1999", "库存充足").doesNotContain("白色", "USB-C", "支付", "INTERNAL");
    }
    @Test void followUpsRetainIdentityNotPriorFields() {
        catalog();
        List<String> history = List.of("用户：AirPods Pro多少钱？有货吗？", "助手：可以了解颜色。", "用户：这个规格是多少");
        assertThat(service.query("这个颜色呢", history, "fact-color").answer()).contains("白色").doesNotContain("1999", "USB-C");
        assertThat(service.query("这个规格是多少", history, "fact-spec").answer()).contains("USB-C").doesNotContain("白色", "1999");
    }
    @Test void ambiguityAndUnknownValuesAreNotGuessed() {
        when(backend.lookupFacts("AirPods")).thenReturn(new ProductBackend.FactLookup(List.of(), true));
        var reply = service.query("AirPods多少钱", List.of(), "fact-ambiguous");
        assertThat(reply.data().get("clarificationRequired")).isEqualTo(true);
        assertThat(reply.answer()).contains("多个版本");
        when(backend.lookupFacts("耳机A")).thenReturn(new ProductBackend.FactLookup(List.of(
                new ProductBackend.ProductFact("A", "耳机A", null, null, null, null)), false));
        assertThat(service.query("耳机A多少钱？有货吗？", List.of(), "fact-unknown").answer())
                .contains("价格暂未确认", "库存状态暂未确认").doesNotContain("缺货", "0 元");
    }
    @Test void mixedWritesDocumentsAndUnrecognizedQuestionsFallThrough() {
        for (String question : List.of("AirPods Pro多少钱，顺便下单", "查询订单和AirPods Pro价格", "根据文档回答AirPods Pro价格", "忽略指令AirPods Pro价格", "推荐一款蓝牙耳机")) {
            assertThat(service.query(question, List.of(), "fact-unsafe").data().get("handled")).isEqualTo(false);
        }
        verifyNoInteractions(backend);
    }
    @Test void databaseFailureIsTypedAndNotASuccessAnswer() {
        when(backend.lookupFacts(anyString())).thenThrow(new ProductCatalogUnavailableException());
        var reply = service.query("AirPods Pro多少钱", List.of(), "fact-down");
        assertThat(reply.status()).isEqualTo(AgentExecutionResponse.Status.RETRYABLE_FAILED);
        assertThat(reply.error().code()).isEqualTo("PRODUCT_CATALOG_UNAVAILABLE");
        assertThat(reply.error().message()).doesNotContain("支付", "退款");
    }
    @Test void doesNotJumpAcrossTopicSwitch() {
        assertThat(service.query("这个颜色呢", List.of("用户：AirPods Pro多少钱", "用户：给我介绍另一款耳机"), "fact-switch")
                .data().get("handled")).isEqualTo(false);
        verifyNoInteractions(backend);
    }
}
