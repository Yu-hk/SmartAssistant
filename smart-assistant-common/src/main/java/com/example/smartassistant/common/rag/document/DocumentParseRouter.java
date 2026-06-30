/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析路由——根据文件扩展名自动选择对应的解析器。
 * <p>
 * 参考 RAG 文章的最佳实践：不能指望一个解析器吃遍所有文档类型。
 * 此处实现"解析路由"：普通文本走轻量解析，PDF 用 PDFBox，
 * Word 走 POI，HTML 走 JSoup。
 * </p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * DocumentParseRouter router = new DocumentParseRouter();
 * List<ParsedDocument> elements = router.parse("/path/to/document.pdf");
 * }</pre>
 */
public class DocumentParseRouter {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseRouter.class);

    private final Map<String, DocumentParser> parsers;

    /** 默认文本解析器兜底 */
    private final DocumentParser fallbackParser;

    public DocumentParseRouter() {
        this.parsers = new HashMap<>();
        this.fallbackParser = new TextFallbackParser();

        // 注册标准解析器
        parsers.put("pdf", new PdfDocumentParser());
        parsers.put("docx", new WordDocumentParser());
        parsers.put("html", new HtmlDocumentParser());
        parsers.put("htm", new HtmlDocumentParser());
        parsers.put("txt", new TextFallbackParser());
    }

    /**
     * 注册自定义解析器。
     *
     * @param extension 文件扩展名（不含点号，如 "pdf"）
     * @param parser    对应的解析器
     */
    public void registerParser(String extension, DocumentParser parser) {
        parsers.put(extension.toLowerCase(), parser);
        log.info("[DocRouter] 注册解析器: .{} -> {}", extension, parser.getClass().getSimpleName());
    }

    /**
     * 解析文档——自动路由到合适的解析器。
     *
     * @param filePath 文档的绝对路径
     * @return 解析出的文档元素列表
     * @throws DocumentParseException 解析失败或格式不支持时抛出
     */
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        String extension = getExtension(fileName);

        DocumentParser parser = parsers.get(extension);
        if (parser == null) {
            log.warn("[DocRouter] 未找到 .{} 的专用解析器，使用兜底文本解析: {}",
                    extension, fileName);
            parser = fallbackParser;
        }

        log.info("[DocRouter] 路由解析: file={}, extension=.{}, parser={}",
                fileName, extension, parser.getClass().getSimpleName());
        return parser.parse(filePath);
    }

    /**
     * 判断是否支持该文件格式的解析。
     */
    public boolean supports(String filePath) {
        String extension = getExtension(filePath);
        return parsers.containsKey(extension) || extension.equals("txt");
    }

    /** 获取已注册的扩展名列表 */
    public List<String> supportedExtensions() {
        return List.copyOf(parsers.keySet());
    }

    // ==================== 内部方法 ====================

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    /**
     * 纯文本兜底解析器——简单按空行分段。
     */
    private static class TextFallbackParser implements DocumentParser {

        private static final Logger log = LoggerFactory.getLogger(TextFallbackParser.class);

        @Override
        public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
            Path path = Paths.get(filePath);
            String fileName = path.getFileName().toString();

            try {
                String content = java.nio.file.Files.readString(path,
                        java.nio.charset.StandardCharsets.UTF_8);
                if (content.isBlank()) return List.of();

                List<ParsedDocument> results = new java.util.ArrayList<>();
                String[] paragraphs = content.split("\n\\s*\n");
                int seq = 0;
                for (String para : paragraphs) {
                    para = para.trim();
                    if (para.isBlank()) continue;
                    String title = para.split("\n", 2)[0].trim();
                    if (title.length() > 80) title = title.substring(0, 80) + "...";

                    results.add(ParsedDocument.builder()
                            .docId(fileName + "-s" + (++seq))
                            .title(title)
                            .content(para)
                            .sourceUrl(path.toAbsolutePath().toString())
                            .pageNumber(-1)
                            .contentType("txt")
                            .build());
                }

                log.info("[TextParser] 解析完成: file={}, elements={}", fileName, results.size());
                return results;
            } catch (java.io.IOException e) {
                throw new DocumentParseException("文本文件读取失败: " + filePath, e);
            }
        }
    }
}
