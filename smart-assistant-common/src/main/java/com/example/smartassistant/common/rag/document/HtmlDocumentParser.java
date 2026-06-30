/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * HTML 文档解析器——基于 JSoup 实现。
 * <p>
 * 自动移除导航栏、广告、页脚等噪声元素，提取文章主体内容。
 * 按标题元素（h1-h6）分段，每个标题下的内容作为一个 ParsedDocument 元素。
 * 支持提取 Meta 信息（title、description、keywords）作为元数据。
 * </p>
 */
public class HtmlDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(HtmlDocumentParser.class);

    /** 需要移除的噪声选择器列表 */
    private static final String[] NOISE_SELECTORS = {
            "script", "style", "nav", "footer", "header",
            ".nav", ".navbar", ".footer", ".header", ".sidebar",
            ".ad", ".advertisement", ".ads", ".banner",
            ".menu", ".topbar", ".breadcrumb",
            "#nav", "#footer", "#header", "#sidebar",
            "#ad", "#ads", "#advertisement",
            // 页脚的常见 class
            "[class*=copyright]", "[class*=foot]", "[id*=copyright]",
            "[class*=disclaimer]", "[class*=license]"
    };

    @Override
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        String sourceUrl = path.toAbsolutePath().toString();

        List<ParsedDocument> results = new ArrayList<>();

        try {
            // 解析 HTML
            Document doc = Jsoup.parse(new File(filePath), "UTF-8");
            doc.outputSettings().prettyPrint(true);

            // 提取 Meta 信息
            String htmlTitle = extractMeta(doc, "og:title");
            if (htmlTitle == null) htmlTitle = doc.title();
            if (htmlTitle == null || htmlTitle.isBlank()) htmlTitle = fileName;

            String description = extractMeta(doc, "description");
            String keywords = extractMeta(doc, "keywords");

            // 移除噪声元素
            for (String selector : NOISE_SELECTORS) {
                try {
                    doc.select(selector).remove();
                } catch (Exception ignored) {
                    // 某些选择器可能不合法，跳过
                }
            }

            // 提取正文区域（优先 article / main / .content / #content）
            Element body = selectMainContent(doc);

            if (body == null) {
                log.warn("[HtmlParser] 未找到主体内容区域，使用 body: {}", fileName);
                body = doc.body();
            }

            if (body == null) {
                log.warn("[HtmlParser] body 为空: {}", fileName);
                return results;
            }

            // 按标题元素分段
            Elements headings = body.select("h1, h2, h3, h4, h5, h6");
            if (headings.isEmpty()) {
                // 无标题结构，整体作为一个元素
                String text = body.text().trim();
                if (!text.isBlank()) {
                    results.add(buildElement(text, htmlTitle, fileName,
                            sourceUrl, description, keywords));
                }
            } else {
                // 按标题分段
                Element currentSection = null;
                StringBuilder sectionContent = new StringBuilder();
                String currentTitle = htmlTitle;

                for (Element child : body.children()) {
                    String tagName = child.tagName().toLowerCase();
                    boolean isHeading = tagName.matches("h[1-6]");

                    if (isHeading) {
                        // 保存上一节
                        if (sectionContent.length() > 0) {
                            String content = sectionContent.toString().trim();
                            if (!content.isBlank()) {
                                results.add(buildElement(content, currentTitle,
                                        fileName, sourceUrl, description, keywords));
                            }
                            sectionContent.setLength(0);
                        }
                        currentTitle = child.text().trim();
                        continue;
                    }
                    if (sectionContent.length() > 0) sectionContent.append("\n");
                    sectionContent.append(child.text().trim());
                }

                // 最后一段
                if (sectionContent.length() > 0) {
                    String content = sectionContent.toString().trim();
                    if (!content.isBlank()) {
                        results.add(buildElement(content, currentTitle,
                                fileName, sourceUrl, description, keywords));
                    }
                }
            }

            log.info("[HtmlParser] 解析完成: file={}, elements={}", fileName, results.size());
        } catch (IOException e) {
            throw new DocumentParseException("HTML 解析失败: " + filePath, e);
        }

        return results;
    }

    /** 选择 HTML 中的主体内容区域 */
    private static Element selectMainContent(Document doc) {
        // 按优先级选择
        Element main = doc.selectFirst("article");
        if (main != null) return main;
        main = doc.selectFirst("main");
        if (main != null) return main;
        main = doc.selectFirst(".content, #content, .post-content, .article-content, .entry-content");
        if (main != null) return main;
        return doc.body();
    }

    /** 提取 Meta 标签属性 */
    private static String extractMeta(Document doc, String name) {
        // 尝试 name 属性
        Elements metas = doc.select("meta[name=\"" + name + "\"]");
        if (metas.isEmpty()) {
            // 尝试 property 属性（Open Graph）
            metas = doc.select("meta[property=\"" + name + "\"]");
        }
        if (!metas.isEmpty()) {
            String content = metas.first().attr("content");
            if (content != null && !content.isBlank()) return content.trim();
        }
        return null;
    }

    /** 构建 ParsedDocument */
    private static ParsedDocument buildElement(String content, String title,
                                                String docId, String sourceUrl,
                                                String description, String keywords) {
        return ParsedDocument.builder()
                .docId(docId + "-" + sha256(content).substring(0, 8))
                .title(title)
                .content(content)
                .sourceUrl(sourceUrl)
                .pageNumber(-1)
                .section("")
                .contentType("html")
                .contentHash(sha256(content))
                .category(description != null ? description : "")
                .keywords(keywords != null ? keywords : "")
                .build();
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
