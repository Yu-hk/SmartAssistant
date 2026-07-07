/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.ingestion;

import java.util.regex.Pattern;

/**
 * 入库前 PII 脱敏器——对标字节 RAG 七连问第二问「清洗 pipeline·PII 脱敏」。
 * <p>
 * 内部文档入库前必须将敏感信息替换为占位标记，避免泄露与检索噪声：
 * <ul>
 *   <li>手机号：1[3-9]\d{9}</li>
 *   <li>身份证号：17 位数字 + 校验位（或 15 位）</li>
 *   <li>邮箱：标准邮箱格式</li>
 *   <li>内部 IP：10.x / 192.168.x / 172.16~31.x</li>
 *   <li>工号：员工编号 / 工号 后的字母数字组合</li>
 * </ul>
 * 采用覆盖式脱敏（替换为 [TAG]），不破坏 chunk 的语义结构与检索可用性。
 * </p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>正则前后加非数字边界，避免误伤订单号等长数字串；</li>
 *   <li>工号保留前缀标签（"工号[EMP_ID]"），保留可追溯语义；</li>
 *   <li>{@link #containsPii(String)} 供入库审核门禁判断是否需人工复核。</li>
 * </ul>
 */
public class PiiScrubber {

    /** 手机号（中国大陆 11 位，前后非数字边界） */
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");

    /** 身份证号（18 位含 X 校验位，或 15 位） */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])\\d{17}[\\dXx](?![0-9])|(?<![0-9])\\d{15}(?![0-9])");

    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 内部 IP（RFC1918 私有网段） */
    private static final Pattern INTERNAL_IP = Pattern.compile(
            "(?<![0-9])(?:10\\.\\d{1,3}|192\\.168|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})(?![0-9])");

    /** 工号（保留前缀标签） */
    private static final Pattern EMP_ID = Pattern.compile(
            "(工号|员工编号|employee[_\\s]?id)[:：]?\\s*([A-Za-z0-9]{4,})",
            Pattern.CASE_INSENSITIVE);

    /**
     * 脱敏文本：将识别到的 PII 替换为占位标记。
     *
     * @param text 原始文本（可为 null）
     * @return 脱敏后文本；null/空原样返回
     */
    public String scrub(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String t = PHONE.matcher(text).replaceAll("[PHONE]");
        t = ID_CARD.matcher(t).replaceAll("[ID_CARD]");
        t = EMAIL.matcher(t).replaceAll("[EMAIL]");
        t = INTERNAL_IP.matcher(t).replaceAll("[INTERNAL_IP]");
        t = EMP_ID.matcher(t).replaceAll("$1[EMP_ID]");
        return t;
    }

    /**
     * 判断文本是否包含 PII（供入库审核门禁使用）。
     */
    public boolean containsPii(String text) {
        if (text == null || text.isBlank()) return false;
        return PHONE.matcher(text).find()
                || ID_CARD.matcher(text).find()
                || EMAIL.matcher(text).find()
                || INTERNAL_IP.matcher(text).find()
                || EMP_ID.matcher(text).find();
    }
}
