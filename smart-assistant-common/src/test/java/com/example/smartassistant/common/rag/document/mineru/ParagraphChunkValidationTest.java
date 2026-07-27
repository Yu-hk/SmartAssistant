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
import java.util.List;
import java.util.regex.Pattern;

/**
 * 一次性验证驱动：段落感知聚合切分 vs 现有「逐 block 切分」。
 *
 * <p>背景：MinerU 把正文拆成极碎的物理文本框（中位 19 字符，0% 含换行），现有
 * {@link DocumentChunker} 逐 {@link ParsedDocument} 调用策略且 buffer 不跨元素累积，
 * 导致每个碎 block 单独成块（本 PDF 207 个碎块，很多是一行/一个列表项）。</p>
 *
 * <p>本测试实现「段落感知聚合切分」原型（仅验证，不改动生产代码）：</p>
 * <ol>
 *   <li>读真实 MinerU CLI 的 content_list.json，保留原始类型流
 *       （text/code/table/image + text_level 标题级别）；</li>
 *   <li>把连续碎文本按段落边界（章节大标题 / 表格·图片·代码硬边界 / token 上限）
 *       聚合成「段落单元」，列表项(a)b)c)、编号项连续聚合；</li>
 *   <li>以段落单元为不可分割原子，累积成 ≤maxTokens 的块（绝不在 block 中间切断）；</li>
 *   <li>表格/图片始终独立成块（整体保留）；</li>
 *   <li>同源对比现有策略（真实 MinerUDocumentParser + DocumentChunker）的 207 碎块。</li>
 * </ol>
 */
class ParagraphChunkValidationTest {

    private static final String CLI_JSON = System.getProperty("user.pdf.cli.json",
            "C:/mineru/parse_out/模型接口及使用说明/auto/模型接口及使用说明_content_list.json");
    private static final String SOURCE_PDF = "D:/Desktop/模型接口及使用说明.pdf";
    private static final Path REPORT = Paths.get("target/chunk-paragraph-report.txt");
    private static final Path DETAIL = Paths.get("target/chunk-paragraph-detail.txt");
    private static final Path INDEX = Paths.get("target/chunk-paragraph-index.txt");
    private static final int MAX_TOKENS = 1024;

    /** 章节大标题模式（中文编号），用作段落边界；数字编号一律视为列表项(聚合) */
    private static final Pattern SECTION_TITLE = Pattern.compile(
            "^(第[一二三四五六七八九十百千0-9]+[章节篇条款]"          // 第X章 / 第X节
                    + "|[一二三四五六七八九十百]+[、．.]"              // 一、 二．
                    + "|（[一二三四五六七八九十]+）"                    // （一）（二）
                    + "|\\([一二三四五六七八九十]+\\))");              // (一)(二)

    enum Kind { TEXT, CODE, TABLE, IMAGE }

    static final class Seg {
        final Kind kind;
        final String text;
        final Integer level;
        final int page;
        final String caption;

        Seg(Kind kind, String text, Integer level, int page) { this(kind, text, level, page, null); }
        Seg(Kind kind, String text, Integer level, int page, String caption) {
            this.kind = kind;
            this.text = text;
            this.level = level;
            this.page = page;
            this.caption = caption;
        }
    }

    @Test
    void validateParagraphChunkOnRealPdf() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Paths.get(CLI_JSON).toFile());

        // ---- 1. 构建原始类型流（保留 code/table/text/image + level）----
        List<Seg> segs = new ArrayList<>();
        int rawText = 0, rawCode = 0, rawTable = 0, rawImage = 0, rawEq = 0, rawPage = 0;
        for (JsonNode it : root) {
            if (!it.isObject()) continue;
            String type = it.path("type").asText(null);
            if (type == null) continue;
            int page = it.path("page_idx").asInt(0) + 1;
            Integer level = it.has("text_level") ? it.get("text_level").asInt() : null;
            switch (type) {
                case "text" -> { String t = it.path("text").asText(""); if (!t.isBlank()) { segs.add(new Seg(Kind.TEXT, t, level, page)); rawText++; } }
                case "equation" -> { String t = it.path("text").asText(""); if (!t.isBlank()) { segs.add(new Seg(Kind.TEXT, cleanEquation(t), null, page)); rawEq++; } }
                case "code" -> {
                    StringBuilder sb = new StringBuilder();
                    String cap = it.path("code_caption").asText("");
                    if (!cap.isBlank()) sb.append(cap).append("\n");
                    sb.append(it.path("code_body").asText(""));
                    segs.add(new Seg(Kind.CODE, sb.toString(), null, page)); rawCode++;
                }
                case "table" -> {
                    String body = it.path("table_body").asText("");
                    if (body.isBlank()) { rawTable++; continue; }
                    String tc = it.path("table_caption").asText("");
                    segs.add(new Seg(Kind.TABLE, body, null, page, tc)); rawTable++;
                }
                case "image" -> {
                    String cap = it.path("image_caption").asText("");
                    String ocr = it.path("text").asText("");
                    String content = !cap.isBlank() ? cap : ocr;
                    if (!content.isBlank()) { segs.add(new Seg(Kind.IMAGE, content, null, page, cap)); rawImage++; }
                }
                case "page_number" -> rawPage++;
                default -> { /* 忽略 */ }
            }
        }

        // ---- 2. 旧策略：真实 MinerUDocumentParser + DocumentChunker（逐 block）----
        MinerUParseResponse resp = buildResponse(segs);
        MinerUClient client = req -> resp;
        MinerUDocumentParser parser = new MinerUDocumentParser(client);
        List<ParsedDocument> parsed = parser.parse(SOURCE_PDF);
        List<KnowledgeDocument> oldChunks = new DocumentChunker().chunk(parsed);

        // ---- 3. 新策略：段落感知聚合切分 ----
        List<KnowledgeDocument> newChunks = paragraphChunk(segs);

        // ---- 4. 统计 + 报告 ----
        Files.createDirectories(REPORT.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(REPORT, StandardCharsets.UTF_8));
             PrintWriter detail = new PrintWriter(Files.newBufferedWriter(DETAIL, StandardCharsets.UTF_8));
             PrintWriter index = new PrintWriter(Files.newBufferedWriter(INDEX, StandardCharsets.UTF_8))) {

            banner(out, "PARAGRAPH CHUNK VALIDATION REPORT (对比)");
            out.println("sourcePdf=" + SOURCE_PDF);
            out.println("maxTokens=" + MAX_TOKENS + " (新策略：段落单元为原子，表格/图片独立成块)");
            out.println();
            out.println("原始流: text=" + rawText + " code=" + rawCode + " table=" + rawTable
                    + " image=" + rawImage + " equation=" + rawEq + " page_number(跳过)=" + rawPage
                    + " => 有效片段=" + segs.size());

            banner(out, "A) 现有策略 (MinerUDocumentParser + DocumentChunker 逐block)");
            dumpStats(out, oldChunks);

            banner(out, "B) 段落感知聚合策略 (ParagraphChunk)");
            dumpStats(out, newChunks);

            banner(out, "C) 对比结论");
            out.println("块数: 旧=" + oldChunks.size() + " -> 新=" + newChunks.size()
                    + " (减少 " + (oldChunks.size() - newChunks.size()) + " 块, "
                    + String.format("%.0f%%", (1 - (double) newChunks.size() / oldChunks.size()) * 100) + ")");
            double oldMed = median(tokens(oldChunks)), newMed = median(tokens(newChunks));
            out.println("Token中位数: 旧=" + (int) oldMed + " -> 新=" + (int) newMed
                    + " (块粒度显著变粗, 更接近自然段落)");

            // 全量明细
            banner(detail, "ALL PARAGRAPH CHUNKS DETAIL (chunkCount=" + newChunks.size() + ")");
            detail.println("sourcePdf=" + SOURCE_PDF);
            for (int i = 0; i < newChunks.size(); i++) {
                KnowledgeDocument c = newChunks.get(i);
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
            banner(detail, "END OF PARAGRAPH CHUNKS");

            // 索引
            index.println("# 段落切分块索引 (共 " + newChunks.size() + " 块)");
            index.println("# 序号 | 来源 | 字符 | Token | 标题(前72字)\n");
            for (int i = 0; i < newChunks.size(); i++) {
                KnowledgeDocument c = newChunks.get(i);
                String content = c.getContent() == null ? "" : c.getContent();
                String t = (c.getTitle() == null ? "" : c.getTitle()).replace("\n", " ");
                t = t.length() > 72 ? t.substring(0, 72) + "…" : t;
                index.println(String.format("%3d | %-3s | %5dc | %4dt | %s",
                        i, c.getSourceType(), content.length(),
                        RecursiveChunkStrategy.estimateTokens(content), t));
            }
            banner(out, "END");
        }
        System.out.println("[paragraph-report] written. old=" + oldChunks.size() + " new=" + newChunks.size());
    }

    // ==================== 段落感知聚合切分 ====================

    private List<KnowledgeDocument> paragraphChunk(List<Seg> segs) {
        // Stage 1: 聚合成段落单元（列表项/编号项连续聚合，章节大标题断段，表格/图片/代码硬边界）
        List<Unit> units = new ArrayList<>();
        List<Seg> cur = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            if (s.kind == Kind.TABLE || s.kind == Kind.IMAGE) {
                if (!cur.isEmpty()) { units.add(new Unit(cur, dominantKind(cur))); cur = new ArrayList<>(); }
                units.add(new Unit(List.of(s), s.kind));
                continue;
            }
            if (s.kind == Kind.CODE) {
                if (!cur.isEmpty()) { units.add(new Unit(cur, dominantKind(cur))); cur = new ArrayList<>(); }
                List<Seg> codeGroup = new ArrayList<>();
                codeGroup.add(s);
                int j = i + 1;
                while (j < segs.size() && segs.get(j).kind == Kind.CODE) { codeGroup.add(segs.get(j)); j++; }
                units.add(new Unit(codeGroup, Kind.CODE));
                i = j - 1;
                continue;
            }
            // TEXT / EQUATION
            if (isSectionTitle(s)) {
                boolean lastIsTitle = !cur.isEmpty() && isSectionTitle(cur.get(cur.size() - 1));
                if (!cur.isEmpty() && !lastIsTitle) {
                    units.add(new Unit(cur, dominantKind(cur)));
                    cur = new ArrayList<>();
                }
            }
            cur.add(s);
        }
        if (!cur.isEmpty()) units.add(new Unit(cur, dominantKind(cur)));

        // Stage 2: 段落原子累积（≤maxTokens）；超长段落按句子细分，表格按行分批，绝不在句/行中间切断
        List<KnowledgeDocument> chunks = new ArrayList<>();
        List<String> atoms = new ArrayList<>();
        List<String> followTables = new ArrayList<>();
        int bufTok = 0;
        int seq = 0;
        for (Unit u : units) {
            if (u.kind == Kind.TABLE || u.kind == Kind.IMAGE) {
                String html = u.text();
                String prev = lastParagraph(atoms);
                String cap = u.segs.stream().map(s -> s.caption)
                        .filter(c -> c != null && !c.isBlank()).findFirst().orElse(null);
                boolean follow = (u.kind == Kind.TABLE) && canFollowTable(prev, cap)
                        && (bufTok + u.tokens() <= MAX_TOKENS);
                if (follow) {
                    // 被文本引用的参数表（"详见表1"）并入当前文本块，保持「函数说明+参数表」完整
                    followTables.add(html);
                    bufTok += u.tokens();
                    continue;
                }
                if (!atoms.isEmpty() || !followTables.isEmpty()) {
                    chunks.add(flushText(atoms, followTables, seq++));
                    atoms.clear(); followTables.clear(); bufTok = 0;
                }
                for (String sub : splitTable(u)) {
                    if (!sub.trim().isEmpty()) chunks.add(makeChunk(sub, seq++, "PDF"));
                }
                continue;
            }
            // 章节大标题起始：强制开新块，使块结构贴合阅读顺序
            if (u.startsSection() && !atoms.isEmpty()) {
                chunks.add(flushText(atoms, followTables, seq++));
                atoms.clear(); followTables.clear(); bufTok = 0;
            }
            for (String para : splitUnitToParagraphs(u)) {
                int pt = RecursiveChunkStrategy.estimateTokens(para);
                if (!atoms.isEmpty() && bufTok + pt > MAX_TOKENS) {
                    chunks.add(flushText(atoms, followTables, seq++));
                    atoms.clear(); followTables.clear(); bufTok = 0;
                }
                atoms.add(para);
                bufTok += pt;
            }
        }
        if (!atoms.isEmpty() || !followTables.isEmpty()) chunks.add(flushText(atoms, followTables, seq++));
        return chunks;
    }

    private static final class Unit {
        final List<Seg> segs;
        final Kind kind;
        Unit(List<Seg> segs, Kind kind) { this.segs = segs; this.kind = kind; }
        String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.size(); i++) {
                if (i > 0) sb.append("\n\n");
                sb.append(segs.get(i).text);
            }
            return sb.toString();
        }
        int tokens() { return RecursiveChunkStrategy.estimateTokens(text()); }
        boolean startsSection() {
            return !segs.isEmpty() && isSectionTitle(segs.get(0));
        }
    }

    private Kind dominantKind(List<Seg> segs) {
        for (Seg s : segs) if (s.kind != Kind.TABLE && s.kind != Kind.IMAGE) return s.kind;
        return segs.isEmpty() ? Kind.TEXT : segs.get(0).kind;
    }

    /** 把单元拆成段落级原子（每段 ≤maxTokens；超长段落按句子细分，不切断句子） */
    private List<String> splitUnitToParagraphs(Unit u) {
        List<String> out = new ArrayList<>();
        for (String p : u.text().split("\n\n", -1)) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (RecursiveChunkStrategy.estimateTokens(p) <= MAX_TOKENS) {
                out.add(p);
            } else {
                out.addAll(splitLongText(p));
            }
        }
        return out;
    }

    /** 超长文本按句号/换行细分（句子原子），保证每块 ≤maxTokens 且不在句子中间切断 */
    private List<String> splitLongText(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split("(?<=[。；!！?？])|\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String candidate = sb.length() > 0 ? sb + "\n" + part : part;
            if (sb.length() > 0 && RecursiveChunkStrategy.estimateTokens(candidate) > MAX_TOKENS) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(part);
        }
        if (sb.length() > 0) out.add(sb.toString().trim());
        return out;
    }

    /** 表格按行分批（表头随每组重复），每块 ≤maxTokens，不切断单行 */
    private List<String> splitTable(Unit u) {
        List<String> rows = new ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("<tr>[\\s\\S]*?</tr>").matcher(u.text());
        while (m.find()) rows.add(m.group());
        if (rows.size() <= 1) return List.of(u.text());
        String header = rows.get(0);
        String prefix = "<table>" + header;
        List<String> batches = new ArrayList<>();
        StringBuilder batch = new StringBuilder(prefix);
        for (int i = 1; i < rows.size(); i++) {
            String candidate = batch + rows.get(i) + "</table>";
            if (RecursiveChunkStrategy.estimateTokens(candidate) > MAX_TOKENS && batch.length() > prefix.length()) {
                batches.add(batch + "</table>");
                batch.setLength(0);
                batch.append(prefix);
            }
            batch.append(rows.get(i));
        }
        if (batch.length() > prefix.length()) batches.add(batch + "</table>");
        return batches.isEmpty() ? List.of(u.text()) : batches;
    }

    private KnowledgeDocument flushText(List<String> atoms, List<String> followTables, int seq) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n\n", atoms).trim());
        for (String tbl : followTables) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(tbl);
        }
        return makeChunk(sb.toString().trim(), seq, "PDF");
    }

    private KnowledgeDocument makeChunk(String content, int seq, String sourceType) {
        String title = firstLine(content);
        String id = "模型接口及使用说明-para-g" + seq;
        return new KnowledgeDocument(id, title, content, "pdf", "", -1, -1,
                "", "v1", SOURCE_PDF, seq, "", sourceType);
    }

    private static boolean isSectionTitle(Seg s) {
        if (s.level == null) return false;
        String t = s.text.trim();
        if (t.length() > 40) return false;
        if (t.endsWith("。") || t.endsWith("？") || t.endsWith("！") || t.endsWith(".")) return false;
        return SECTION_TITLE.matcher(t).find();
    }

    /** 表格是否应跟随前一个文本块：被显式引用为参数表（"详见表1"/"输入参数"/"参数说明"等）时并入 */
    private static boolean canFollowTable(String prev, String caption) {
        if (prev == null) return false;
        String p = prev.trim();
        if (p.isEmpty()) return false;
        boolean refTable = p.matches(".*(详见表|见下表|如下表|见?表[0-9一二三四五六七八九十]+).*")
                || p.contains("输入参数") || p.contains("输出参数")
                || p.contains("参数如下") || p.contains("参数说明") || p.contains("字段说明");
        return refTable;
    }

    private static String lastParagraph(List<String> atoms) {
        return atoms.isEmpty() ? null : atoms.get(atoms.size() - 1);
    }

    /** 反向 LaTeX 清洗：把 MinerU 误识别为公式(equation)的代码还原为可读文本；真正公式保留原样 */
    private static String cleanEquation(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        // 含真实数学符号 -> 保留原始 LaTeX，避免破坏公式
        if (s.contains("\\frac") || s.contains("\\sum") || s.contains("\\int") || s.contains("\\sqrt")
                || s.contains("\\alpha") || s.contains("\\beta") || s.contains("\\infty")
                || s.contains("\\theta") || s.contains("\\partial") || s.contains("\\lim")
                || s.contains("\\vec") || s.contains("\\nabla") || s.contains("\\log")
                || s.contains("\\sin") || s.contains("\\cos") || s.contains("\\exp")) {
            return raw;
        }
        s = s.replaceAll("\\$\\$", " ");
        // 去掉 LaTeX 命令（保留花括号作为标识符分组边界）；\\math* 家族（\\mathrm/\\mathfrak/\\mathbf...）一并移除
        s = s.replaceAll("\\\\math[a-zA-Z]+", "");
        s = s.replaceAll("\\\\(left|right|text|textbf|mathit|mathsf|mathcal)\\b", "");
        // 上下标 ^ {...} -> 内容；^ X -> X
        s = s.replaceAll("\\^\\s*\\{\\s*([^}]*?)\\s*\\}", "$1");
        s = s.replaceAll("\\^\\s*([A-Za-z0-9])", "$1");
        // 特殊字符还原
        s = s.replace("\\backslash", "\\").replace("\\{", "{").replace("\\}", "}")
                .replace("\\_", "_").replace("\\&", "&").replace("\\%", "%")
                .replace("\\,", " ").replace("\\;", " ").replace("\\:", " ")
                .replace("\\ ", " ");
        // 从内到外处理花括号分组：组内合并单字符、去下划线空格；组间保留空格（词边界）
        java.util.regex.Pattern inner = java.util.regex.Pattern.compile("\\{([^{}]*)\\}");
        java.util.regex.Matcher im = inner.matcher(s);
        while (im.find()) {
            String g = im.group(1);
            String fixed = g.replaceAll("(?<=\\b[A-Za-z0-9]\\b) (?=\\b[A-Za-z0-9]\\b)", "")
                    .replaceAll(" ?_ ?", "_");
            s = s.replace(im.group(0), fixed);
            im = inner.matcher(s);
        }
        // 花括号外残余单字符合并（顶层字母序列）
        s = s.replaceAll("(?<=\\b[A-Za-z0-9]\\b) (?=\\b[A-Za-z0-9]\\b)", "")
                .replaceAll(" ?_ ?", "_");
        // 去除代码标点旁多余空格
        s = s.replaceAll("\\(\\s+", "(").replaceAll("\\s+\\)", ")");
        s = s.replaceAll("\\[\\s+", "[").replaceAll("\\s+\\]", "]");
        s = s.replaceAll("[ \\t]+", " ");
        return s.trim();
    }

    private static String firstLine(String text) {
        String f = text.split("\n", 2)[0].trim();
        return f.length() > 80 ? f.substring(0, 80) + "..." : f;
    }

    // ==================== 旧策略响应构造 ====================

    private MinerUParseResponse buildResponse(List<Seg> segs) {
        var byPage = new java.util.LinkedHashMap<Integer, List<MinerUBlock>>();
        for (Seg s : segs) {
            MinerUBlock b = new MinerUBlock();
            switch (s.kind) {
                case TEXT -> { b.setType("text"); b.setText(s.text); }
                case CODE -> { b.setType("text"); b.setText(s.text); } // 现有映射把 code 标 text
                case TABLE -> { b.setType("table"); b.setText(s.text); if (s.caption != null) b.setTableCaption(s.caption); }
                case IMAGE -> { b.setType("image"); if (s.caption != null) b.setImageCaption(s.caption); b.setText(s.text); }
            }
            byPage.computeIfAbsent(s.page, k -> new ArrayList<>()).add(b);
        }
        List<MinerUPage> pages = new ArrayList<>();
        for (var e : byPage.entrySet()) pages.add(new MinerUPage(e.getKey(), e.getValue()));
        MinerUParseResponse r = new MinerUParseResponse();
        r.setStatus("ok");
        r.setRequestId("para-validate");
        r.setPages(pages);
        return r;
    }

    // ==================== 报告工具 ====================

    private void dumpStats(PrintWriter out, List<KnowledgeDocument> chunks) {
        List<Integer> tok = tokens(chunks);
        List<Integer> ch = chars(chunks);
        out.println("chunkCount=" + chunks.size());
        out.println("charLen min/median/mean/max="
                + pct(ch, 0) + "/" + pct(ch, 50) + "/" + mean(ch) + "/" + pct(ch, 100));
        out.println("tokenCount min/median/mean/max="
                + pct(tok, 0) + "/" + pct(tok, 50) + "/" + mean(tok) + "/" + pct(tok, 100));
        out.println("chunks>1024tokens=" + tok.stream().filter(t -> t > 1024).count());
    }

    private List<Integer> tokens(List<KnowledgeDocument> chunks) {
        List<Integer> r = new ArrayList<>();
        for (KnowledgeDocument c : chunks)
            r.add(RecursiveChunkStrategy.estimateTokens(c.getContent()));
        return r;
    }
    private List<Integer> chars(List<KnowledgeDocument> chunks) {
        List<Integer> r = new ArrayList<>();
        for (KnowledgeDocument c : chunks) r.add(c.getContent() == null ? 0 : c.getContent().length());
        return r;
    }

    private static int pct(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        List<Integer> s = new ArrayList<>(sorted); s.sort(Integer::compareTo);
        if (p <= 0) return s.get(0);
        if (p >= 100) return s.get(s.size() - 1);
        int idx = (int) Math.ceil(p / 100.0 * s.size()) - 1;
        return s.get(Math.max(0, Math.min(idx, s.size() - 1)));
    }
    private static int median(List<Integer> list) { return pct(list, 50); }
    private static int mean(List<Integer> list) {
        if (list.isEmpty()) return 0;
        long s = 0; for (int v : list) s += v; return (int) (s / list.size());
    }
    private static void banner(PrintWriter out, String title) {
        out.println();
        out.println("==================== " + title + " ====================");
    }
}
