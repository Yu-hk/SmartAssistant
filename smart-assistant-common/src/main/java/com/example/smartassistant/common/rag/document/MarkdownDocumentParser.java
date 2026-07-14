/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器（REQ-1）——将 {@code .md / .markdown} 解析为统一的 {@link ParsedDocument} 列表。
 * <p>
 * 实现要点：
 * <ul>
 *   <li><b>Front-matter 元数据</b>：文件头 {@code --- ... ---} 区的 {@code key: value} 抽取为
 *       title / category / version / effectiveAt / expireAt / keywords / tenantId / authorityLevel
 *       / sourceType（手动解析，避免强依赖 YAML 库）；</li>
 *   <li><b>按标题分节</b>：以 {@code #} 标题切分章节，每节产出一个 {@link ParsedDocument}（标题即章节名）；</li>
 *   <li><b>纯文本渲染</b>：使用 CommonMark {@link TextContentRenderer} 剥离 Markdown 行内格式，
 *       保证入库正文为干净文本。</li>
 * </ul>
 */
public class MarkdownDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownDocumentParser.class);

    // 注：front-matter 由下方 extractFrontMatter/stripFrontMatter 手动解析（避免 YAML 强依赖），
    // 故解析器无需注册 FrontMatterExtension；parse 入参已是剥离 front-matter 后的正文。
    private static final Parser PARSER = Parser.builder().build();
    private static final TextContentRenderer RENDERER = TextContentRenderer.builder().build();

    private static final Pattern FRONT_MATTER_BOUNDARY = Pattern.compile("^---\\s*$");
    private static final Pattern KV = Pattern.compile("^([A-Za-z0-9_\\-\\u4e00-\\u9fa5]+)\\s*[:：]\\s*(.*)$");
    private static final Pattern DATE = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})");

    @Override
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        Path path = Path.of(filePath);
        String fileName = path.getFileName() != null ? path.getFileName().toString() : filePath;
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (raw.isBlank()) return List.of();

            Map<String, String> front = extractFrontMatter(raw);
            String body = stripFrontMatter(raw);

            Node document = PARSER.parse(body);

            List<Section> sections = splitByHeadings(document);
            if (sections.isEmpty()) {
                // 无标题：整体作为一个章节
                String text = RENDERER.render(document).trim();
                if (text.isBlank()) return List.of();
                sections.add(new Section(front.getOrDefault("title", fileName), text));
            }

            String title = front.getOrDefault("title", sections.get(0).title);
            long effectiveAt = parseDate(front.get("effectiveAt"));
            long expireAt = parseDate(front.get("expireAt"));
            String version = orDefault(front.get("version"), "v1");
            String category = orDefault(front.get("category"), "");
            String keywords = orDefault(front.get("keywords"), "");
            String tenantId = orDefault(front.get("tenantId"), "");

            List<ParsedDocument> results = new ArrayList<>();
            int seq = 0;
            for (Section sec : sections) {
                String content = sec.content.trim();
                if (content.isBlank()) continue;
                String secTitle = (sec.title != null && !sec.title.isBlank()) ? sec.title : title;
                results.add(ParsedDocument.builder()
                        .docId(fileName + "-md" + (++seq))
                        .title(secTitle)
                        .content(content)
                        .sourceUrl(path.toAbsolutePath().toString())
                        .pageNumber(-1)
                        .contentType("markdown")
                        .tenantId(tenantId)
                        .version(version)
                        .effectiveAt(effectiveAt)
                        .expireAt(expireAt)
                        .category(category)
                        .keywords(keywords)
                        .build());
            }
            log.info("[MarkdownParser] 解析完成: file={}, sections={}", fileName, results.size());
            return results;
        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException("Markdown 解析失败: " + filePath, e);
        }
    }

    /** 抽取 front-matter 的 key:value 映射（手动，避免 YAML 依赖） */
    private static Map<String, String> extractFrontMatter(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !FRONT_MATTER_BOUNDARY.matcher(lines[0].trim()).matches()) {
            return map;
        }
        boolean inFm = false;
        StringBuilder captured = new StringBuilder();
        for (String line : lines) {
            if (FRONT_MATTER_BOUNDARY.matcher(line.trim()).matches()) {
                if (!inFm) { inFm = true; continue; }
                else break; // 结束边界
            }
            if (inFm) captured.append(line).append("\n");
        }
        for (String l : captured.toString().split("\n")) {
            Matcher m = KV.matcher(l.trim());
            if (m.matches()) {
                map.put(m.group(1).trim(), m.group(2).trim());
            }
        }
        return map;
    }

    /** 去除 front-matter 块（连同边界行），返回正文 */
    private static String stripFrontMatter(String raw) {
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !FRONT_MATTER_BOUNDARY.matcher(lines[0].trim()).matches()) {
            return raw;
        }
        boolean inFm = false;
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            if (FRONT_MATTER_BOUNDARY.matcher(lines[i].trim()).matches()) {
                if (!inFm) { inFm = true; start = i; continue; }
                else {
                    // 结束边界：保留其后的内容
                    StringBuilder sb = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) sb.append(lines[j]).append("\n");
                    return sb.toString();
                }
            }
        }
        return raw.substring(Math.min(start + 1, raw.length()));
    }

    /** 按标题切分章节（保留标题作为章节名） */
    private static List<Section> splitByHeadings(Node document) {
        List<Section> sections = new ArrayList<>();
        final Section[] current = {new Section("", "")};
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                flush(sections, current[0]);
                current[0] = new Section(renderInline(heading), "");
            }

            @Override
            public void visit(Paragraph paragraph) {
                current[0].content += renderInline(paragraph) + "\n\n";
            }

            @Override
            public void visit(BulletList list) {
                current[0].content += renderInline(list) + "\n\n";
            }

            @Override
            public void visit(BlockQuote quote) {
                current[0].content += renderInline(quote) + "\n\n";
            }
        });
        flush(sections, current[0]);
        return sections;
    }

    private static void flush(List<Section> sections, Section s) {
        if (s.content != null && !s.content.trim().isBlank()) {
            sections.add(new Section(s.title, s.content.trim()));
        }
    }

    private static String renderInline(Node node) {
        try {
            return RENDERER.render(node).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseDate(String s) {
        if (s == null || s.isBlank()) return -1;
        Matcher m = DATE.matcher(s);
        if (m.find()) {
            try {
                LocalDate d = LocalDate.parse(m.group(1).replace('/', '-'),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                return d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    private static String orDefault(String v, String d) {
        return (v == null || v.isBlank()) ? d : v;
    }

    /** 章节载体 */
    private static class Section {
        String title;
        String content;
        Section(String title, String content) { this.title = title; this.content = content; }
    }
}
