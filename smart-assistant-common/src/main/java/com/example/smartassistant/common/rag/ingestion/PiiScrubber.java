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
 * 内部文档入库前必须将敏感信息替换为占位标记，避免泄露与检索噪声。覆盖类别：
 * <ul>
 *   <li>手机号：1[3-9]\d{9}</li>
 *   <li>身份证号：17 位数字 + 校验位（或 15 位）</li>
 *   <li>银行卡号：16 位 / 19 位（纯数字，避开身份证 15/18 区间）</li>
 *   <li>邮箱：标准邮箱格式</li>
 *   <li>内部 IP：10.x / 192.168.x / 172.16~31.x</li>
 *   <li>住址：道路名 + 门牌/楼栋/室（如「xx路yy号」「zz栋」）</li>
 *   <li>工号：员工编号 / 工号 后的字母数字组合</li>
 *   <li>姓名：仅覆盖带显式标签（姓名/联系人/收货人/持卡人/投保人/被保险人/经办人）
 *       后的 2-4 个汉字；非 NER，自由文本中的无标签姓名不处理（已知局限见文末）</li>
 * </ul>
 * 采用覆盖式脱敏（替换为 [TAG]），不破坏 chunk 的语义结构与检索可用性。
 * </p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>正则前后加非数字/非汉字边界，避免误伤订单号、工单号等长数字/汉字串；</li>
 *   <li>工号保留前缀标签（"工号[EMP_ID]"），姓名保留标签（"姓名[NAME]"），保留可追溯语义；</li>
 *   <li>{@link #containsPii(String)} 供入库审核门禁判断——既可判定原始文本是否含 PII，
 *       也可在脱敏后做残留检测（规则未覆盖的 PII 仍存在则送人工复核）。</li>
 * </ul>
 *
 * <p>已知局限（非缺陷，属规则级方案边界，须如实告知使用者）：</p>
 * <ul>
 *   <li>中文姓名无构词规律，纯正则可误杀（如「客户中心」）或漏杀（如「张三的订单」中带领属的姓名）；
 *       本类仅对显式标签后的姓名脱敏。如需高精度姓名/实体识别，应引入 NER（如 HanLP），不在本类范围。</li>
 *   <li>住址仅覆盖「道路 + 门牌/楼栋/室」组合，孤立省市区（如「北京市」）不脱敏（属公开地理信息）。</li>
 *   <li>护照号、军官证、车牌、生物特征等非规则敏感信息未覆盖；如需覆盖应在 {@code containsPii}
 *       扩展或引入专用识别模型。</li>
 * </ul>
 */
public class PiiScrubber {

    /** 手机号（中国大陆 11 位，前后非数字边界） */
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");

    /** 身份证号（18 位含 X 校验位，或 15 位） */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])\\d{17}[\\dXx](?![0-9])|(?<![0-9])\\d{15}(?![0-9])");

    /** 银行卡号（16 位或 19 位纯数字，避开身份证 15/18 区间） */
    private static final Pattern BANK_CARD = Pattern.compile(
            "(?<![0-9])(?:\\d{16}|\\d{19})(?![0-9])");

    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 内部 IP（RFC1918 私有网段） */
    private static final Pattern INTERNAL_IP = Pattern.compile(
            "(?<![0-9])(?:10\\.\\d{1,3}|192\\.168|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})(?![0-9])");

    /** 住址：道路名 + 门牌/楼栋/室（门牌号属敏感，脱敏） */
    private static final Pattern ADDRESS = Pattern.compile(
            "[\\u4e00-\\u9fa5]{1,10}(?:路|街|道|巷|大道)[\\u4e00-\\u9fa50-9A-Za-z]{0,15}"
            + "(?:\\d+号|\\d+号楼|\\d+栋|\\d+单元|\\d+室)");

    /** 工号（保留前缀标签） */
    private static final Pattern EMP_ID = Pattern.compile(
            "(工号|员工编号|employee[_\\s]?id)[:：]?\\s*([A-Za-z0-9]{4,})",
            Pattern.CASE_INSENSITIVE);

    /** 姓名（仅显式标签后的 2-4 汉字，后接非汉字即界定边界以降低误杀；
     *  分组：g1=标签、g2=标签后冒号/空白、g3=姓名，替换时保留标签与分隔） */
    private static final Pattern NAME = Pattern.compile(
            "(?<![\\u4e00-\\u9fa5])(姓名|联系人|收货人|持卡人|投保人|被保险人|经办人)"
            + "([:：]?\\s*)([\\u4e00-\\u9fa5]{2,4})(?![\\u4e00-\\u9fa5])");

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
        t = BANK_CARD.matcher(t).replaceAll("[BANK_CARD]");
        t = EMAIL.matcher(t).replaceAll("[EMAIL]");
        t = INTERNAL_IP.matcher(t).replaceAll("[INTERNAL_IP]");
        t = ADDRESS.matcher(t).replaceAll("[ADDRESS]");
        t = EMP_ID.matcher(t).replaceAll("$1[EMP_ID]");
        t = NAME.matcher(t).replaceAll("$1$2[NAME]");
        return t;
    }

    /**
     * 判断文本是否包含 PII（供入库审核门禁 / 脱敏后残留检测使用）。
     */
    public boolean containsPii(String text) {
        if (text == null || text.isBlank()) return false;
        return PHONE.matcher(text).find()
                || ID_CARD.matcher(text).find()
                || BANK_CARD.matcher(text).find()
                || EMAIL.matcher(text).find()
                || INTERNAL_IP.matcher(text).find()
                || ADDRESS.matcher(text).find()
                || EMP_ID.matcher(text).find()
                || NAME.matcher(text).find();
    }
}
