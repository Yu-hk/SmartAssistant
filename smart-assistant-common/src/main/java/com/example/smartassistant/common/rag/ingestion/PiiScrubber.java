/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.security.PiiPolicyEngine;

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
    private final PiiPolicyEngine engine;

    public PiiScrubber() { this(PiiPolicyEngine.shared()); }
    public PiiScrubber(PiiPolicyEngine engine) { this.engine = engine; }

    /**
     * 脱敏文本：将识别到的 PII 替换为占位标记。
     *
     * @param text 原始文本（可为 null）
     * @return 脱敏后文本；null/空原样返回
     */
    public String scrub(String text) {
        return engine.sanitize(text);
    }

    /**
     * 判断文本是否包含 PII（供入库审核门禁使用）。
     */
    public boolean containsPii(String text) {
        return engine.containsPii(text);
    }
}
