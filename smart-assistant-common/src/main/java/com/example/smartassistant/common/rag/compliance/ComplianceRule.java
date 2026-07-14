/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

/**
 * 单条合规规则（REQ-3 规则集的可配置单元）。
 * <p>
 * 由 {@code rag/compliance-rules.json} 反序列化得到，也可用代码构造。
 * 命中判定基于 {@link #pattern}（正则，大小写不敏感），分级由 {@link #severity} 决定，
 * 处置由 {@link #strategy} 决定（{@code warn / rewrite / block}），
 * {@link #rewrite} 为命中时的改写建议文本。
 * </p>
 */
public class ComplianceRule {

    /** 规则 ID（如 C001，便于审计溯源） */
    private String id = "";

    /** 正则模式（大小写不敏感） */
    private String pattern = "";

    /** 严重度：HIGH / MEDIUM / LOW */
    private String severity = "MEDIUM";

    /** 处置策略：warn / rewrite / block；缺省 rewrite（与全局默认一致） */
    private String strategy = "rewrite";

    /** 改写建议文本（strategy=rewrite 时生效；为空表示不改写） */
    private String rewrite = "";

    /** 规则类别（超承诺 / 模糊政策 / 绝对化 / 自伤 / 隐私 等） */
    private String category = "";

    public ComplianceRule() {
    }

    public ComplianceRule(String id, String pattern, String severity, String strategy,
                          String rewrite, String category) {
        this.id = id;
        this.pattern = pattern;
        this.severity = severity;
        this.strategy = strategy;
        this.rewrite = rewrite;
        this.category = category;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getRewrite() { return rewrite; }
    public void setRewrite(String rewrite) { this.rewrite = rewrite; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    /** 严重度排序权重（HIGH=3 / MEDIUM=2 / LOW=1） */
    public int severityRank() {
        if ("HIGH".equalsIgnoreCase(severity)) return 3;
        if ("LOW".equalsIgnoreCase(severity)) return 1;
        return 2;
    }
}
