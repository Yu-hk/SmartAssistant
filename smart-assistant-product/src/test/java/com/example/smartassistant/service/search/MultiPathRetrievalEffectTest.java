/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file for the full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.Bm25Scorer;
import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import com.example.smartassistant.common.rag.Reranker;
import com.example.smartassistant.common.rag.eval.RetrievalMetrics;
import com.example.smartassistant.common.rag.pipeline.AdaptiveRerankTopK;
import com.example.smartassistant.common.rag.pipeline.DedupHandler;
import com.example.smartassistant.common.rag.pipeline.EmbeddingScorer;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.common.rag.pipeline.RagSearchPipeline;
import com.example.smartassistant.common.rag.pipeline.RerankHandler;
import com.example.smartassistant.service.search.handler.RrfFusionHandler;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.example.smartassistant.service.search.handler.Bm25SearchHandler;
import com.example.smartassistant.service.search.handler.ExactMatchHandler;
import com.example.smartassistant.service.search.handler.KnowledgeSearchHandler;
import com.example.smartassistant.service.search.handler.KeywordSearchHandler;
import com.example.smartassistant.spi.InMemoryProductBackend;
import com.example.smartassistant.spi.ProductBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多路召回（Multi-Path Retrieval）效果测试。
 *
 * <p>目标：验证「精确匹配 / 关键词 / BM25 / 知识库 」多路召回 + RRF 融合 + 去重 + 重排
 * 的真实管线行为，并用 {@link RetrievalMetrics} 量化「单路语义」 vs 「多路 RRF 融合」的
 * 召回率提升（Recall@K / MRR / nDCG@K）。全部使用内存组件，无需真实 BGE 模型 / 向量库 / PG。</p>
 */
class MultiPathRetrievalEffectTest {

    /** 不依赖真 BGE 的确定性 stub：相同文本→相同向量，不同文本→近似正交。 */
    static final class StubBgeEmbeddingModel extends BgeEmbeddingModel {
        private static final int DIM = 1024;

        StubBgeEmbeddingModel() {
            super("__multipath_test_stub__");
        }

        @Override
        public int dimensions() {
            return DIM;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public float[] embedding(String text) {
            if (text == null) text = "";
            float[] v = new float[DIM];
            for (int p = 0; p < text.length(); p++) {
                char c = text.charAt(p);
                long seed = ((long) c) * 1000003L + p;
                Random rnd = new Random(seed);
                for (int i = 0; i < DIM; i++) {
                    v[i] += (rnd.nextFloat() * 2.0f - 1.0f);
                }
            }
            double norm = 0.0;
            for (float x : v) norm += (double) x * x;
            norm = Math.sqrt(norm);
            if (norm > 1e-12) {
                for (int i = 0; i < DIM; i++) v[i] /= (float) norm;
            }
            return v;
        }
    }

    // ============ 报告收集（静态，@AfterAll 写出 JSON） ============
    static final List<Map<String, Object>> e2eRows = new ArrayList<>();
    static final List<Map<String, Object>> metricRows = new ArrayList<>();
    static final Map<String, Object> metricAvg = new LinkedHashMap<>();
    /** 文档 id → 标题（供报告把召回结果从 id 解析为可读标题）。 */
    static final Map<String, String> docIndex = new LinkedHashMap<>();

    private final BgeEmbeddingModel embed = new StubBgeEmbeddingModel();
    private final ChineseTokenizer tokenizer = new ChineseTokenizer();
    private KnowledgeRetrievalService retrievalService;
    private ProductBackend productBackend;

    @BeforeEach
    void setup() {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase(
                KnowledgeSeedData.PRODUCT_KB, embed, tokenizer, Reranker.identity());
        kb.addDocument(new KnowledgeDocument("kb-iphone", "iPhone 15 Pro",
                "iPhone 15 Pro 苹果手机 售价8999元 钛金属 A17 Pro芯片 4800万像素 视频续航23小时",
                "product", "iPhone,苹果,手机", -1L, -1L));
        kb.addDocument(new KnowledgeDocument("kb-airpods", "AirPods Pro",
                "AirPods Pro 第二代 苹果耳机 售价1999元 主动降噪 自适应音频 USB-C充电",
                "product", "AirPods,苹果,耳机,降噪", -1L, -1L));
        kb.addDocument(new KnowledgeDocument("kb-macbook", "MacBook Air M3",
                "MacBook Air M3 苹果笔记本 13.6英寸 M3芯片 18小时续航 轻薄办公",
                "product", "MacBook,苹果,笔记本", -1L, -1L));
        kb.addDocument(new KnowledgeDocument("kb-faq-battery", "续航说明",
                "iPhone 15 Pro 视频续航23小时，MacBook Air M3 续航18小时，长时间使用建议携带充电器",
                "faq", "续航,电池", -1L, -1L));
        kb.addDocument(new KnowledgeDocument("kb-faq-noise", "降噪说明",
                "AirPods Pro 支持主动降噪与通透模式，适合通勤和安静办公环境",
                "faq", "降噪,耳机", -1L, -1L));
        kb.reindex();
        retrievalService = new KnowledgeRetrievalService().register(kb);

        productBackend = new InMemoryProductBackend();
    }

    @Test
    @DisplayName("真实多路召回管线端到端：各路命中 + RRF 融合 + 去重 + 重排")
    void e2eMultiPathRetrieval() {
        e2eRows.clear();

        ExactMatchHandler exact = new ExactMatchHandler(productBackend);
        KeywordSearchHandler keyword = new KeywordSearchHandler(productBackend);
        Bm25SearchHandler bm25 = new Bm25SearchHandler(productBackend, new Bm25Scorer(tokenizer));
        KnowledgeSearchHandler knowledge = new KnowledgeSearchHandler(retrievalService);
        RrfFusionHandler rrf = new RrfFusionHandler();
        DedupHandler dedup = new DedupHandler(true, DedupHandler.DedupMode.AGGRESSIVE, 0.85);
        EmbeddingScorer scorer = new EmbeddingScorer(embed::embedding);
        RerankHandler rerank = new RerankHandler(scorer, true, 5,
                new AdaptiveRerankTopK(3, 5, 8).asResolver());

        RagSearchPipeline pipeline = new RagSearchPipeline(
                List.of(exact, keyword, bm25, knowledge, rrf, dedup, rerank));

        String[] queries = {
                "iPhone 15 Pro 价格多少",
                "AirPods Pro 降噪怎么样",
                "MacBook Air 续航多久",
                "iPhone 15 Pro 规格参数",
                "AirPods Pro 多少钱"
        };

        for (String q : queries) {
            RagSearchContext ctx = new RagSearchContext(q);
            pipeline.execute(ctx);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query", q);

            Map<String, Object> paths = new LinkedHashMap<>();
            for (var e : ctx.getPathResults().entrySet()) {
                List<String> items = e.getValue().getItems();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("count", items.size());
                List<String> previews = new ArrayList<>();
                for (String it : items) {
                    previews.add(it.length() > 50 ? it.substring(0, 50) + "…" : it);
                }
                p.put("items", previews);
                paths.put(e.getKey(), p);
            }
            row.put("paths", paths);

            List<Map<String, Object>> fused = new ArrayList<>();
            for (var it : ctx.getFusedResults()) {
                String c = it.getContent();
                String preview = c.length() > 70 ? c.substring(0, 70) + "…" : c;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("content", preview);
                f.put("score", Math.round(it.getRrfScore() * 10000.0) / 10000.0);
                fused.add(f);
            }
            row.put("fusedTop", fused);
            row.put("qualityScore", Math.round(ctx.getQualityScore() * 10000.0) / 10000.0);
            row.put("degraded", ctx.isDegraded());
            e2eRows.add(row);

            assertFalse(ctx.getFusedResults().isEmpty(), "融合结果不应为空: " + q);
            // 注意：degraded 是多路召回的设计内容错（某路异常不影响整体），仅记录不阻断
            if (ctx.isDegraded()) {
                System.out.println("[MultiPath] 查询发生降级(某路异常): " + q
                        + " errors=" + ctx.getErrors());
            }
        }
    }

    @Test
    @DisplayName("量化对比：单路语义召回 vs 多路 RRF 融合（Recall@K / MRR / nDCG@K）")
    void metricsSingleVsMulti() {
        metricRows.clear();
        metricAvg.clear();

        List<KnowledgeDocument> docs = List.of(
                new KnowledgeDocument("d-laptop-apple", "MacBook Air M3", "MacBook Air M3 笔记本 苹果 轻薄 续航18小时", "product", "", -1L, -1L),
                new KnowledgeDocument("d-laptop-win", "ThinkPad X1", "ThinkPad X1 笔记本 Windows 商务 续航10小时", "product", "", -1L, -1L),
                new KnowledgeDocument("d-phone-apple", "iPhone 15 Pro", "iPhone 15 Pro 手机 苹果 A17 钛金属 8999", "product", "", -1L, -1L),
                new KnowledgeDocument("d-phone-android", "Galaxy S24", "Samsung Galaxy S24 手机 安卓 拍照", "product", "", -1L, -1L),
                new KnowledgeDocument("d-ear-apple", "AirPods Pro", "AirPods Pro 耳机 苹果 降噪 1999", "product", "", -1L, -1L),
                new KnowledgeDocument("d-ear-sony", "Sony XM5", "Sony WH-1000XM5 耳机 降噪 头戴", "product", "", -1L, -1L),
                new KnowledgeDocument("d-tab-apple", "iPad Pro", "iPad Pro 平板 苹果 M4 绘图", "product", "", -1L, -1L),
                new KnowledgeDocument("d-tab-android", "小米平板", "小米平板 安卓 影音", "product", "", -1L, -1L),
                new KnowledgeDocument("d-watch", "Apple Watch", "Apple Watch 手表 健康 监测", "product", "", -1L, -1L),
                new KnowledgeDocument("d-camera", "Sony A7", "Sony A7 相机 全画幅 摄影", "product", "", -1L, -1L),
                new KnowledgeDocument("d-tv", "LG OLED", "LG OLED 电视 4K 影院", "product", "", -1L, -1L),
                new KnowledgeDocument("d-charger", "充电器", "充电器 快充 Type-C 配件", "product", "", -1L, -1L)
        );

        docIndex.clear();
        for (var d : docs) {
            docIndex.put(d.getId(), d.getTitle());
        }

        Bm25Scorer bm25Scorer = new Bm25Scorer(tokenizer);
        bm25Scorer.initialize(docs);

        Map<String, List<String>> graphRules = new LinkedHashMap<>();
        graphRules.put("苹果", List.of("d-laptop-apple", "d-phone-apple", "d-ear-apple", "d-tab-apple", "d-watch"));
        graphRules.put("降噪", List.of("d-ear-apple", "d-ear-sony"));
        graphRules.put("安卓", List.of("d-phone-android", "d-tab-android"));
        graphRules.put("笔记本", List.of("d-laptop-apple", "d-laptop-win"));
        graphRules.put("手机", List.of("d-phone-apple", "d-phone-android"));
        graphRules.put("耳机", List.of("d-ear-apple", "d-ear-sony"));
        graphRules.put("平板", List.of("d-tab-apple", "d-tab-android"));
        graphRules.put("手表", List.of("d-watch"));
        graphRules.put("相机", List.of("d-camera"));
        graphRules.put("电视", List.of("d-tv"));

        Map<String, Set<String>> golden = new LinkedHashMap<>();
        golden.put("苹果笔记本推荐", Set.of("d-laptop-apple"));
        golden.put("iPhone 价格", Set.of("d-phone-apple"));
        golden.put("降噪耳机", Set.of("d-ear-apple", "d-ear-sony"));
        golden.put("安卓手机", Set.of("d-phone-android"));
        golden.put("苹果耳机降噪", Set.of("d-ear-apple"));
        golden.put("平板绘图", Set.of("d-tab-apple"));
        golden.put("智能手表健康", Set.of("d-watch"));
        golden.put("Windows商务本", Set.of("d-laptop-win"));

        RrfFusionHandler rrf = new RrfFusionHandler();
        DedupHandler dedup = new DedupHandler(true, DedupHandler.DedupMode.AGGRESSIVE, 0.85);

        double[] singleSum = new double[5]; // recall@1,3,5,mrr,ndcg@5
        double[] multiSum = new double[5];
        int n = 0;

        for (var g : golden.entrySet()) {
            String q = g.getKey();
            Set<String> rel = g.getValue();

            List<String> semantic = semanticRecall(q, docs, 5);
            List<String> bm25 = bm25Recall(q, bm25Scorer, docs, 5);
            List<String> keyword = keywordRecall(q, docs, 5);
            List<String> exact = exactRecall(q, docs);
            List<String> graph = graphRecall(q, graphRules, 5);

            double sR1 = RetrievalMetrics.recallAtK(rel, semantic, 1);
            double sR3 = RetrievalMetrics.recallAtK(rel, semantic, 3);
            double sR5 = RetrievalMetrics.recallAtK(rel, semantic, 5);
            double sMrr = RetrievalMetrics.mrr(rel, semantic);
            double sNdcg = RetrievalMetrics.ndcgAtK(rel, semantic, 5);

            RagSearchContext ctx = new RagSearchContext(q);
            ctx.addPathResult("语义", semantic);
            ctx.addPathResult("BM25", bm25);
            ctx.addPathResult("关键词", keyword);
            ctx.addPathResult("精确", exact);
            ctx.addPathResult("图谱", graph);
            rrf.handle(ctx);
            dedup.handle(ctx);
            List<String> multi = ctx.getFusedResults().stream()
                    .map(RagSearchContext.RankedItem::getContent).collect(Collectors.toList());

            double mR1 = RetrievalMetrics.recallAtK(rel, multi, 1);
            double mR3 = RetrievalMetrics.recallAtK(rel, multi, 3);
            double mR5 = RetrievalMetrics.recallAtK(rel, multi, 5);
            double mMrr = RetrievalMetrics.mrr(rel, multi);
            double mNdcg = RetrievalMetrics.ndcgAtK(rel, multi, 5);

            Map<String, Object> single = new LinkedHashMap<>();
            single.put("recall@1", sR1);
            single.put("recall@3", sR3);
            single.put("recall@5", sR5);
            single.put("mrr", sMrr);
            single.put("ndcg@5", sNdcg);

            Map<String, Object> multiM = new LinkedHashMap<>();
            multiM.put("recall@1", mR1);
            multiM.put("recall@3", mR3);
            multiM.put("recall@5", mR5);
            multiM.put("mrr", mMrr);
            multiM.put("ndcg@5", mNdcg);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query", q);
            row.put("relevant", new ArrayList<>(rel));
            Map<String, Object> paths = new LinkedHashMap<>();
            paths.put("语义", semantic);
            paths.put("BM25", bm25);
            paths.put("关键词", keyword);
            paths.put("精确", exact);
            paths.put("图谱", graph);
            row.put("paths", paths);
            row.put("single", single);
            row.put("multi", multiM);
            row.put("multiIds", multi);
            metricRows.add(row);

            singleSum[0] += sR1; singleSum[1] += sR3; singleSum[2] += sR5;
            singleSum[3] += sMrr; singleSum[4] += sNdcg;
            multiSum[0] += mR1; multiSum[1] += mR3; multiSum[2] += mR5;
            multiSum[3] += mMrr; multiSum[4] += mNdcg;
            n++;
        }

        Map<String, Object> singleAvg = new LinkedHashMap<>();
        singleAvg.put("recall@1", singleSum[0] / n);
        singleAvg.put("recall@3", singleSum[1] / n);
        singleAvg.put("recall@5", singleSum[2] / n);
        singleAvg.put("mrr", singleSum[3] / n);
        singleAvg.put("ndcg@5", singleSum[4] / n);
        Map<String, Object> multiAvgM = new LinkedHashMap<>();
        multiAvgM.put("recall@1", multiSum[0] / n);
        multiAvgM.put("recall@3", multiSum[1] / n);
        multiAvgM.put("recall@5", multiSum[2] / n);
        multiAvgM.put("mrr", multiSum[3] / n);
        multiAvgM.put("ndcg@5", multiSum[4] / n);
        metricAvg.put("single", singleAvg);
        metricAvg.put("multi", multiAvgM);

        // 核心结论：多路 RRF 融合的平均 Recall@5 不应低于单路语义召回
        assertTrue(multiSum[2] >= singleSum[2] - 1e-9,
                "多路 RRF 融合平均 Recall@5 应 >= 单路语义召回");
    }

    // ============ 各路召回函数（可控、语义合理） ============

    private List<String> semanticRecall(String q, List<KnowledgeDocument> docs, int topK) {
        List<Map.Entry<KnowledgeDocument, Double>> scored = new ArrayList<>();
        for (var d : docs) {
            scored.add(Map.entry(d, cosine(q, d.toEmbedText())));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return scored.stream().limit(topK).map(e -> e.getKey().getId()).collect(Collectors.toList());
    }

    private List<String> bm25Recall(String q, Bm25Scorer scorer, List<KnowledgeDocument> docs, int topK) {
        return scorer.rerank(docs, q, topK).stream()
                .map(e -> e.getKey().getId()).collect(Collectors.toList());
    }

    private List<String> keywordRecall(String q, List<KnowledgeDocument> docs, int topK) {
        String lower = q.toLowerCase();
        List<Map.Entry<KnowledgeDocument, Integer>> hit = new ArrayList<>();
        for (var d : docs) {
            int cnt = 0;
            String text = (d.getTitle() + " " + d.getContent()).toLowerCase();
            if (text.contains(lower)) cnt += 3;
            for (String tok : text.split("\\s+")) {
                if (!tok.isEmpty() && lower.contains(tok)) cnt++;
            }
            if (cnt > 0) hit.add(Map.entry(d, cnt));
        }
        hit.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return hit.stream().limit(topK).map(e -> e.getKey().getId()).collect(Collectors.toList());
    }

    private List<String> exactRecall(String q, List<KnowledgeDocument> docs) {
        List<String> r = new ArrayList<>();
        for (var d : docs) {
            String key = d.getId().replace("d-", "");
            if (q.toLowerCase().contains(key) || q.toLowerCase().contains(d.getTitle().toLowerCase())) {
                r.add(d.getId());
            }
        }
        return r;
    }

    private List<String> graphRecall(String q, Map<String, List<String>> rules, int topK) {
        Set<String> r = new LinkedHashSet<>();
        for (var e : rules.entrySet()) {
            if (q.contains(e.getKey())) r.addAll(e.getValue());
        }
        return new ArrayList<>(r).stream().limit(topK).collect(Collectors.toList());
    }

    private double cosine(String a, String b) {
        float[] va = embed.embedding(a);
        float[] vb = embed.embedding(b);
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < va.length; i++) {
            dot += va[i] * vb[i];
            nA += va[i] * va[i];
            nB += vb[i] * vb[i];
        }
        double d = Math.sqrt(nA) * Math.sqrt(nB);
        return d == 0 ? 0 : dot / d;
    }

    @AfterAll
    static void writeReport() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rrfK", 60);
        meta.put("candidatePoolK", 20);
        meta.put("qualityThreshold", 0.30);
        meta.put("embedding", "StubBgeEmbeddingModel(1024d,确定性)");
        meta.put("knowledgeBase", "InMemoryKnowledgeBase + InMemoryProductBackend + StubBge");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("e2e", e2eRows);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("perQuery", metricRows);
        metrics.put("average", metricAvg);
        root.put("metrics", metrics);
        root.put("docIndex", docIndex);

        ObjectMapper om = new ObjectMapper();
        File out = new File("target/multipath-effect.json");
        out.getParentFile().mkdirs();
        om.writerWithDefaultPrettyPrinter().writeValue(out, root);
        System.out.println("[MultiPath] 报告已写出: " + out.getAbsolutePath());
    }
}
