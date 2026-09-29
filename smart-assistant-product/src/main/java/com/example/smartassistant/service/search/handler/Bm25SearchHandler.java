/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.Bm25Scorer;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.service.core.ProductFeatureSchema;
import com.example.smartassistant.service.search.handler.JevProductFieldAdvisor.ProductField;
import com.example.smartassistant.spi.ProductBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * H03: BM25 语义评分检索 Handler。
 *
 * <p>将所有产品文本构建为 {@link KnowledgeDocument} 集合，
 * 使用 {@link Bm25Scorer#rerank(List, String, int)} 排序，取 Top-K。
 */
@Component
public class Bm25SearchHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(Bm25SearchHandler.class);

    private static final int TOP_K = 5;

    private final ProductBackend productBackend;
    private final ChineseTokenizer tokenizer;
    private final JevProductFieldAdvisor fieldAdvisor;

    /** Immutable scorer/documents pair; refresh atomically when catalog intake changes. */
    private volatile ProductIndex index;
    private volatile long nextRefreshNanos;

    @Value("${product.rag.bm25-refresh-ms:60000}")
    private long refreshMillis = 60000;

    @Autowired
    public Bm25SearchHandler(ProductBackend productBackend, ChineseTokenizer tokenizer,
                             JevProductFieldAdvisor fieldAdvisor) {
        this.productBackend = productBackend;
        this.tokenizer = tokenizer;
        this.fieldAdvisor = fieldAdvisor;
    }

    /** Allows isolated tests to run without an external Jev client. */
    public Bm25SearchHandler(ProductBackend productBackend, ChineseTokenizer tokenizer) {
        this(productBackend, tokenizer, null);
    }

    @Override
    public void handle(RagSearchContext context) {
        ProductIndex current = currentIndex();
        if (current == null || current.products().isEmpty()) {
            context.addPathResult("BM25", List.of());
            return;
        }

        EnumSet<ProductField> selected = selectFields(context.getOriginalQuery(), current.products());
        if (selected.isEmpty() && fieldAdvisor != null) {
            selected = fieldAdvisor.suggest(context.getOriginalQuery());
        }
        Set<String> allResults = new LinkedHashSet<>();

        for (String variant : context.getQueryVariants()) {
            try {
                for (String code : rankCodes(current, variant, selected)) {
                    try {
                        String info = productBackend.queryProductInfo(code);
                        if (info != null && !info.contains("PRODUCT_NOT_FOUND")
                                && !info.contains("TOOL_EXECUTION_ERROR")
                                && !info.contains("TOOL_INVALID_ARGUMENT")) {
                            allResults.add(info);
                        }
                    } catch (Exception e) {
                        log.debug("[RagHandler] BM25 单个查询失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[RagHandler] BM25 失败 (variant={}): {}", variant, e.getMessage());
            }
        }

        context.addPathResult("BM25", List.copyOf(allResults));
        log.info("[RagHandler] BM25: {} results for {} variants, fields={}",
                allResults.size(), context.getQueryVariants().size(), selected);
    }

    private EnumSet<ProductField> selectFields(String query, List<ProductBackend.ProductSearchDocument> products) {
        EnumSet<ProductField> fields = EnumSet.noneOf(ProductField.class);
        if (query == null || query.isBlank()) return fields;
        String normalized = query.toUpperCase(Locale.ROOT);
        for (ProductBackend.ProductSearchDocument product : products) {
            if (containsNamedProduct(normalized, product.code())
                    || containsNamedProduct(normalized, product.name())
                    || Arrays.stream(Objects.toString(product.aliases(), "").split("\u001f"))
                        .anyMatch(alias -> containsNamedProduct(normalized, alias))) {
                fields.add(ProductField.IDENTITY);
                break;
            }
        }
        if (List.of("适合", "用途", "使用场景", "适用人群", "推荐给", "送给", "通勤", "办公", "学习", "旅行")
                .stream().anyMatch(query::contains)) fields.add(ProductField.PURPOSE);
        ProductFeatureSchema schema = ProductFeatureSchema.defaultSchema();
        if (List.of(schema.weight(), schema.battery(), schema.noiseCancelling()).stream()
                .anyMatch(definition -> definition.mentionedIn(query))
                || List.of("规格", "参数", "功能", "性能").stream().anyMatch(query::contains)) {
            fields.add(ProductField.FEATURE);
        }
        return fields;
    }

    private static boolean containsNamedProduct(String query, String name) {
        if (name == null || name.length() < 3) return false;
        String normalized = name.toUpperCase(Locale.ROOT);
        if (query.contains(normalized)) return true;
        // Catalog names often append a generation in parentheses. The maintained
        // name's stable prefix is still an identity match, not a new alias/fact.
        int qualifier = Math.min(
                normalized.indexOf('（') < 0 ? normalized.length() : normalized.indexOf('（'),
                normalized.indexOf('(') < 0 ? normalized.length() : normalized.indexOf('('));
        String baseName = normalized.substring(0, qualifier).trim();
        if (qualifier == normalized.length() || baseName.length() < 3) return false;
        for (int start = query.indexOf(baseName); start >= 0;
                start = query.indexOf(baseName, start + 1)) {
            int end = start + baseName.length();
            if (end == query.length()) return true;
            char next = query.charAt(end);
            if (Character.isLetterOrDigit(next) && next < 128) continue;
            if (next == ' ') {
                int following = end;
                while (following < query.length() && query.charAt(following) == ' ') following++;
                if (following < query.length() && query.charAt(following) < 128
                        && Character.isLetterOrDigit(query.charAt(following))) continue;
            }
            return true;
        }
        return false;
    }

    /** Field matches boost a candidate; the whole-catalog rank remains a soft recall fallback. */
    private List<String> rankCodes(ProductIndex current, String query, EnumSet<ProductField> selected) {
        Map<String, Double> scores = new HashMap<>();
        for (ProductField field : selected) addRanks(scores, current.fields().get(field), query, 3.0);
        addRanks(scores, current.baseline(), query, selected.isEmpty() ? 1.0 : 0.25);
        Set<String> explicitlyNamed = new LinkedHashSet<>();
        if (selected.contains(ProductField.IDENTITY)) {
            String normalized = query.toUpperCase(Locale.ROOT);
            for (var product : current.products()) {
                if (containsNamedProduct(normalized, product.code())
                        || containsNamedProduct(normalized, product.name())
                        || Arrays.stream(Objects.toString(product.aliases(), "").split("\u001f"))
                            .anyMatch(alias -> containsNamedProduct(normalized, alias))) {
                    explicitlyNamed.add(product.code());
                    scores.merge(product.code(), 1.0, Double::sum);
                }
            }
        }
        // A named product is a hard scope, not just a ranking preference. Keep all
        // explicitly named products for comparisons, but do not inject unrelated
        // catalog entries into a single-product fact answer.
        if (!explicitlyNamed.isEmpty()) scores.keySet().retainAll(explicitlyNamed);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_K).map(Map.Entry::getKey).toList();
    }

    private static void addRanks(Map<String, Double> scores, Corpus corpus, String query, double weight) {
        if (corpus == null) return;
        int rank = 0;
        for (var entry : corpus.scorer().rerank(corpus.docs(), query, TOP_K)) {
            scores.merge(entry.getKey().getId(), weight / (60 + ++rank), Double::sum);
        }
        // BM25's classic IDF can be zero/negative in a tiny catalog. A literal phrase
        // present in a maintained field must still be recallable, without inventing facts.
        if (query != null && !query.isBlank()) {
            String needle = query.trim().toUpperCase(Locale.ROOT);
            for (KnowledgeDocument doc : corpus.docs()) {
                if (doc.getContent().toUpperCase(Locale.ROOT).contains(needle)) {
                    scores.merge(doc.getId(), weight / 100.0, Double::sum);
                }
            }
        }
    }

    private synchronized ProductIndex currentIndex() {
        long now = System.nanoTime();
        if (now - nextRefreshNanos < 0) return index;
        // Retry a failed catalog read soon; a successful snapshot refreshes at
        // the configured interval without mutating an in-flight scorer.
        nextRefreshNanos = now + TimeUnit.SECONDS.toNanos(10);
        List<ProductBackend.ProductSearchDocument> products;
        try {
            products = productBackend.listProductSearchDocuments().stream()
                    .filter(product -> product.code() != null && !product.code().isBlank()
                            && product.name() != null && !product.name().isBlank()).toList();
        } catch (Exception e) {
            log.warn("[RagHandler] BM25 商品目录读取失败: {}", e.getMessage());
            return index;
        }

        Corpus baseline = corpus(products, product -> product.code() + " " + product.name() + " "
                + Objects.toString(product.spec(), "") + " " + Objects.toString(product.aliases(), "").replace('\u001f', ' ')
                + " " + Objects.toString(product.purposes(), "") + " " + Objects.toString(product.features(), ""));
        Map<ProductField, Corpus> fields = new EnumMap<>(ProductField.class);
        fields.put(ProductField.IDENTITY, corpus(products, product -> product.code() + " "
                + product.name() + " " + Objects.toString(product.aliases(), "").replace('\u001f', ' ')));
        fields.put(ProductField.PURPOSE, corpus(products, product -> Objects.toString(product.purposes(), "")));
        fields.put(ProductField.FEATURE, corpus(products, product -> Objects.toString(product.features(), "")));
        index = new ProductIndex(List.copyOf(products), baseline, Map.copyOf(fields));
        nextRefreshNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                refreshMillis > 0 ? refreshMillis : 60000);
        log.info("[RagHandler] BM25 分字段索引重建完成: {} 个产品", products.size());
        return index;
    }

    private Corpus corpus(List<ProductBackend.ProductSearchDocument> products,
                          java.util.function.Function<ProductBackend.ProductSearchDocument, String> text) {
        List<KnowledgeDocument> docs = products.stream().map(product ->
                new KnowledgeDocument(product.code(), "", text.apply(product), "product", "", -1L, -1L)).toList();
        Bm25Scorer scorer = new Bm25Scorer(tokenizer);
        scorer.initialize(docs);
        return new Corpus(docs, scorer);
    }

    private record Corpus(List<KnowledgeDocument> docs, Bm25Scorer scorer) { }
    private record ProductIndex(List<ProductBackend.ProductSearchDocument> products,
                                Corpus baseline, Map<ProductField, Corpus> fields) { }

    @Override
    public int getOrder() {
        return 30;
    }
}
