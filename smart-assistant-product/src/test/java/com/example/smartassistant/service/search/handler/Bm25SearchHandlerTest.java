package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static com.example.smartassistant.service.search.handler.JevProductFieldAdvisor.ProductField.*;

class Bm25SearchHandlerTest {

    @Test
    void indexesTypedCatalogAndLooksUpCurrentFactsByCodeWithoutBlankSearch() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductSearchDocuments()).thenReturn(List.of(
                new ProductBackend.ProductSearchDocument("AIRPODS-PRO", "AirPods Pro", "降噪 耳机"),
                new ProductBackend.ProductSearchDocument("IPAD-PRO", "iPad Pro", "平板"),
                new ProductBackend.ProductSearchDocument("MACBOOK-AIR", "MacBook Air", "笔记本")));
        when(backend.queryProductInfo("AIRPODS-PRO")).thenReturn("AirPods Pro\n库存：充足");
        ChineseTokenizer tokenizer = mock(ChineseTokenizer.class);
        when(tokenizer.tokenize(anyString())).thenAnswer(invocation ->
                Arrays.stream(invocation.getArgument(0, String.class).split("\\s+"))
                        .filter(value -> !value.isBlank()).collect(Collectors.toSet()));

        Bm25SearchHandler handler = new Bm25SearchHandler(backend, tokenizer);
        RagSearchContext context = new RagSearchContext("降噪");
        context.addQueryVariant("降噪 耳机");
        handler.handle(context);

        assertThat(context.getPathResults().get("BM25").getItems())
                .containsExactly("AirPods Pro\n库存：充足");
        verify(backend).listProductSearchDocuments();
        verify(backend, never()).searchProduct(anyString());
        verify(backend, atLeastOnce()).queryProductInfo("AIRPODS-PRO");
    }

    @Test
    void refreshesCatalogSnapshotWhenItsIntervalExpires() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductSearchDocuments()).thenReturn(List.of(), List.of(
                new ProductBackend.ProductSearchDocument("AIRPODS-PRO", "AirPods Pro", "降噪"),
                new ProductBackend.ProductSearchDocument("IPAD-PRO", "iPad Pro", "平板"),
                new ProductBackend.ProductSearchDocument("MACBOOK-AIR", "MacBook Air", "笔记本")));
        ChineseTokenizer tokenizer = mock(ChineseTokenizer.class);
        when(tokenizer.tokenize(anyString())).thenAnswer(invocation ->
                Arrays.stream(invocation.getArgument(0, String.class).split("\\s+"))
                        .filter(value -> !value.isBlank()).collect(Collectors.toSet()));
        when(backend.queryProductInfo("AIRPODS-PRO")).thenReturn("AirPods Pro\n库存：充足");
        Bm25SearchHandler handler = new Bm25SearchHandler(backend, tokenizer);

        RagSearchContext before = new RagSearchContext("降噪");
        handler.handle(before);
        ReflectionTestUtils.setField(handler, "nextRefreshNanos", 0L);
        RagSearchContext after = new RagSearchContext("降噪");
        handler.handle(after);

        assertThat(before.getPathResults().get("BM25").getItems()).isEmpty();
        assertThat(after.getPathResults().get("BM25").getItems())
                .containsExactly("AirPods Pro\n库存：充足");
        verify(backend, times(2)).listProductSearchDocuments();
    }

    @Test
    void explicitNameUsesIdentityFieldWithoutCallingJev() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductSearchDocuments()).thenReturn(List.of(
                new ProductBackend.ProductSearchDocument("QA-A", "AirPods Pro", "耳机", "苹果降噪耳机", "通勤", "主动降噪"),
                new ProductBackend.ProductSearchDocument("QA-B", "iPad Pro", "平板", "", "学习", ""),
                new ProductBackend.ProductSearchDocument("QA-C", "MacBook Air", "电脑", "", "办公", "")));
        when(backend.queryProductInfo("QA-A")).thenReturn("AirPods Pro live facts");
        JevProductFieldAdvisor advisor = mock(JevProductFieldAdvisor.class);
        Bm25SearchHandler handler = new Bm25SearchHandler(backend, tokenizer(), advisor);

        RagSearchContext context = new RagSearchContext("AirPods Pro");
        handler.handle(context);

        assertThat(context.getPathResults().get("BM25").getItems()).contains("AirPods Pro live facts");
        verifyNoInteractions(advisor);
    }

    @Test
    void nameWithoutParenthesizedGenerationStillSelectsIdentity() {
        Bm25SearchHandler handler = new Bm25SearchHandler(mock(ProductBackend.class), tokenizer());
        EnumSet<JevProductFieldAdvisor.ProductField> fields = ReflectionTestUtils.invokeMethod(
                handler, "selectFields", "AirPods Pro的重量是多少？", List.of(
                        new ProductBackend.ProductSearchDocument(
                                "AIRPODS-PRO", "AirPods Pro（第二代）", "耳机")));
        assertThat(fields).containsExactlyInAnyOrder(IDENTITY, FEATURE);
    }

    @Test
    void namedFeatureQuestionExcludesOtherCatalogProducts() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductSearchDocuments()).thenReturn(List.of(
                new ProductBackend.ProductSearchDocument("AIRPODS-PRO", "AirPods Pro（第二代）", "降噪耳机"),
                new ProductBackend.ProductSearchDocument("AIRPODS-MAX", "AirPods Pro Max", "耳机"),
                new ProductBackend.ProductSearchDocument("SONY-XM6", "Sony XM6", "耳机")));
        when(backend.queryProductInfo("AIRPODS-PRO")).thenReturn("AirPods Pro facts");
        when(backend.queryProductInfo("AIRPODS-MAX")).thenReturn("AirPods Pro Max facts");
        when(backend.queryProductInfo("SONY-XM6")).thenReturn("Sony facts");
        Bm25SearchHandler handler = new Bm25SearchHandler(backend, tokenizer());

        RagSearchContext context = new RagSearchContext("AirPods Pro的重量是多少？");
        handler.handle(context);

        assertThat(context.getPathResults().get("BM25").getItems()).containsExactly("AirPods Pro facts");
        verify(backend, never()).queryProductInfo("AIRPODS-MAX");
        verify(backend, never()).queryProductInfo("SONY-XM6");
    }

    @Test
    void uncertainQueryUsesJevMultiLabelHintAndPreservesBaselineOnFailure() {
        ProductBackend backend = mock(ProductBackend.class);
        when(backend.listProductSearchDocuments()).thenReturn(List.of(
                new ProductBackend.ProductSearchDocument("QA-A", "甲商品", "", "", "游学设备", "游学设备"),
                new ProductBackend.ProductSearchDocument("QA-B", "乙商品", "游学设备", "", "", ""),
                new ProductBackend.ProductSearchDocument("QA-C", "丙商品", "其他", "", "", "")));
        when(backend.queryProductInfo("QA-A")).thenReturn("A");
        when(backend.queryProductInfo("QA-B")).thenReturn("B");
        JevProductFieldAdvisor advisor = mock(JevProductFieldAdvisor.class);
        when(advisor.suggest("游学设备")).thenReturn(EnumSet.of(PURPOSE, FEATURE), EnumSet.noneOf(
                JevProductFieldAdvisor.ProductField.class));
        Bm25SearchHandler handler = new Bm25SearchHandler(backend, tokenizer(), advisor);

        RagSearchContext selected = new RagSearchContext("游学设备");
        handler.handle(selected);
        RagSearchContext fallback = new RagSearchContext("游学设备");
        handler.handle(fallback);

        assertThat(selected.getPathResults().get("BM25").getItems()).contains("A", "B");
        assertThat(selected.getPathResults().get("BM25").getItems().getFirst()).isEqualTo("A");
        assertThat(fallback.getPathResults().get("BM25").getItems()).contains("A", "B");
        verify(advisor, times(2)).suggest("游学设备");
    }

    private static ChineseTokenizer tokenizer() {
        ChineseTokenizer tokenizer = mock(ChineseTokenizer.class);
        when(tokenizer.tokenize(anyString())).thenAnswer(invocation ->
                Arrays.stream(invocation.getArgument(0, String.class).split("\\s+"))
                        .filter(value -> !value.isBlank()).collect(Collectors.toSet()));
        return tokenizer;
    }
}
