/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.apache.tika.Tika;

import java.nio.file.Path;
import java.util.Map;

/**
 * 内容嗅探器（REQ-1）——基于 Apache Tika 检测文件真实媒体类型，用于扩展名不可靠时的兜底路由。
 * <p>
 * 例如：扩展名缺失/错误（{@code .dat}）、或内容与扩展名不符时，用 Tika 识别出
 * {@code application/pdf} / {@code text/html} / {@code text/markdown} 等，再映射回本项目
 * 解析路由约定的 content type（{@code pdf / html / markdown / docx / txt / image}）。
 * </p>
 */
public class TikaDocumentSniffer {

    private final Tika tika = new Tika();

    /** 已知 content type → 解析路由约定的类型标识 */
    private static final Map<String, String> MIME_TO_TYPE = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/msword", "docx"),
            Map.entry("text/html", "html"),
            Map.entry("text/markdown", "markdown"),
            Map.entry("text/x-markdown", "markdown"),
            Map.entry("text/plain", "txt")
    );

    /**
     * 嗅探文件真实类型。
     *
     * @param path 文件路径
     * @return 映射后的类型标识（{@code pdf/html/markdown/docx/txt/image}），无法识别返回 {@code null}
     */
    public String sniff(Path path) {
        try {
            String mime = tika.detect(path);
            if (mime == null || mime.isBlank()) return null;
            // 精确匹配
            String mapped = MIME_TO_TYPE.get(mime);
            if (mapped != null) return mapped;
            // 图片类兜底
            if (mime.startsWith("image/")) return "image";
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 嗅探文件真实类型（字符串路径重载）。
     */
    public String sniff(String filePath) {
        return sniff(Path.of(filePath));
    }
}
