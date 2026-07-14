/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import java.util.ArrayList;
import java.util.List;

/**
 * 合规黄金测试集单用例（REQ-8）——供 {@link ComplianceGoldenSuiteEvaluator} 做回归判定。
 *
 * <p>字段约定（与 {@code eval-test-suite.json} 的 {@code complianceTests} 段对齐）：</p>
 * <ul>
 *   <li>{@code id} 用例标识；</li>
 *   <li>{@code text} 被评测原文；</li>
 *   <li>{@code expectViolation} {@code true}=应命中规则，{@code false}=良性反例（不应命中）；</li>
 *   <li>{@code expectedRuleIds} 期望命中的规则 id 列表（可空；仅当 expectViolation=true 时校验）。</li>
 * </ul>
 */
public class ComplianceTestCase {

    /** 用例标识（如 CMP-001） */
    public String id = "";

    /** 被评测原文 */
    public String text = "";

    /** true=应命中规则；false=良性反例，不应命中 */
    public boolean expectViolation = false;

    /** 期望命中的规则 id 列表（仅 expectViolation=true 时校验） */
    public List<String> expectedRuleIds = new ArrayList<>();

    /** Jackson 反序列化用 */
    public ComplianceTestCase() {
    }

    public ComplianceTestCase(String id, String text, boolean expectViolation, List<String> expectedRuleIds) {
        this.id = id;
        this.text = text;
        this.expectViolation = expectViolation;
        this.expectedRuleIds = expectedRuleIds != null ? expectedRuleIds : new ArrayList<>();
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public boolean isExpectViolation() { return expectViolation; }
    public List<String> getExpectedRuleIds() {
        return expectedRuleIds != null ? expectedRuleIds : new ArrayList<>();
    }
}
