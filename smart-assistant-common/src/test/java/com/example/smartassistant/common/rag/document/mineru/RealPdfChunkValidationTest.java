/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file for the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.chunking.RecursiveChunkStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次性验证驱动：用真实 MinerU CLI（pipeline 后端）产出的 content_list.json，
 * 适配成项目 {@link MinerUParseResponse}，再走真实 {@link MinerUDocumentParser}
 * （caption 优先级 / contentType 映射）+ 真实 {@link DocumentChunker} 分块。
 *
 * 目的：在不改动业务源码的前提下，用真实「模型接口及使用说明.pdf」验证切分链路，
 * 并把分块统计写入 target/chunk-validation-report.txt。
 *
 * 复用说明：MinerU 解析由真实 CLI 已完成（C:/mineru/parse_out/...），本测试仅把其
 * content_list.json 适配为 sidecar 协议格式，分块逻辑 100% 走生产代码。
 */
class RealPdfChunkValidationTest {

    private static final String CLI_JSON = System.getProperty("user.pdf.cli.json",
            "C:/mineru/parse_out/模型接口及使用说明/auto/模型接口及使用说明_content_list.json");
    private static final String SOURCE_PDF = "D:/Desktop/模型接口及使用说明.pdf";
    private static final Path REPORT = Paths.get("target/chunk-validation-report.txt");
    private static final Path DETAIL = Paths.get("target/chunk-all-detail.txt");

    @Test
    void validateChunkOnRealPdf() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Paths.get(CLI_JSON).toFile());

        // ---- 1. content_list.json -> MinerUParseResponse（按页分组）----
        Map<Integer, List<MinerUBlock>> byPage = new LinkedHashMap<>();
        int rawItemCount = 0;
        int mappedCount = 0;
        Map<String, Integer> rawCat = new TreeMap<>();

        for (JsonNode it : root) {
            if (!it.isObject()) continue;
            String type = it.path("type").asText(null);
            if (type == null) continue;
            rawItemCount++;
            rawCat.merge(type, 1, Integer::sum);

            MinerUBlock b = switch (type) {
                case "text", "equation" -> {
                    MinerUBlock x = new MinerUBlock();
                    x.setType("text");
                    x.setText(it.path("text").asText(""));
                    yield x;
                }
                case "code" -> {
                    MinerUBlock x = new MinerUBlock();
                    x.setType("text");
                    StringBuilder sb = new StringBuilder();
                    String cap = it.path("code_caption").asText("");
                    if (!cap.isBlank()) sb.append(cap).append("\n");
                    sb.append(it.path("code_body").asText(""));
                    x.setText(sb.toString());
                    yield x;
                }
                case "table" -> {
                    MinerUBlock x = new MinerUBlock();
                    x.setType("table");
                    x.setText(it.path("table_body").asText(""));
                    String tc = it.path("table_caption").asText("");
                    if (!tc.isBlank()) x.setTableCaption(tc);
                    yield x;
                }
                case "image" -> {
                    MinerUBlock x = new MinerUBlock();
                    x.setType("image");
                    String ip = it.path("img_path").asText("");
                    if (!ip.isBlank()) x.setImagePath(ip);
                    String ic = it.path("image_caption").asText("");
                    if (!ic.isBlank()) x.setImageCaption(ic);
                    yield x;
                }
                default -> null; // page_number 等跳过
            };
            if (b != null) {
                int pageIdx = it.path("page_idx").asInt(0);
                byPage.computeIfAbsent(pageIdx + 1, k -> new ArrayList<>()).add(b);
                mappedCount++;
            }
        }

        List<MinerUPage> pages = new ArrayList<>();
        for (Map.Entry<Integer, List<MinerUBlock>> e : byPage.entrySet()) {
            pages.add(new MinerUPage(e.getKey(), e.getValue()));
        }
        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setRequestId("validate");
        resp.setPages(pages);

        // ---- 2. 真实解析映射 + 真实分块 ----
        MinerUClient client = req -> {
            resp.setRequestId(req.getRequestId());
            return resp;
        };
        MinerUDocumentParser parser = new MinerUDocumentParser(client);
        List<ParsedDocument> parsed = parser.parse(SOURCE_PDF);

        DocumentChunker chunker = new DocumentChunker();
        List<KnowledgeDocument> chunks = chunker.chunk(parsed);

        // ---- 3. 统计 ----
        Map<String, Long> parsedByType = new LinkedHashMap<>();
        Map<String, Long> parsedCharsByType = new LinkedHashMap<>();
        long parsedTotalChars = 0;
        for (ParsedDocument d : parsed) {
            String ct = d.getContentType();
            String content = d.getContent() == null ? "" : d.getContent();
            parsedByType.merge(ct, 1L, Long::sum);
            parsedCharsByType.merge(ct, (long) content.length(), Long::sum);
            parsedTotalChars += content.length();
        }

        List<Integer> charSizes = new ArrayList<>();
        List<Integer> tokenSizes = new ArrayList<>();
        Map<String, Long> chunkBySource = new LinkedHashMap<>();
        for (KnowledgeDocument c : chunks) {
            int chars = c.getContent() == null ? 0 : c.getContent().length();
            int tokens = RecursiveChunkStrategy.estimateTokens(c.getContent());
            charSizes.add(chars);
            tokenSizes.add(tokens);
            chunkBySource.merge(c.getSourceType(), 1L, Long::sum);
        }
        charSizes.sort(Integer::compareTo);
        tokenSizes.sort(Integer::compareTo);

        int chunkCount = chunks.size();
        int maxTokens = ChunkStrategyDefaultMaxTokens();
        int chunksOverMax = (int) tokenSizes.stream().filter(t -> t > 1024).count();
        int chunksOver2048 = (int) tokenSizes.stream().filter(t -> t > 2048).count();

        Files.createDirectories(REPORT.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(REPORT, StandardCharsets.UTF_8));
             PrintWriter detail = new PrintWriter(Files.newBufferedWriter(DETAIL, StandardCharsets.UTF_8))) {
            banner(out, "REAL PDF CHUNK VALIDATION REPORT");
            out.println("sourcePdf=" + SOURCE_PDF);
            out.println("mineruCliJson=" + CLI_JSON);
            out.println("strategy=SemanticChunkStrategy (fallback=RecursiveChunkStrategy)");
            out.println("configured maxTokens=1024, overlap=128");
            out.println();

            banner(out, "1) MinerU CLI 解析 (content_list.json)");
            out.println("rawItems=" + rawItemCount + "  mappedBlocks=" + mappedCount + "  pages=" + pages.size());
            out.println("rawCategoryDistribution=" + rawCat);

            banner(out, "2) ParsedDocument (经 MinerUDocumentParser 映射)");
            out.println("parsedElementCount=" + parsed.size());
            out.println("parsedByContentType=" + parsedByType);
            out.println("parsedCharsByContentType=" + parsedCharsByType);
            out.println("parsedTotalChars=" + parsedTotalChars);

            banner(out, "3) Chunk 结果 (经 DocumentChunker)");
            out.println("chunkCount=" + chunkCount);
            out.println("chunkBySourceType=" + chunkBySource);
            out.println("charLen min/median/mean/max="
                    + pct(charSizes, 0) + "/" + pct(charSizes, 50) + "/" + mean(charSizes) + "/" + pct(charSizes, 100));
            out.println("charLen p90/p95=" + pct(charSizes, 90) + "/" + pct(charSizes, 95));
            out.println("tokenCount min/median/mean/max="
                    + pct(tokenSizes, 0) + "/" + pct(tokenSizes, 50) + "/" + mean(tokenSizes) + "/" + pct(tokenSizes, 100));
            out.println("tokenCount p90/p95=" + pct(tokenSizes, 90) + "/" + pct(tokenSizes, 95));
            out.println("chunks>1024tokens=" + chunksOverMax + "  chunks>2048tokens=" + chunksOver2048);

            banner(out, "4) 样本块 (前2块 + 最大块 + 首个表格块)");
            dumpSample(out, chunks, findIndices(chunks, 2, true, "pdf-table"));

            banner(out, "END");

            // ---- 5. 全量分块明细（不截断，逐块完整内容）----
            banner(detail, "ALL CHUNKS DETAIL (chunkCount=" + chunkCount + ")");
            detail.println("sourcePdf=" + SOURCE_PDF);
            detail.println("tokenEstimator=RecursiveChunkStrategy.estimateTokens");
            detail.println();
            for (int i = 0; i < chunks.size(); i++) {
                KnowledgeDocument c = chunks.get(i);
                String content = c.getContent() == null ? "" : c.getContent();
                detail.println("################ CHUNK #" + i
                        + " (sourceType=" + c.getSourceType()
                        + ", chars=" + content.length()
                        + ", tokens=" + RecursiveChunkStrategy.estimateTokens(content) + ") ################");
                detail.println("[TITLE] " + (c.getTitle() == null ? "" : c.getTitle()));
                detail.println("[CONTENT]");
                detail.println(content);
                detail.println();
            }
            banner(detail, "END OF ALL CHUNKS");
        }
        System.out.println("[report] written to " + REPORT.toAbsolutePath());
        System.out.printf("[summary] parsed=%d chunks=%d charMedian=%d tokenMedian=%d%n",
                parsed.size(), chunkCount, pct(charSizes, 50), pct(tokenSizes, 50));
        org.junit.jupiter.api.Assertions.assertTrue(chunkCount > 0, "应至少产生 1 个 chunk");
    }

    private static int ChunkStrategyDefaultMaxTokens() {
        return 1024;
    }

    private static List<Integer> findIndices(List<KnowledgeDocument> chunks, int head,
                                             boolean includeLargest, String tableTypeHint) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < Math.min(head, chunks.size()); i++) idx.add(i);
        if (includeLargest && !chunks.isEmpty()) {
            int max = 0;
            for (int i = 1; i < chunks.size(); i++) {
                if (len(chunks.get(i)) > len(chunks.get(max))) max = i;
            }
            if (!idx.contains(max)) idx.add(max);
        }
        // 首个表格块
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).getTitle() != null && chunks.get(i).getTitle().startsWith("表格")) {
                if (!idx.contains(i)) idx.add(i);
                break;
            }
        }
        idx.sort(Comparator.naturalOrder());
        return idx;
    }

    private static int len(KnowledgeDocument c) {
        return c.getContent() == null ? 0 : c.getContent().length();
    }

    private static void dumpSample(PrintWriter out, List<KnowledgeDocument> chunks, List<Integer> idx) {
        for (int i : idx) {
            if (i < 0 || i >= chunks.size()) continue;
            KnowledgeDocument c = chunks.get(i);
            out.println("---- CHUNK #" + i + " (sourceType=" + c.getSourceType()
                    + ", chars=" + len(c) + ", tokens=" + RecursiveChunkStrategy.estimateTokens(c.getContent()) + ") ----");
            out.println("title=" + c.getTitle());
            String content = c.getContent() == null ? "" : c.getContent();
            out.println(content.length() > 400 ? content.substring(0, 400) + " …(截断)" : content);
        }
    }

    private static int pct(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        if (p <= 0) return sorted.get(0);
        if (p >= 100) return sorted.get(sorted.size() - 1);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    private static int mean(List<Integer> list) {
        if (list.isEmpty()) return 0;
        long s = 0;
        for (int v : list) s += v;
        return (int) (s / list.size());
    }

    private static void banner(PrintWriter out, String title) {
        out.println();
        out.println("==================== " + title + " ====================");
    }
}
