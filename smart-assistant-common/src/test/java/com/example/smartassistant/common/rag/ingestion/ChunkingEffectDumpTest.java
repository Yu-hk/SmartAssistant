package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.ParentChildDocumentChunker;
import com.example.smartassistant.common.rag.chunking.RecursiveChunkStrategy;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.document.PdfDocumentParser;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临时导出工具：真实跑通生产管线（PdfDocumentParser + ParentChildDocumentChunker），
 * 将解析内容、父块、子块明细写入 target/chunking-effect.json，供离线渲染 HTML 报告。
 * 不落地断言，仅 dump。
 */
class ChunkingEffectDumpTest {

    private static final String[] PDFS = {
            "下单操作指南.pdf",
            "订单取消与退款政策.pdf",
            "支付安全与发票说明.pdf"
    };

    @Test
    void dumpChunkingEffect() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser();
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"generatedAt\": \"").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\",\n");
        json.append("  \"config\": {\n");
        json.append("    \"childMaxTokens\": 256,\n");
        json.append("    \"parentMaxTokens\": 1024,\n");
        json.append("    \"overlap\": 50,\n");
        json.append("    \"strategy\": \"SemanticChunkStrategy\",\n");
        json.append("    \"pipeline\": \"PdfDocumentParser.parse -> ParentChildDocumentChunker.chunkParentChild\"\n");
        json.append("  },\n");
        json.append("  \"pdfs\": [\n");

        for (int f = 0; f < PDFS.length; f++) {
            String fileName = PDFS[f];
            Path pdfPath = resolveResource("/knowledge-pdfs/" + fileName);
            List<ParsedDocument> parsed = parser.parse(pdfPath.toString());

            // 解析阶段统计
            List<Map<String, Object>> parsedEls = new ArrayList<>();
            int proseChars = 0;
            long tableCount = parsed.stream()
                    .filter(d -> "pdf-table".equals(d.getContentType())).count();
            int maxPage = 0;
            for (ParsedDocument d : parsed) {
                int chars = d.getContent() == null ? 0 : d.getContent().length();
                if (!"pdf-table".equals(d.getContentType())) proseChars += chars;
                maxPage = Math.max(maxPage, d.getPageNumber());
                Map<String, Object> el = new LinkedHashMap<>();
                el.put("contentType", d.getContentType() == null ? "null" : d.getContentType());
                el.put("page", d.getPageNumber());
                el.put("section", d.getSection());
                el.put("title", d.getTitle());
                el.put("content", d.getContent());
                el.put("charCount", chars);
                parsedEls.add(el);
            }

            // 分块阶段
            ParentChildDocumentChunker.ParentChildResult result = chunker.chunkParentChild(parsed);
            List<KnowledgeDocument> parents = result.parentDocs();
            List<KnowledgeDocument> children = result.childDocs();

            List<Map<String, Object>> parentJson = new ArrayList<>();
            int pMin = Integer.MAX_VALUE, pMax = 0, pSum = 0;
            for (KnowledgeDocument p : parents) {
                int tok = RecursiveChunkStrategy.estimateTokens(p.getContent());
                pMin = Math.min(pMin, tok); pMax = Math.max(pMax, tok); pSum += tok;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("index", p.getChunkIndex());
                m.put("tokens", tok);
                m.put("title", p.getTitle());
                m.put("content", p.getContent());
                parentJson.add(m);
            }

            List<Map<String, Object>> childJson = new ArrayList<>();
            int cMin = Integer.MAX_VALUE, cMax = 0, cSum = 0;
            long orphan = 0;
            for (KnowledgeDocument c : children) {
                int tok = RecursiveChunkStrategy.estimateTokens(c.getContent());
                cMin = Math.min(cMin, tok); cMax = Math.max(cMax, tok); cSum += tok;
                if (c.getParentDocId() == null || c.getParentDocId().isBlank()) orphan++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("parentId", c.getParentDocId());
                m.put("tokens", tok);
                m.put("title", c.getTitle());
                m.put("content", c.getContent());
                childJson.add(m);
            }

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("parsedCount", parsed.size());
            stats.put("proseChars", proseChars);
            stats.put("tableCount", tableCount);
            stats.put("pageCount", maxPage);
            stats.put("parentCount", parents.size());
            stats.put("childCount", children.size());
            stats.put("avgParentTok", parents.isEmpty() ? 0 : pSum / parents.size());
            stats.put("avgChildTok", children.isEmpty() ? 0 : cSum / children.size());
            stats.put("minParentTok", parents.isEmpty() ? 0 : pMin);
            stats.put("maxParentTok", parents.isEmpty() ? 0 : pMax);
            stats.put("minChildTok", children.isEmpty() ? 0 : cMin);
            stats.put("maxChildTok", children.isEmpty() ? 0 : cMax);
            stats.put("orphan", orphan);

            json.append("    {\n");
            json.append("      \"fileName\": ").append(jsonStr(fileName)).append(",\n");
            json.append("      \"stats\": ").append(mapToJson(stats)).append(",\n");
            json.append("      \"parsedElements\": ").append(listToJson(parsedEls)).append(",\n");
            json.append("      \"parents\": ").append(listToJson(parentJson)).append(",\n");
            json.append("      \"children\": ").append(listToJson(childJson)).append("\n");
            json.append("    }");
            json.append(f < PDFS.length - 1 ? ",\n" : "\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        Path out = Paths.get(System.getProperty("user.dir"), "target", "chunking-effect.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString(), StandardCharsets.UTF_8);
        System.out.println("[ChunkingEffectDump] 已写出: " + out.toAbsolutePath());
    }

    // ==================== JSON 构建辅助 ====================

    private String mapToJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append("        \"").append(e.getKey()).append("\": ");
            sb.append(valueToJson(e.getValue()));
            sb.append(i < m.size() - 1 ? ",\n" : "\n");
            i++;
        }
        sb.append("      }");
        return sb.toString();
    }

    private String listToJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append("        ").append(mapToJson(list.get(i)).replace("\n", "\n        "));
            sb.append(i < list.size() - 1 ? ",\n" : "\n");
        }
        sb.append("      ]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String valueToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return jsonStr((String) v);
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Map) return mapToJson((Map<String, Object>) v);
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<Object> l = (List<Object>) v;
            for (int i = 0; i < l.size(); i++) {
                sb.append(valueToJson(l.get(i)));
                if (i < l.size() - 1) sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
        return jsonStr(v.toString());
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private Path resolveResource(String resourcePath) throws Exception {
        URL url = getClass().getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException("测试资源未找到: " + resourcePath);
        }
        return Paths.get(url.toURI());
    }
}
