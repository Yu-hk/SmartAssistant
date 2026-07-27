/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.document.DocumentParser;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性验证驱动：用真实业务 PDF（命令行 -Duser.pdf.path 指定，默认 D:/tmp/model-spec.pdf）
 * 走 PdfParserRouter（MinerU 开启 + routeOnImages → MinerU sidecar 路径）解析，
 * 再经 DocumentChunker 分块，把每个 chunk 的元信息与完整正文写入
 * target/user-pdf-mineru-report.txt。用于验证「图片文字/表格」是否经 MinerU 进入索引。
 * 不改变任何业务源码。
 */
class UserPdfChunkMinerUTest {

    private static final String PDF_PATH = System.getProperty("user.pdf.path",
            "D:/tmp/model-spec.pdf");
    private static final Path REPORT = Paths.get("target/user-pdf-mineru-report.txt");

    private static final String VENV = "C:/Users/14928/.workbuddy/binaries/python/envs/mineru";
    private static final String SIDECAR_CMD = VENV + "/Scripts/python.exe " + VENV + "/mineru_sidecar.py";

    @Test
    void chunkUserPdfViaMinerU() throws Exception {
        Files.createDirectories(REPORT.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(REPORT, StandardCharsets.UTF_8))) {
            banner(out, "USER PDF CHUNK REPORT (MinerU path)");
            out.println("sourceFile=" + PDF_PATH);
            out.println("fileExists=" + Files.exists(Paths.get(PDF_PATH))
                    + " bytes=" + (Files.exists(Paths.get(PDF_PATH)) ? Files.size(Paths.get(PDF_PATH)) : -1));

            // MinerU 开启 + 含图片即路由 MinerU
            MinerUProperties props = new MinerUProperties();
            props.setEnabled(true);
            props.setRouteOnImages(true);
            props.setSidecarCommand(SIDECAR_CMD);
            props.setTimeoutMs(600_000L);
            props.setImagesTempDir("C:/tmp/mineru");
            props.setFallbackToPdfbox(true);
            out.println("mineru.enabled=" + props.isEnabled() + " routeOnImages=" + props.isRouteOnImages());
            out.println("sidecarCommand=" + props.getSidecarCommand());

            MinerUClient client = new MinerUSidecarClient(props);
            DocumentParser router = new PdfParserRouter(client, props);

            boolean needsMinerU = ((PdfParserRouter) router).needsMinerU(PDF_PATH);
            out.println("needsMinerU(path)=" + needsMinerU + " => " + (needsMinerU ? "MinerU" : "PDFBox") + " 路径");

            // 解析
            banner(out, "PARSE via PdfParserRouter (MinerU)");
            List<ParsedDocument> parsed = router.parse(PDF_PATH);
            out.println("ParsedDocument count=" + parsed.size());

            Map<String, Long> dist = new LinkedHashMap<>();
            int totalChars = 0;
            for (ParsedDocument d : parsed) {
                dist.merge(d.getContentType(), 1L, Long::sum);
                totalChars += (d.getContent() == null ? 0 : d.getContent().length());
            }
            out.println("content-type distribution=" + dist);
            out.println("parsed total chars=" + totalChars);

            // 分块
            banner(out, "CHUNK via DocumentChunker");
            DocumentChunker chunker = new DocumentChunker();
            List<KnowledgeDocument> chunks = chunker.chunk(parsed);
            out.println("Chunk count=" + chunks.size());

            int idx = 0;
            for (KnowledgeDocument c : chunks) {
                banner(out, "CHUNK #" + idx);
                out.println("id=" + c.getId());
                out.println("sourceType=" + c.getSourceType());
                out.println("title=" + c.getTitle());
                out.println("keywords=" + c.getKeywords());
                out.println("chunkIndex=" + c.getChunkIndex());
                String content = c.getContent();
                out.println("contentLength=" + (content == null ? 0 : content.length()));
                out.println("---- content ----");
                out.println(content);
                out.println("---- end content ----");
                idx++;
            }
            banner(out, "END: total chunks=" + chunks.size());
        }
        System.out.println("[report] written to " + REPORT.toAbsolutePath());
    }

    private static void banner(PrintWriter out, String title) {
        out.println();
        out.println("==================== " + title + " ====================");
    }
}
