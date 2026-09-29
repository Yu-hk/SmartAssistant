package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
}
