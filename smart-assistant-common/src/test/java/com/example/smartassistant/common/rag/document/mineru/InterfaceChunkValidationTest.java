/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file for the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.KnowledgeDocument;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一次性验证驱动：按「二级标题 / 接口级」聚合切分。
 *
 * <p>需求：本 PDF 是接口文档，一个接口（接口定义 + 入参 + 结果）应切分为一块。</p>
 *
 * <p>关键难点：MinerU 解析出的标题层级与"接口"粒度并不一一对应。</p>
 * <ul>
 *   <li>严格中文编号的「二级标题」（（一）（二）（三）（四））在本文档中：
 *       （二）频率预测子系统模型——单点 内含 <b>6 个接口</b>，整段约 2.8 万 token，无法作为一块；</li>
 *   <li>真正"一个接口"对应到编号的「三级标题」：1. 短波传播电场计算模型 等，
 *       每个含 模型概述/接口描述/应用实例/参数表；</li>
 *   <li>而（一）模型初始化 这种（一）级标题本身就是一个接口（无更细编号）。</li>
 * </ul>
 *
 * <p>识别规则（deepest non-leaf candidate wins）：</p>
 * <ol>
 *   <li>标题 = text_level != null 的 seg；按文本模式赋 outline rank
 *       （章=1、（一）=2、1.=3、1)=4、a)=5，无编号标题=0）；</li>
 *   <li>候选 = rank ∈ {1,2,3} 且非"叶子小节"（接口描述/应用实例/模型概述 等）；</li>
 *   <li>用栈求出"容器"：被更深候选嵌套的候选标记为非边界；
 *       边界 = 未被标记的最深候选 → 恰好是每个接口；</li>
 *   <li>每个边界标题到下一个边界标题之间的全部内容（含其下小节/参数表/代码）聚合成一块；
 *       父级章节标题作为上下文前缀带入每块。</li>
 * </ol>
 */
class InterfaceChunkValidationTest {

    private static final String CLI_JSON = System.getProperty("user.pdf.cli.json",
            "C:/mineru/parse_out/模型接口及使用说明/auto/模型接口及使用说明_content_list.json");
    private static final String SOURCE_PDF = "D:/Desktop/模型接口及使用说明.pdf";
    private static final Path REPORT = Paths.get("target/chunk-interface-report.txt");
    private static final Path DETAIL = Paths.get("target/chunk-interface-detail.txt");
    private static final Path INDEX = Paths.get("target/chunk-interface-index.txt");
    private static final Path JSON_OUT = Paths.get("target/chunk-interface-result.json");
    private static final Path MD_OUT = Paths.get("target/chunk-interface-result.md");
    private static final Path REPORT_PC = Paths.get("target/chunk-parentchild-report.txt");
    private static final Path DETAIL_PC = Paths.get("target/chunk-parentchild-detail.txt");
    private static final Path JSON_PC = Paths.get("target/chunk-parentchild-result.json");

    enum Kind { TEXT, CODE, TABLE, IMAGE }

    static final class Seg {
        final Kind kind;
        final String text;
        final Integer level;
        final int page;
        final String caption;
        Seg(Kind kind, String text, Integer level, int page) { this(kind, text, level, page, null); }
        Seg(Kind kind, String text, Integer level, int page, String caption) {
            this.kind = kind; this.text = text; this.level = level; this.page = page; this.caption = caption;
        }
    }

    @Test
    void validateInterfaceChunkOnRealPdf() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Paths.get(CLI_JSON).toFile());

        // ---- 1. 构建原始类型流 ----
        SegBuild sb0 = buildSegs(root);
        List<Seg> segs = sb0.segs;

        // ---- 2. 接口级切分 ----
        List<InterfaceChunk> chunks = interfaceChunk(segs);

        // ---- 3. 统计 + 报告 ----
        Files.createDirectories(REPORT.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(REPORT, StandardCharsets.UTF_8));
             PrintWriter detail = new PrintWriter(Files.newBufferedWriter(DETAIL, StandardCharsets.UTF_8));
             PrintWriter index = new PrintWriter(Files.newBufferedWriter(INDEX, StandardCharsets.UTF_8));
             PrintWriter jsonW = new PrintWriter(Files.newBufferedWriter(JSON_OUT, StandardCharsets.UTF_8));
             PrintWriter mdW = new PrintWriter(Files.newBufferedWriter(MD_OUT, StandardCharsets.UTF_8))) {

            banner(out, "INTERFACE-LEVEL CHUNK VALIDATION REPORT");
            out.println("sourcePdf=" + SOURCE_PDF);
            out.println("规则: 一个接口(接口定义+入参+结果)=一块; 父级章节标题作上下文前缀; deepest non-leaf candidate wins");
            out.println();
            out.println("原始流: text=" + sb0.text + " code=" + sb0.code + " table=" + sb0.table
                    + " image=" + sb0.image + " equation=" + sb0.eq + " page_number(跳过)=" + sb0.page
                    + " => 有效片段=" + segs.size());

            banner(out, "A) 接口级切分结果");
            dumpStats(out, chunks);

            banner(out, "B) 嵌入可行性提示");
            long over1024 = chunks.stream().filter(c -> c.tokens > 1024).count();
            long over512 = chunks.stream().filter(c -> c.tokens > 512).count();
            out.println("单接口块 Token 中位数=" + medianTokens(chunks));
            out.println("块数>1024 token(超项目切分目标): " + over1024 + " / " + chunks.size());
            out.println("块数>512 token(超典型 BGE 嵌入上限): " + over512 + " / " + chunks.size());
            out.println("说明: 按需求每个接口整体成块, 部分接口(参数表多)远超嵌入模型上限;");
            out.println("     若需可嵌入, 可对超长接口在其子节(模型概述/接口描述/应用实例)处再切分(本验证保持整块)。");

            banner(out, "C) 块清单(序号|Token|标题)");
            for (int i = 0; i < chunks.size(); i++) {
                InterfaceChunk c = chunks.get(i);
                out.println(String.format("  #%-2d [%4dt] %s", i, c.tokens, firstLine(c.title, 60)));
            }

            // 全量明细
            banner(detail, "ALL INTERFACE CHUNKS DETAIL (chunkCount=" + chunks.size() + ")");
            detail.println("sourcePdf=" + SOURCE_PDF);
            for (int i = 0; i < chunks.size(); i++) {
                InterfaceChunk c = chunks.get(i);
                detail.println("################ CHUNK #" + i
                        + " (sourceType=PDF, chars=" + c.content.length()
                        + ", tokens=" + c.tokens + ") ################");
                detail.println("[TITLE] " + c.title);
                detail.println("[CONTENT]");
                detail.println(c.content);
                detail.println();
            }
            banner(detail, "END OF INTERFACE CHUNKS");

            // 索引
            index.println("# 接口级切分块索引 (共 " + chunks.size() + " 块)");
            index.println("# 序号 | 字符 | Token | 标题(前70字)\n");
            for (int i = 0; i < chunks.size(); i++) {
                InterfaceChunk c = chunks.get(i);
                String t = c.title.replace("\n", " ");
                t = t.length() > 70 ? t.substring(0, 70) + "…" : t;
                index.println(String.format("%3d | %5dc | %4dt | %s",
                        i, c.content.length(), c.tokens, t));
            }
            // ---- 4. 导出用户可直接使用的 JSON / Markdown ----
            ObjectMapper outMapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode arr = outMapper.createArrayNode();
            StringBuilder md = new StringBuilder();
            md.append("# 文档切分结果（接口级聚合）\n\n");
            md.append("- 源文档: ").append(SOURCE_PDF).append("\n");
            md.append("- 总块数: ").append(chunks.size()).append("\n");
            md.append("- 切分策略: MinerU解析 → 接口级聚合（一个接口=一块：接口定义+入参+结果整体）→ 父级章节标题作上下文前缀\n\n");
            md.append("---\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                InterfaceChunk c = chunks.get(i);
                com.fasterxml.jackson.databind.node.ObjectNode o = outMapper.createObjectNode();
                o.put("seq", i);
                o.put("sourceType", "PDF");
                o.put("chars", c.content.length());
                o.put("tokens", c.tokens);
                o.put("title", c.title);
                o.put("content", c.content);
                arr.add(o);
                md.append("## 块 ").append(i).append("　「").append(c.title).append("」\n\n");
                md.append("> 来源: PDF ｜ 字符: ").append(c.content.length())
                        .append(" ｜ Token: ").append(c.tokens).append("\n\n");
                md.append(c.content).append("\n\n---\n\n");
            }
            outMapper.writerWithDefaultPrettyPrinter().writeValue(jsonW, arr);
            mdW.print(md);

            banner(out, "END");
        }
        System.out.println("[interface-report] written. chunks=" + chunks.size());
    }

    @Test
    void validateParentChildChunkOnRealPdf() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Paths.get(CLI_JSON).toFile());
        SegBuild sb = buildSegs(root);
        List<Seg> segs = sb.segs;
        int CHILD_CAP = 512;
        List<ParentChildChunk> parents = new ArrayList<>();
        List<ChildChunk> children = new ArrayList<>();
        buildParentChild(segs, CHILD_CAP, parents, children);

        Files.createDirectories(REPORT_PC.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(REPORT_PC, StandardCharsets.UTF_8));
             PrintWriter detail = new PrintWriter(Files.newBufferedWriter(DETAIL_PC, StandardCharsets.UTF_8));
             PrintWriter jsonW = new PrintWriter(Files.newBufferedWriter(JSON_PC, StandardCharsets.UTF_8))) {

            banner(out, "PARENT-CHILD CHUNK VALIDATION REPORT");
            out.println("sourcePdf=" + SOURCE_PDF);
            out.println("parent=接口级整块(定义+入参+结果); child=子节(模型概述/接口描述/应用实例/参数表)再切分≤" + CHILD_CAP + "t; child→parent_id 关联");
            out.println();
            out.println("原始流: 有效片段=" + segs.size());
            banner(out, "A) 父-子切分结果");
            out.println("parentCount=" + parents.size());
            out.println("childCount=" + children.size());
            out.println("平均每个父块对应子块数=" + String.format("%.2f", (double) children.size() / parents.size()));

            List<Integer> ctok = new ArrayList<>();
            children.forEach(c -> ctok.add(c.tokens));
            out.println("child tokenCount min/median/mean/max=" + pct(ctok, 0) + "/" + pct(ctok, 50) + "/" + mean(ctok) + "/" + pct(ctok, 100));
            long le512 = children.stream().filter(c -> c.tokens <= 512).count();
            long le1024 = children.stream().filter(c -> c.tokens <= 1024).count();
            out.println("child <=512t(可直接BGE嵌入): " + le512 + " / " + children.size());
            out.println("child <=1024t: " + le1024 + " / " + children.size());
            long overCapTables = children.stream().filter(c -> c.tokens > 512 && c.content.contains("<table")).count();
            out.println("child >512t 且为表格块(行批次/单大行, 不切断行): " + overCapTables);
            out.println("说明: 父块超长不嵌入(用于生成); 子块≤512t用于检索; 命中子块经parent_id取回父块整接口作答");

            banner(out, "B) 每块父→子映射");
            for (ParentChildChunk p : parents) {
                out.println(String.format("  %s [%5dt] 子块=%d  %s", p.id, p.tokens, p.childIds.size(), firstLine(p.title, 46)));
            }

            // 全量明细
            banner(detail, "PARENT CHUNKS (" + parents.size() + ")");
            for (ParentChildChunk p : parents) {
                detail.println("################ PARENT " + p.id + " (chars=" + p.content.length()
                        + ", tokens=" + p.tokens + ", childIds=" + p.childIds.size() + ") ################");
                detail.println("[TITLE] " + p.title);
                detail.println("[CHILD_IDS] " + String.join(",", p.childIds));
                detail.println("[CONTENT]");
                detail.println(p.content);
                detail.println();
            }
            banner(detail, "CHILD CHUNKS (" + children.size() + ")");
            for (ChildChunk c : children) {
                detail.println("################ CHILD " + c.id + " (parent=" + c.parentId
                        + ", chars=" + c.content.length() + ", tokens=" + c.tokens + ") ################");
                detail.println("[TITLE] " + c.title);
                detail.println("[CONTENT]");
                detail.println(c.content);
                detail.println();
            }
            banner(detail, "END OF PARENT-CHILD CHUNKS");

            // JSON：parents + children，带 parent_id 关联
            ObjectMapper om = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode pa = om.createArrayNode();
            for (ParentChildChunk p : parents) {
                com.fasterxml.jackson.databind.node.ObjectNode o = om.createObjectNode();
                o.put("chunkId", p.id);
                o.put("sourceType", "PDF");
                o.put("chars", p.content.length());
                o.put("tokens", p.tokens);
                o.put("title", p.title);
                o.put("content", p.content);
                com.fasterxml.jackson.databind.node.ArrayNode ca = om.createArrayNode();
                p.childIds.forEach(ca::add);
                o.set("childIds", ca);
                pa.add(o);
            }
            com.fasterxml.jackson.databind.node.ArrayNode ch = om.createArrayNode();
            for (ChildChunk c : children) {
                com.fasterxml.jackson.databind.node.ObjectNode o = om.createObjectNode();
                o.put("chunkId", c.id);
                o.put("parentId", c.parentId);
                o.put("sourceType", "PDF");
                o.put("chars", c.content.length());
                o.put("tokens", c.tokens);
                o.put("title", c.title);
                o.put("content", c.content);
                ch.add(o);
            }
            com.fasterxml.jackson.databind.node.ObjectNode rootNode = om.createObjectNode();
            rootNode.set("parents", pa);
            rootNode.set("children", ch);
            om.writerWithDefaultPrettyPrinter().writeValue(jsonW, rootNode);

            banner(out, "END");
        }
        System.out.println("[parent-child] parents=" + parents.size() + " children=" + children.size());
    }

    // ==================== 接口级切分核心 ====================

    static final class InterfaceChunk {
        final String title;
        final String content;
        final int tokens;
        InterfaceChunk(String title, String content) {
            this.title = title; this.content = content;
            this.tokens = RecursiveChunkStrategy.estimateTokens(content);
        }
    }

    private static final class BoundsResult {
        final List<Integer> boundaries;
        final Set<Integer> containers;
        BoundsResult(List<Integer> b, Set<Integer> c) { boundaries = b; containers = c; }
    }

    /** 计算接口级边界 + 容器标题集合（父块与父-子切分共用） */
    private BoundsResult interfaceBounds(List<Seg> segs) {
        int n = segs.size();
        boolean[] isHeading = new boolean[n];
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) {
            Seg s = segs.get(i);
            if (s.level != null) {
                isHeading[i] = true;
                Integer r = rankOf(s.text);
                rank[i] = (r != null) ? r : 0; // 无编号标题(封面/主标题) rank=0
            }
        }
        // 叶子锚定：每个"接口描述/应用实例/模型概述"叶子小节，
        // 向前找到最近的"非叶子标题"即为接口边界。可同时正确处理两种结构：
        // a) 子系统接口：1. 短波传播电场计算模型（其叶子 2)接口描述 的父标题）
        // b) 模型初始化：1. 接口描述 直接位于章下，父标题=章（四、模型接口及使用说明）
        Set<Integer> leafBoundaries = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            if (isHeading[i] && isLeafSubsection(segs.get(i).text)) {
                for (int j = i - 1; j >= 0; j--) {
                    if (isHeading[j] && !isLeafSubsection(segs.get(j).text)) { leafBoundaries.add(j); break; }
                }
            }
        }
        // 二级标题若不含更深接口子节点(非叶子锚定边界的祖先)→本身也是边界
        // （如环境配置项的 (一) 使用前授权管理 / (二) Windows…）；
        // 含更深接口子节点的二级标题=容器（如 (二) 频率预测子系统），仅作上下文前缀，从正文剥离
        Set<Integer> containers = new LinkedHashSet<>();
        Set<Integer> sectionBoundaries = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            if (isHeading[i] && rank[i] == 2 && !isLeafSubsection(segs.get(i).text)) {
                if (isAncestorOfAny(i, leafBoundaries, isHeading, rank)) containers.add(i);
                else sectionBoundaries.add(i);
            }
        }
        // 合并边界，保持文档出现顺序；文档开头到首个边界之间内容生成引导块
        Set<Integer> bset = new LinkedHashSet<>(leafBoundaries);
        bset.addAll(sectionBoundaries);
        List<Integer> boundaries = new ArrayList<>(bset);
        boundaries.sort(Integer::compareTo);
        if (!boundaries.isEmpty() && boundaries.get(0) > 0) boundaries.add(0, 0);
        return new BoundsResult(boundaries, containers);
    }

    private List<InterfaceChunk> interfaceChunk(List<Seg> segs) {
        BoundsResult br = interfaceBounds(segs);
        List<Integer> boundaries = br.boundaries;
        Set<Integer> containers = br.containers;
        int n = segs.size();
        // 组装需要 rank/isHeading
        int[] rank = new int[n];
        boolean[] isHeading = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (segs.get(i).level != null) {
                isHeading[i] = true;
                Integer r = rankOf(segs.get(i).text);
                rank[i] = (r != null) ? r : 0;
            }
        }
        // 3) 组装块：维护 outline 栈以携带祖先上下文
        List<InterfaceChunk> result = new ArrayList<>();
        List<String> outlineStack = new ArrayList<>(); // {rank::text}
        int bptr = 0;
        for (int i = 0; i < n; i++) {
            if (isHeading[i]) {
                int r = rank[i];
                while (!outlineStack.isEmpty() && parseRank(outlineStack.get(outlineStack.size() - 1)) >= r) outlineStack.remove(outlineStack.size() - 1);
                outlineStack.add(r + "::" + segs.get(i).text);
            }
            if (bptr < boundaries.size() && boundaries.get(bptr) == i) {
                // 祖先 = outlineStack 去掉最后一项(本接口自身标题)
                List<String> ancestors = new ArrayList<>(outlineStack);
                if (!ancestors.isEmpty()) ancestors.remove(ancestors.size() - 1);
                int next = (bptr + 1 < boundaries.size()) ? boundaries.get(bptr + 1) : n;
                StringBuilder body = new StringBuilder();
                for (int j = i; j < next; j++) {
                    if (containers.contains(j)) continue; // 容器标题不入正文，仅作上下文前缀
                    body.append(segText(segs.get(j))).append("\n\n");
                }
                String ancestorPrefix = ancestors.isEmpty() ? ""
                        : String.join("\n", stripRank(ancestors)) + "\n\n";
                String content = (ancestorPrefix + body.toString()).trim();
                result.add(new InterfaceChunk(firstLine(body.toString().trim(), 80), content));
                bptr++;
            }
        }
        return result;
    }

    // ==================== 父-子切分（接口级父块 + 子节子块 + parent_id 关联） ====================

    static final class ParentChildChunk {
        final String id;
        final String title;
        final String content;
        final int tokens;
        final List<String> childIds = new ArrayList<>();
        ParentChildChunk(String id, String title, String content) {
            this.id = id; this.title = title; this.content = content;
            this.tokens = RecursiveChunkStrategy.estimateTokens(content);
        }
    }

    static final class ChildChunk {
        final String id;
        final String parentId;
        final String title;
        final String content;
        final int tokens;
        ChildChunk(String id, String parentId, String title, String content) {
            this.id = id; this.parentId = parentId; this.title = title; this.content = content;
            this.tokens = RecursiveChunkStrategy.estimateTokens(content);
        }
    }

    private String assembleContent(List<Seg> segs, int start, int end, Set<Integer> containers) {
        StringBuilder b = new StringBuilder();
        for (int j = start; j < end; j++) {
            if (containers != null && containers.contains(j)) continue;
            b.append(segText(segs.get(j))).append("\n\n");
        }
        return b.toString().trim();
    }

    /** 把子节单元按 token 上限切分；表格按行分批(表头重复,不切断行)；超长文本按句切分 */
    private List<String> splitUnits(List<String> units, List<Boolean> isTable, int cap) {
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTok = 0;
        for (int u = 0; u < units.size(); u++) {
            List<String> parts = new ArrayList<>();
            if (isTable.get(u)) {
                if (RecursiveChunkStrategy.estimateTokens(units.get(u)) <= cap) parts.add(units.get(u));
                else parts.addAll(splitTableHtml(units.get(u), cap));
            } else if (RecursiveChunkStrategy.estimateTokens(units.get(u)) <= cap) {
                parts.add(units.get(u));
            } else {
                parts.addAll(splitBySentence(units.get(u), cap));
            }
            for (String p : parts) {
                int pTok = RecursiveChunkStrategy.estimateTokens(p);
                if (cur.length() > 0 && curTok + pTok > cap) {
                    pieces.add(cur.toString().trim());
                    cur = new StringBuilder(); curTok = 0;
                }
                if (cur.length() > 0) cur.append("\n\n");
                cur.append(p); curTok += pTok;
            }
        }
        if (cur.length() > 0) pieces.add(cur.toString().trim());
        return pieces;
    }

    /** 超长 HTML 表格按行分批：表头(或首行)随每个批次重复，绝不切断单行 */
    private List<String> splitTableHtml(String html, int cap) {
        List<String> res = new ArrayList<>();
        if (html == null || html.isBlank()) return res;
        java.util.regex.Pattern trPat = java.util.regex.Pattern.compile("<tr[\\s>][\\s\\S]*?</tr>", java.util.regex.Pattern.CASE_INSENSITIVE);
        // 表头：优先 <thead>，否则用首个 <tr>
        String header = "";
        java.util.regex.Matcher th = java.util.regex.Pattern.compile("<thead>[\\s\\S]*?</thead>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (th.find()) header = th.group(0);
        else {
            java.util.regex.Matcher ftr = trPat.matcher(html);
            if (ftr.find()) header = ftr.group(0);
        }
        List<String> rows = new ArrayList<>();
        java.util.regex.Matcher rm = trPat.matcher(html);
        int idx = 0;
        while (rm.find()) {
            if (idx++ == 0 && !header.isEmpty() && header.equals(rm.group(0))) continue; // 跳过已作表头的首行
            rows.add(rm.group(0));
        }
        if (rows.isEmpty()) { res.add(html.trim()); return res; } // 无法拆分则整表
        StringBuilder cur = new StringBuilder();
        int curTok = 0;
        if (!header.isEmpty()) { cur.append(header); curTok = RecursiveChunkStrategy.estimateTokens(header); }
        for (String row : rows) {
            int rTok = RecursiveChunkStrategy.estimateTokens(row);
            if (cur.length() > 0 && curTok + rTok > cap) {
                res.add(wrapTable(cur.toString()));
                cur = new StringBuilder();
                if (!header.isEmpty()) { cur.append(header); curTok = RecursiveChunkStrategy.estimateTokens(header); }
                else curTok = 0;
            }
            cur.append(row); curTok += rTok;
        }
        if (cur.length() > 0) res.add(wrapTable(cur.toString()));
        return res;
    }

    private static String wrapTable(String inner) {
        String t = inner.trim();
        if (t.toLowerCase().startsWith("<table")) return t;
        return "<table>" + t + "</table>";
    }

    private List<String> splitBySentence(String text, int cap) {
        List<String> res = new ArrayList<>();
        String[] sents = text.split("(?<=[。！？!?；;\\n])");
        StringBuilder cur = new StringBuilder(); int curTok = 0;
        for (String s : sents) {
            s = s.trim(); if (s.isEmpty()) continue;
            int t = RecursiveChunkStrategy.estimateTokens(s);
            if (cur.length() > 0 && curTok + t > cap) { res.add(cur.toString().trim()); cur = new StringBuilder(); curTok = 0; }
            if (cur.length() > 0) cur.append(" ");
            cur.append(s); curTok += t;
        }
        if (cur.length() > 0) res.add(cur.toString().trim());
        return res;
    }

    /** 父-子切分：父块=接口级整块(用于生成)；子块=子节再切分≤cap(用于检索)，经 parent_id 关联 */
    private void buildParentChild(List<Seg> segs, int childCap,
                                  List<ParentChildChunk> parents, List<ChildChunk> children) {
        BoundsResult br = interfaceBounds(segs);
        List<Integer> boundaries = br.boundaries;
        Set<Integer> containers = br.containers;
        int n = segs.size();
        boolean[] isHeading = new boolean[n];
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) {
            if (segs.get(i).level != null) {
                isHeading[i] = true;
                Integer r = rankOf(segs.get(i).text);
                rank[i] = (r != null) ? r : 0;
            }
        }
        // 预计算每个 seg 的祖先前缀（章节链）
        String[] ancestorPrefixAt = new String[n];
        List<String> os = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (isHeading[i]) {
                int r = rank[i];
                while (!os.isEmpty() && parseRank(os.get(os.size() - 1)) >= r) os.remove(os.size() - 1);
                os.add(r + "::" + segs.get(i).text);
            }
            List<String> anc = new ArrayList<>(os);
            if (!anc.isEmpty()) anc.remove(anc.size() - 1);
            ancestorPrefixAt[i] = anc.isEmpty() ? "" : String.join("\n", stripRank(anc));
        }
        for (int bi = 0; bi < boundaries.size(); bi++) {
            int i = boundaries.get(bi);
            int next = (bi + 1 < boundaries.size()) ? boundaries.get(bi + 1) : n;
            String parentBody = assembleContent(segs, i, next, containers);
            String ancestorPrefix = ancestorPrefixAt[i];
            String parentContent = (ancestorPrefix.isEmpty() ? "" : ancestorPrefix + "\n\n") + parentBody;
            String parentId = "iface_" + bi;
            ParentChildChunk parent = new ParentChildChunk(parentId, firstLine(parentBody, 80), parentContent.trim());
            // 子节边界：父接口内的子节标题（编号标题或叶子小节），按文本模式识别（MinerU 深子节常无 text_level）
            Integer pr = headingRankByText(segs.get(i).text);
            int parentRank = (pr != null) ? pr : 0;
            List<Integer> childBounds = new ArrayList<>();
            for (int j = i + 1; j < next; j++) {
                Integer r = headingRankByText(segs.get(j).text);
                if (r != null && r > parentRank) childBounds.add(j);
                else if (r == null && isLeafSubsection(segs.get(j).text)) childBounds.add(j);
            }
            if (childBounds.isEmpty()) {
                childBounds.add(i); // 整个接口作为一段（再按 cap 切分）
            } else {
                if (childBounds.get(0) > i + 1) childBounds.add(0, i); // 接口标题到首个子节之间的引言
            }
            childBounds.add(next);
            childBounds.sort(Integer::compareTo);
            int cc = 0;
            for (int c = 0; c < childBounds.size(); c++) {
                int cs = childBounds.get(c);
                int ce = (c + 1 < childBounds.size()) ? childBounds.get(c + 1) : next;
                String childBody = assembleContent(segs, cs, ce, null);
                String childTitle = firstLine(childBody, 60);
                // 上下文前缀：章节链 + 父接口标题（让子块携带归属信息）
                String ctxPrefix = parent.title + (ancestorPrefix.isEmpty() ? "" : "\n" + ancestorPrefix);
                List<String> units = new ArrayList<>();
                List<Boolean> isTable = new ArrayList<>();
                for (int j = cs; j < ce; j++) {
                    units.add(segText(segs.get(j)));
                    isTable.add(segs.get(j).kind == Kind.TABLE);
                }
                List<String> pieces = splitUnits(units, isTable, childCap);
                for (String piece : pieces) {
                    String ccontent = (ctxPrefix + "\n\n" + piece).trim();
                    String childId = parentId + "_c" + String.format("%02d", cc++);
                    children.add(new ChildChunk(childId, parentId, childTitle, ccontent));
                    parent.childIds.add(childId);
                }
            }
            parents.add(parent);
        }
    }

    /** 从 MinerU content_list.json 构建原始类型流（两个测试共用） */
    static final class SegBuild {
        final List<Seg> segs;
        final int text, code, table, image, eq, page;
        SegBuild(List<Seg> segs, int text, int code, int table, int image, int eq, int page) {
            this.segs = segs; this.text = text; this.code = code; this.table = table;
            this.image = image; this.eq = eq; this.page = page;
        }
    }

    private SegBuild buildSegs(JsonNode root) {
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
                    segs.add(new Seg(Kind.TABLE, body, null, page, it.path("table_caption").asText(""))); rawTable++;
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
        return new SegBuild(segs, rawText, rawCode, rawTable, rawImage, rawEq, rawPage);
    }

    private String segText(Seg s) {
        switch (s.kind) {
            case TEXT -> { return s.text; }
            case CODE -> { return "```\n" + s.text + "\n```"; }
            case TABLE -> { return s.text; } // 整表保留
            case IMAGE -> { return s.text; }
            default -> { return s.text; }
        }
    }

    /** 标题 i 是否是指定边界集合里某个边界 b 的父标题（b 前最近一个 rank < rank(b) 的标题） */
    private boolean isAncestorOfAny(int i, Set<Integer> targets, boolean[] isHeading, int[] rank) {
        for (int b : targets) {
            if (b <= i) continue;
            int bRank = rank[b];
            int anc = -1;
            for (int j = b - 1; j >= 0; j--) {
                if (isHeading[j] && rank[j] < bRank) { anc = j; break; }
            }
            if (anc == i) return true;
        }
        return false;
    }

    // ==================== 标题识别 ====================

    private static final Pattern RE_CHAPTER = Pattern.compile("^[一二三四五六七八九十百]+[、.．]");
    private static final Pattern RE_SECTION = Pattern.compile("^[（(][一二三四五六七八九十百]+[）)]");
    private static final Pattern RE_NUM    = Pattern.compile("^\\d+[.、．]");
    private static final Pattern RE_PARNUM = Pattern.compile("^[（(]\\d+[）)]");
    private static final Pattern RE_ALPHA  = Pattern.compile("^[a-zA-Z][)）]");

    private static Integer rankOf(String t) {
        if (t == null) return null;
        t = t.trim();
        if (RE_CHAPTER.matcher(t).find()) return 1;
        if (RE_SECTION.matcher(t).find()) return 2;
        if (RE_NUM.matcher(t).find()) return 3;
        if (RE_PARNUM.matcher(t).find()) return 4;
        if (RE_ALPHA.matcher(t).find()) return 5;
        return null;
    }

    /** 仅按文本模式判定标题层级（不依赖 text_level）。
     *  MinerU 深层子节(如 "1) 模型概述")常无 text_level，故子节切分用它而非 rankOf。 */
    private static Integer headingRankByText(String t) {
        if (t == null) return null;
        t = t.trim();
        if (RE_CHAPTER.matcher(t).find()) return 1;
        if (RE_SECTION.matcher(t).find()) return 2;
        if (RE_NUM.matcher(t).find()) return 3;
        if (t.matches("^[（(]\\d+[）)].*")) return 2;
        if (t.matches("^\\d+[)）].*")) return 4;   // "1) 模型概述" 这类 ASCII/全角右括号编号
        if (t.matches("^[a-zA-Z][)）].*")) return 5; // "a) 获取 MAC 地址"
        return null;
    }

    private static final Set<String> LEAF_SET = Set.of(
            "接口描述", "应用实例", "模型概述", "参数说明", "输入参数", "输出参数",
            "返回值", "函数说明", "功能说明", "字段说明", "结果说明", "调用示例",
            "概述", "说明", "注意事项", "参数表", "参数列表");

    private static boolean isLeafSubsection(String t) {
        if (t == null) return false;
        String stripped = t.replaceFirst(
                "^([一二三四五六七八九十百]+[、.．]|[（(][一二三四五六七八九十百]+[）)]|\\d+[.、．]|\\d+[)）]|[（(]\\d+[）)]|[a-zA-Z][)）])\\s*", "")
                .trim();
        stripped = stripped.replaceAll("[：:：.\\s]+$", "");
        return LEAF_SET.contains(stripped);
    }

    private static int parseRank(String e) { return Integer.parseInt(e.split("::", 2)[0]); }
    private static List<String> stripRank(List<String> list) {
        List<String> r = new ArrayList<>();
        for (String e : list) r.add(e.split("::", 2)[1]);
        return r;
    }

    // ==================== 报告工具 ====================

    private void dumpStats(PrintWriter out, List<InterfaceChunk> chunks) {
        List<Integer> tok = new ArrayList<>(), ch = new ArrayList<>();
        for (InterfaceChunk c : chunks) { tok.add(c.tokens); ch.add(c.content.length()); }
        out.println("chunkCount=" + chunks.size());
        out.println("charLen min/median/mean/max=" + pct(ch,0) + "/" + pct(ch,50) + "/" + mean(ch) + "/" + pct(ch,100));
        out.println("tokenCount min/median/mean/max=" + pct(tok,0) + "/" + pct(tok,50) + "/" + mean(tok) + "/" + pct(tok,100));
    }

    private int medianTokens(List<InterfaceChunk> chunks) {
        List<Integer> t = new ArrayList<>(); chunks.forEach(c -> t.add(c.tokens)); return pct(t, 50);
    }

    private static int pct(List<Integer> list, int p) {
        if (list.isEmpty()) return 0;
        List<Integer> s = new ArrayList<>(list); s.sort(Integer::compareTo);
        if (p <= 0) return s.get(0);
        if (p >= 100) return s.get(s.size() - 1);
        int idx = Math.max(0, Math.min(s.size() - 1, (int) Math.ceil(p / 100.0 * s.size()) - 1));
        return s.get(idx);
    }
    private static int mean(List<Integer> list) {
        if (list.isEmpty()) return 0;
        long s = 0; for (int v : list) s += v; return (int) (s / list.size());
    }
    private static String firstLine(String text, int max) {
        if (text == null) return "";
        String f = text.split("\n", 2)[0].trim();
        return f.length() > max ? f.substring(0, max) + "…" : f;
    }

    /** 反向 LaTeX 清洗：把 MinerU 误识别为公式的代码还原；真正公式保留原样 */
    private static String cleanEquation(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (s.contains("\\frac") || s.contains("\\sum") || s.contains("\\int") || s.contains("\\sqrt")
                || s.contains("\\alpha") || s.contains("\\beta") || s.contains("\\infty")
                || s.contains("\\theta") || s.contains("\\partial") || s.contains("\\lim")
                || s.contains("\\vec") || s.contains("\\nabla") || s.contains("\\log")
                || s.contains("\\sin") || s.contains("\\cos") || s.contains("\\exp")) {
            return raw;
        }
        s = s.replaceAll("\\$\\$", " ");
        s = s.replaceAll("\\\\math[a-zA-Z]+", "");
        s = s.replaceAll("\\\\(left|right|text|textbf|mathit|mathsf|mathcal)\\b", "");
        s = s.replaceAll("\\^\\s*\\{\\s*([^}]*?)\\s*\\}", "$1");
        s = s.replaceAll("\\^\\s*([A-Za-z0-9])", "$1");
        s = s.replace("\\backslash", "\\").replace("\\{", "{").replace("\\}", "}")
                .replace("\\_", "_").replace("\\&", "&").replace("\\%", "%")
                .replace("\\,", " ").replace("\\;", " ").replace("\\:", " ")
                .replace("\\ ", " ");
        java.util.regex.Pattern inner = java.util.regex.Pattern.compile("\\{([^{}]*)\\}");
        java.util.regex.Matcher im = inner.matcher(s);
        while (im.find()) {
            String g = im.group(1);
            String fixed = g.replaceAll("(?<=\\b[A-Za-z0-9]\\b) (?=\\b[A-Za-z0-9]\\b)", "")
                    .replaceAll(" ?_ ?", "_");
            s = s.replace(im.group(0), fixed);
            im = inner.matcher(s);
        }
        s = s.replaceAll("(?<=\\b[A-Za-z0-9]\\b) (?=\\b[A-Za-z0-9]\\b)", "")
                .replaceAll(" ?_ ?", "_");
        s = s.replaceAll("\\(\\s+", "(").replaceAll("\\s+\\)", ")");
        s = s.replaceAll("\\[\\s+", "[").replaceAll("\\s+\\]", "]");
        s = s.replaceAll("[ \\t]+", " ");
        return s.trim();
    }

    private static void banner(PrintWriter out, String title) {
        out.println();
        out.println("==================== " + title + " ====================");
    }
}
