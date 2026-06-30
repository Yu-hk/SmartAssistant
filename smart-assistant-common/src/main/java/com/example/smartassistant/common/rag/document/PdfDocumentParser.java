/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * PDF 文档解析器——基于 Apache PDFBox 3.x 实现。
 * <p>
 * 按页解析，每页输出一个 ParsedDocument，保留页号用于引用溯源。
 * 注意：PDFBox 为纯文本提取，不处理扫描件 OCR。
 * 对于复杂表格和双栏排版的 PDF，当前实现将内容按阅读顺序拼接，
 * 后续可升级为 pdfplumber 风格的布局分析。
 * </p>
 */
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    /** 内容分隔符（双栏 PDF 的列分隔辅助，当前无作用，预留） */
    @SuppressWarnings("unused")
    private static final String COLUMN_SEPARATOR = "\t";

    @Override
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        String sourceUrl = path.toAbsolutePath().toString();

        List<ParsedDocument> results = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int totalPages = document.getNumberOfPages();
            log.info("[PdfParser] 开始解析 PDF: file={}, pages={}", fileName, totalPages);

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                stripper.setSortByPosition(true); // 按阅读顺序排序
                stripper.setLineSeparator("\n");
                stripper.setParagraphStart("\n\n");

                String pageText = stripper.getText(document);
                if (pageText == null || pageText.isBlank()) {
                    log.debug("[PdfParser] 第 {} 页为空，跳过", pageNum);
                    continue;
                }
                pageText = pageText.trim();

                // 按空行分割为段落
                String[] paragraphs = pageText.split("\n\\s*\n");
                for (int paraIdx = 0; paraIdx < paragraphs.length; paraIdx++) {
                    String para = paragraphs[paraIdx].trim();
                    if (para.isBlank()) continue;

                    // 提取首行作为标题（不超过 80 字）
                    String title = extractTitle(para, fileName);
                    String section = "第" + pageNum + "页";
                    String contentHash = sha256(para);

                    ParsedDocument parsed = ParsedDocument.builder()
                            .docId(fileName + "-p" + pageNum + "-s" + (paraIdx + 1))
                            .title(title)
                            .content(para)
                            .sourceUrl(sourceUrl)
                            .pageNumber(pageNum)
                            .section(section)
                            .contentType("pdf")
                            .contentHash(contentHash)
                            .build();
                    results.add(parsed);
                }
            }

            log.info("[PdfParser] 解析完成: file={}, elements={}", fileName, results.size());
        } catch (IOException e) {
            throw new DocumentParseException("PDF 解析失败: " + filePath, e);
        }

        return results;
    }

    /** 提取段落标题：取首行前 80 字 */
    private static String extractTitle(String paragraph, String fallback) {
        String firstLine = paragraph.split("\n", 2)[0].trim();
        if (firstLine.length() > 80) firstLine = firstLine.substring(0, 80) + "...";
        return firstLine.isEmpty() ? fallback : firstLine;
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
