/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Word 文档解析器——基于 Apache POI 实现。
 * <p>
 * 支持 .docx 格式的解析。按段落+表格输出 ParsedDocument 列表。
 * 表格内容采用行列拼接格式，保留嵌套表格的上下文关系。
 * 注意：.doc（旧格式）需要 POI-HSMF 额外支持，当前仅处理 .docx。
 * </p>
 */
public class WordDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(WordDocumentParser.class);

    @Override
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        String sourceUrl = path.toAbsolutePath().toString();

        if (!fileName.toLowerCase().endsWith(".docx")) {
            log.warn("[WordParser] 仅支持 .docx 格式，跳过: {}", fileName);
            return List.of();
        }

        List<ParsedDocument> results = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(path.toFile());
             XWPFDocument document = new XWPFDocument(fis)) {

            // 提取文档元信息
            String docTitle = extractDocTitle(document, fileName);

            // 1. 逐段落解析
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            int paraSeq = 0;
            StringBuilder paraBuffer = new StringBuilder();

            for (XWPFParagraph para : paragraphs) {
                String text = para.getText();
                if (text == null || text.isBlank()) {
                    // 空行作为段落分隔
                    if (!paraBuffer.isEmpty()) {
                        String content = paraBuffer.toString().trim();
                        results.add(buildParsedElement(content, docTitle, fileName,
                                sourceUrl, -1, paraSeq));
                        paraBuffer.setLength(0);
                        paraSeq++;
                    }
                    continue;
                }

                // 判断是否为标题（样式名包含 heading / title）
                String style = para.getStyle();
                boolean isHeading = style != null
                        && (style.toLowerCase().contains("heading")
                            || style.toLowerCase().contains("title"));

                if (isHeading && !paraBuffer.isEmpty()) {
                    // 标题触发之前段落的输出
                    String content = paraBuffer.toString().trim();
                    results.add(buildParsedElement(content, docTitle, fileName,
                            sourceUrl, -1, paraSeq));
                    paraBuffer.setLength(0);
                    paraSeq++;
                }

                if (paraBuffer.length() > 0) paraBuffer.append("\n");
                paraBuffer.append(text);
            }

            // 处理最后一段
            if (!paraBuffer.isEmpty()) {
                results.add(buildParsedElement(paraBuffer.toString().trim(),
                        docTitle, fileName, sourceUrl, -1, paraSeq));
            }

            // 2. 逐表格解析
            List<XWPFTable> tables = document.getTables();
            for (int tIdx = 0; tIdx < tables.size(); tIdx++) {
                Set<XWPFTable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                String tableContent = extractTableText(tables.get(tIdx), visited, 0);
                if (!tableContent.isBlank()) {
                    results.add(buildParsedElement(
                            "【表格】\n" + tableContent,
                            docTitle, fileName, sourceUrl, -1,
                            paraSeq + tIdx + 1));
                }
            }

            log.info("[WordParser] 解析完成: file={}, elements={}", fileName, results.size());
        } catch (IOException e) {
            throw new DocumentParseException("Word 文档解析失败: " + filePath, e);
        }

        return results;
    }

    /** 构建 ParsedDocument，自动计算哈希 */
    private static ParsedDocument buildParsedElement(String content, String title,
                                                      String docId, String sourceUrl,
                                                      int pageNumber, int seq) {
        return ParsedDocument.builder()
                .docId(docId + "-s" + seq)
                .title(title)
                .content(content)
                .sourceUrl(sourceUrl)
                .pageNumber(pageNumber)
                .contentType("word")
                .contentHash(sha256(content))
                .build();
    }

    /** 提取文档标题（取文档标题样式段落或第一个段落） */
    private static String extractDocTitle(XWPFDocument document, String fallback) {
        for (XWPFParagraph para : document.getParagraphs()) {
            String style = para.getStyle();
            if (style != null && (style.contains("Title") || style.contains("title"))) {
                String text = para.getText();
                if (text != null && !text.isBlank()) return text.trim();
            }
        }
        // 取第一个非空段落
        for (XWPFParagraph para : document.getParagraphs()) {
            String text = para.getText();
            if (text != null && !text.isBlank()) {
                if (text.length() > 80) text = text.substring(0, 80) + "...";
                return text.trim();
            }
        }
        return fallback;
    }

    /** 提取表格文本：行列拼接格式 */
    private static String extractTableText(XWPFTable table) {
        Set<XWPFTable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return extractTableText(table, visited, 0);
    }

    /**
     * 提取表格文本并保护嵌套遍历。
     * Apache POI 在部分文档中可能返回重复的表格对象；使用对象身份去重并限制深度，
     * 避免自引用或异常嵌套导致 StackOverflowError。
     */
    private static String extractTableText(XWPFTable table,
                                           Set<XWPFTable> visited,
                                           int depth) {
        if (table == null || depth > 8 || !visited.add(table)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            List<XWPFTableCell> cells = row.getTableCells();
            for (int c = 0; c < cells.size(); c++) {
                if (c > 0) sb.append(" | ");
                String cellText = cells.get(c).getText().trim();
                // 处理嵌套表格
                List<XWPFTable> nestedTables = cells.get(c).getTables();
                if (!nestedTables.isEmpty()) {
                    for (XWPFTable nt : nestedTables) {
                        String nested = extractTableText(nt, visited, depth + 1);
                        if (!nested.isBlank()) {
                            cellText += "\n  [嵌套表格]: " + nested;
                        }
                    }
                }
                sb.append(cellText);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** SHA-256 哈希 */
    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }
}
