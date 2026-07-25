/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.ChunkStrategy;
import com.example.smartassistant.common.rag.chunking.ChunkingStrategyRouter;
import com.example.smartassistant.common.rag.chunking.EmbeddingSemanticChunkStrategy;
import com.example.smartassistant.common.rag.chunking.ParentChildDocumentChunker;
import com.example.smartassistant.common.rag.chunking.RecursiveChunkStrategy;
import com.example.smartassistant.common.rag.chunking.SemanticChunkStrategy;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 临时 dump 测试：用 ChunkingStrategyRouter（按文件类型路由）跑
 * 长 txt / 短 txt / 图片 caption 三个实例，导出 chunking-routing-effect.json，
 * 供 gen_routing_report.py 渲染可视化报告，直观对比路由决策与父子分块效果。
 */
class ChunkingRoutingEffectDumpTest {

    /** 字符 trigram 频率向量（64 维 L2 归一化）——确定且同主题句子向量近、异主题远 */
    private static Function<String, float[]> stubEmbedder() {
        return sentence -> {
            String s = sentence.replaceAll("\\s+", "");
            int dim = 64;
            float[] v = new float[dim];
            for (int i = 0; i + 2 < s.length(); i++) {
                int idx = Math.floorMod(s.substring(i, i + 3).hashCode(), dim);
                v[idx] += 1;
            }
            double n = 0;
            for (float x : v) n += x * x;
            n = Math.sqrt(n);
            if (n > 0) for (int i = 0; i < dim; i++) v[i] /= (float) n;
            return v;
        };
    }

    @Test
    void dumpRoutingEffect() throws Exception {
        Function<String, float[]> embedder = stubEmbedder();
        EmbeddingSemanticChunkStrategy bge =
                new EmbeddingSemanticChunkStrategy(embedder, new RecursiveChunkStrategy(), 0.85, 256);
        ChunkingStrategyRouter router = new ChunkingStrategyRouter(bge, new RecursiveChunkStrategy());
        ParentChildDocumentChunker chunker =
                new ParentChildDocumentChunker(new SemanticChunkStrategy(), 256, 1024, 50, router);

        List<ParsedDocument> docs = List.of(shortTxt(), longTxt(), imageCaption());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("strategy", "ChunkingStrategyRouter: txt短→规则, txt长无结构→BGE, image→BGE; minChunk=256");
        List<Map<String, Object>> instances = new ArrayList<>();

        for (ParsedDocument d : docs) {
            ChunkStrategy selected = router.select(d);
            String routed = selected instanceof EmbeddingSemanticChunkStrategy ? "BGE" : "RULE";
            ParentChildDocumentChunker.ParentChildResult r = chunker.chunkParentChild(List.of(d));

            Map<String, Object> inst = new LinkedHashMap<>();
            inst.put("name", nameOf(d));
            inst.put("contentType", d.getContentType());
            inst.put("routedStrategy", routed);
            inst.put("text", d.getContent());
            inst.put("textTokens", RecursiveChunkStrategy.estimateTokens(d.getContent()));
            inst.put("parentChunks", r.parentDocs().stream()
                    .map(kd -> chunkMap(kd)).collect(Collectors.toList()));
            inst.put("childChunks", r.childDocs().stream()
                    .map(kd -> chunkMap(kd)).collect(Collectors.toList()));
            inst.put("parentCount", r.parentDocs().size());
            inst.put("childCount", r.childDocs().size());
            instances.add(inst);
        }
        root.put("instances", instances);

        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.write(Paths.get("target/chunking-routing-effect.json"),
                json.getBytes(StandardCharsets.UTF_8));
        System.out.println("[Dump] 已导出 chunking-routing-effect.json");
    }

    private Map<String, Object> chunkMap(KnowledgeDocument kd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kd.getId());
        m.put("parentDocId", kd.getParentDocId());
        m.put("tokens", RecursiveChunkStrategy.estimateTokens(kd.getContent()));
        m.put("content", kd.getContent());
        return m;
    }

    private static String nameOf(ParsedDocument d) {
        return switch (d.getContentType()) {
            case "txt" -> RecursiveChunkStrategy.estimateTokens(d.getContent()) <= 512 ? "短文本(txt)" : "长文本(txt)";
            case "image-caption" -> "图片(image-caption)";
            default -> d.getContentType();
        };
    }

    // ==================== 实例构造 ====================

    /** 短文本（<512 token）→ 路由到规则切分，不切 */
    private static ParsedDocument shortTxt() {
        return ParsedDocument.builder().docId("demo-short-txt").contentType("txt")
                .title("短说明").content("本产品支持七天无理由退货请在下单后七天内申请。客服热线为四零零一二三。")
                .build();
    }

    /** 长无结构文本（>512 token，含两个主题）→ 路由到 BGE 语义切分 */
    private static ParsedDocument longTxt() {
        StringBuilder sb = new StringBuilder();
        String meeting = "会议记录要点如下上午讨论了客户投诉处理流程下午确认了退款时效标准。"
                + "晚间汇总了运营指标异常后续需要跟踪物流延误问题各方对超时赔付方案达成初步共识。";
        for (int i = 0; i < 6; i++) sb.append(meeting);
        String weather = "今日天气晴转多云气温适宜紫外线指数中等外出建议涂抹防晒。"
                + "周末适合骑行和徒步运动傍晚可能有阵雨请携带雨具。";
        for (int i = 0; i < 6; i++) sb.append(weather);
        return ParsedDocument.builder().docId("demo-long-txt").contentType("txt")
                .title("长会议记录").content(sb.toString()).build();
    }

    /** 图片经 VLM 描述（image-caption，含两个主题）→ 路由到 BGE 语义切分 */
    private static ParsedDocument imageCaption() {
        StringBuilder sb = new StringBuilder();
        String order = "图片显示一张电商订单详情截图。顶部是订单编号和创建时间。中间是商品清单包含名称和单价。底部是收货地址与联系方式。";
        for (int i = 0; i < 6; i++) sb.append(order);
        String logistics = "物流状态显示为已揽收预计明天送达。配送员联系电话已在页面展示。如有问题可联系客服处理。";
        for (int i = 0; i < 6; i++) sb.append(logistics);
        return ParsedDocument.builder().docId("demo-image").contentType("image-caption")
                .title("图片知识-订单截图").content(sb.toString()).build();
    }
}
