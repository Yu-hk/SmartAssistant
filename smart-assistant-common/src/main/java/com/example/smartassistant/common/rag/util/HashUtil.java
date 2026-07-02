/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 内容哈希工具——支持正文归一化后计算 SHA-256，用于 RAG 文档变更检测。
 * <p>
 * 参考 RAG 文章的生产实践：
 * <ol>
 *   <li>对正文做稳定化处理（去掉时间戳、页脚、随机 ID、连续空格）</li>
 *   <li>基于规范化后的文本计算 SHA-256</li>
 *   <li>避免因页脚时间变化、广告位变化造成假变更</li>
 * </ol>
 * </p>
 *
 * <pre>{@code
 * // 变更检测示例
 * String newHash = HashUtil.normalizeAndHash(rawText);
 * String oldHash = registry.getContentHash(docId);
 * if (newHash.equals(oldHash)) {
 *     // 跳过——文档未变更
 * }
 * }</pre>
 */
public final class HashUtil {

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "更新时间[：:]\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}[T ]?\\d{0,2}:?\\d{0,2}:?\\d{0,2}.*?(?=\\n|$)");
    private static final Pattern DATE_LINE_PATTERN = Pattern.compile(
            "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}(:\\d{2})?.*?(?=\\n|$)");
    private static final Pattern PAGE_FOOTER_PATTERN = Pattern.compile(
            "[—-]{1,10}\\s*第\\s*\\d+\\s*页[—-]{0,10}");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern SPECIAL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]");

    private HashUtil() {}

    /**
     * 规范化正文：去除时间戳、页脚、特殊字符、标准化空白。
     * <p>
     * 使同一文档不同时间读取的原始内容产生相同的 hash 输出，
     * 避免页脚时间变了、广告位变了等假变更触发全量重建。
     * </p>
     *
     * @param text 原始正文
     * @return 规范化后的正文
     */
    public static String normalizeText(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = text;
        // 1. 去除"更新时间：2026-06-29..." 类行
        normalized = TIMESTAMP_PATTERN.matcher(normalized).replaceAll("");
        // 2. 去除日期时间行（如 "2026-06-29 13:30 上海"）
        normalized = DATE_LINE_PATTERN.matcher(normalized).replaceAll("");
        // 3. 去除页脚 "——第 3 页——" 类标记
        normalized = PAGE_FOOTER_PATTERN.matcher(normalized).replaceAll("");
        // 4. 去除控制字符
        normalized = SPECIAL_CHARS.matcher(normalized).replaceAll("");
        // 5. 标准化空白（连续空格、换行、制表符 → 单个空格）
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ");
        return normalized.trim();
    }

    /**
     * 计算 SHA-256 内容哈希（十六进制字符串）。
     *
     * @param text 正文（调用方确保已筛选/归一化）
     * @return 64 字符小写十六进制哈希；输入为空返回空字符串
     */
    public static String sha256Hex(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 规范化 + 哈希一步完成。
     *
     * @param text 原始正文
     * @return 规范化后的 SHA-256 哈希（64 字符十六进制）
     */
    public static String normalizeAndHash(String text) {
        return sha256Hex(normalizeText(text));
    }

    /**
     * 提取多段内容的聚合 hash（按 baseDocId 维度）。
     * 将所有段落规范化后拼接，再做一次哈希。
     *
     * @param contents 同一文档的多段内容列表
     * @return 聚合的 64 字符 SHA-256 哈希
     */
    public static String aggregateHash(java.util.List<String> contents) {
        if (contents == null || contents.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String c : contents) {
            sb.append(normalizeText(c)).append("\n---\n");
        }
        return sha256Hex(sb.toString());
    }
}
