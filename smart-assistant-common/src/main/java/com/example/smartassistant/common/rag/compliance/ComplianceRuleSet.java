/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 合规规则集（REQ-3）——承载 ≥20 条「超承诺 / 模糊政策 / 绝对化」等规则，JSON 可配。
 * <p>
 * 从 classpath 资源 {@code rag/compliance-rules.json} 加载（结构见架构 §8.4）。
 * 提供 {@link #match(String)} 做命中检测（正则大小写不敏感），供
 * {@link ComplianceGrader} 判定改写/拒答。
 * </p>
 */
public class ComplianceRuleSet {

    private static final Logger log = LoggerFactory.getLogger(ComplianceRuleSet.class);

    /** 默认 classpath 资源路径 */
    public static final String DEFAULT_RESOURCE = "rag/compliance-rules.json";

    private final List<ComplianceRule> rules;
    private final Map<String, Pattern> compiled = new LinkedHashMap<>();

    public ComplianceRuleSet(List<ComplianceRule> rules) {
        this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
        compileAll();
    }

    /** 从 classpath 资源加载（缺省 {@link #DEFAULT_RESOURCE}） */
    public static ComplianceRuleSet fromClasspath() {
        return fromClasspath(DEFAULT_RESOURCE);
    }

    /** 从指定 classpath 资源加载 */
    public static ComplianceRuleSet fromClasspath(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("[ComplianceRuleSet] 资源未找到: {}，使用空规则集", resource);
                return new ComplianceRuleSet(Collections.emptyList());
            }
            ObjectMapper mapper = new ObjectMapper();
            Wrapper wrapper = mapper.readValue(in, Wrapper.class);
            List<ComplianceRule> list = wrapper.rules != null ? wrapper.rules : Collections.emptyList();
            log.info("[ComplianceRuleSet] 加载规则 {} 条（资源={}）", list.size(), resource);
            return new ComplianceRuleSet(list);
        } catch (Exception e) {
            log.warn("[ComplianceRuleSet] 加载失败（使用空规则集）: {}", e.getMessage());
            return new ComplianceRuleSet(Collections.emptyList());
        }
    }

    private void compileAll() {
        for (ComplianceRule rule : rules) {
            if (rule.getPattern() == null || rule.getPattern().isBlank()) continue;
            try {
                compiled.put(rule.getId(), Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                log.warn("[ComplianceRuleSet] 规则 {} 正则编译失败，跳过: {}", rule.getId(), e.getMessage());
            }
        }
    }

    /** 命中检测：返回所有命中的规则（按规则集顺序） */
    public List<ComplianceRule> match(String text) {
        List<ComplianceRule> matched = new ArrayList<>();
        if (text == null || text.isBlank() || compiled.isEmpty()) return matched;
        for (ComplianceRule rule : rules) {
            Pattern p = compiled.get(rule.getId());
            if (p != null && p.matcher(text).find()) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /** 规则数量 */
    public int size() { return rules.size(); }

    /** 全部规则（只读） */
    public List<ComplianceRule> getRules() { return Collections.unmodifiableList(rules); }

    /** JSON 反序列化包装类 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Wrapper {
        public List<ComplianceRule> rules;
    }
}
