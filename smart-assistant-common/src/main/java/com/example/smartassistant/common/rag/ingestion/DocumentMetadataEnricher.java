/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.document.ParsedDocument;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 元数据绑定器（REQ-1）——为解析后的 {@link ParsedDocument} 补全治理所需的元数据。
 * <p>
 * 依据架构 §8.2 规则：
 * <ul>
 *   <li><b>sourceType</b>：由 content type 映射（pdf→PDF / docx→WORD / html→HTML /
 *       md→MARKDOWN / txt→TXT / image→IMAGE）；</li>
 *   <li><b>version</b>：优先 front-matter/字段；否则文件名正则 {@code v\d+}；否则默认 {@code v1}；</li>
 *   <li><b>effectiveAt / expireAt</b>：正文出现「生效:YYYY-MM-DD / 失效:YYYY-MM-DD」时补全；</li>
 *   <li><b>category</b>：优先已有；否则由标题/首句推断（缺省空串，交由 {@link DocumentValidator} 判废）；</li>
 *   <li><b>authorityLevel</b>：由 sourceType 推导（内部文档默认 L2_INTERNAL）。</li>
 * </ul>
 *
 * <p>注：{@link ParsedDocument} 无 sourceType 字段，故 sourceType 通过静态
 * {@link #toSourceType(String)} 供摄入流程在构造 {@code KnowledgeDocument} 时使用。</p>
 */
public class DocumentMetadataEnricher {

    private static final Map<String, String> CONTENT_TYPE_TO_SOURCE = Map.ofEntries(
            Map.entry("pdf", "PDF"),
            Map.entry("docx", "WORD"),
            Map.entry("doc", "WORD"),
            Map.entry("html", "HTML"),
            Map.entry("htm", "HTML"),
            Map.entry("md", "MARKDOWN"),
            Map.entry("markdown", "MARKDOWN"),
            Map.entry("txt", "TXT"),
            Map.entry("text", "TXT"),
            Map.entry("image", "IMAGE"),
            Map.entry("word", "WORD")
    );

    private static final Set<String> ALLOWED_SOURCE =
            Set.of("PDF", "WORD", "HTML", "MARKDOWN", "TXT", "IMAGE");

    private static final Pattern FILENAME_VERSION = Pattern.compile("v(\\d+(?:\\.\\d+)?)");
    private static final Pattern DATE_IN_TEXT =
            Pattern.compile("(生效|开始|起|on|effective)[\\s:：]*((\\d{4})[-/年.](\\d{1,2})[-/月.](\\d{1,2}))");
    private static final Pattern EXPIRE_IN_TEXT =
            Pattern.compile("(失效|截止|到期|过期|off|expire)[\\s:：]*((\\d{4})[-/年.](\\d{1,2})[-/月.](\\d{1,2}))");

    /**
     * 为解析文档补全元数据（返回新的 {@link ParsedDocument}，原对象不可变）。
     */
    public ParsedDocument enrich(ParsedDocument parsed) {
        if (parsed == null) return null;

        String version = deriveVersion(parsed);
        String category = deriveCategory(parsed);
        long[] validity = deriveValidity(parsed);

        ParsedDocument.Builder builder = ParsedDocument.builder()
                .docId(parsed.getDocId())
                .title(parsed.getTitle())
                .content(parsed.getContent())
                .sourceUrl(parsed.getSourceUrl())
                .pageNumber(parsed.getPageNumber())
                .section(parsed.getSection())
                .contentType(parsed.getContentType())
                .tenantId(parsed.getTenantId())
                .version(version)
                .effectiveAt(validity[0] >= 0 ? validity[0] : parsed.getEffectiveAt())
                .expireAt(validity[1] >= 0 ? validity[1] : parsed.getExpireAt())
                .contentHash(parsed.getContentHash())
                .category(category != null ? category : parsed.getCategory())
                .keywords(parsed.getKeywords());
        return builder.build();
    }

    private String deriveVersion(ParsedDocument p) {
        String v = p.getVersion();
        if (v != null && !v.isBlank() && !"v1".equals(v)) return v;
        Matcher m = FILENAME_VERSION.matcher(p.getDocId() != null ? p.getDocId() : "");
        if (m.find()) return "v" + m.group(1);
        return "v1";
    }

    private String deriveCategory(ParsedDocument p) {
        if (p.getCategory() != null && !p.getCategory().isBlank()) return p.getCategory();
        // 由标题首段推断（取标题前 8 字作为粗分类提示）
        String t = p.getTitle();
        if (t != null && !t.isBlank()) {
            return t.length() <= 16 ? t : t.substring(0, 16);
        }
        return "";
    }

    private long[] deriveValidity(ParsedDocument p) {
        long[] result = {-1, -1};
        String content = p.getContent() != null ? p.getContent() : "";
        Matcher me = DATE_IN_TEXT.matcher(content);
        if (me.find()) result[0] = parseLocalDate(me.group(2), me.group(3), me.group(4));
        Matcher mx = EXPIRE_IN_TEXT.matcher(content);
        if (mx.find()) result[1] = parseLocalDate(mx.group(2), mx.group(3), mx.group(4));
        return result;
    }

    private long parseLocalDate(String y, String mo, String d) {
        try {
            LocalDate date = LocalDate.parse(
                    String.format("%s-%02d-%02d", y, Integer.parseInt(mo), Integer.parseInt(d)),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    /** content type → 来源类型（KnowledgeDocument.sourceType） */
    public static String toSourceType(String contentType) {
        if (contentType == null) return "";
        return CONTENT_TYPE_TO_SOURCE.getOrDefault(contentType.toLowerCase(), "");
    }

    /** 来源类型是否合法 */
    public static boolean isAllowedSource(String sourceType) {
        return sourceType != null && ALLOWED_SOURCE.contains(sourceType);
    }

    /** 来源类型 → 权威性等级 */
    public static AuthorityLevel toAuthorityLevel(String sourceType) {
        if ("WORD".equals(sourceType) || "PDF".equals(sourceType)) return AuthorityLevel.L2_INTERNAL;
        if ("HTML".equals(sourceType) || "MARKDOWN".equals(sourceType)) return AuthorityLevel.L2_INTERNAL;
        return AuthorityLevel.L2_INTERNAL;
    }
}
