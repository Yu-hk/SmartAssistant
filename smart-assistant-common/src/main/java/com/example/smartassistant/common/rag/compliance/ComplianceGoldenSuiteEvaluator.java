/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合规规则黄金回归评测器（REQ-8）——把 REQ-3 的 30 条合规规则接进 GoldenSuite 门禁。
 *
 * <p>纯规则匹配（{@link ComplianceGrader} 默认 {@code llmEnabled=false}），不依赖 LLM / PG / Redis。
 * 对每个 {@link ComplianceTestCase} 调 {@link ComplianceGrader#grade(String)}，判定：</p>
 * <ul>
 *   <li>expectViolation=true：必须 {@link ComplianceResult#isHit()} 且
 *       {@link ComplianceResult#matchedRuleIds()} 包含全部 expectedRuleIds；
 *       且若命中规则 strategy=rewrite 且有 rewrite，断言 {@link ComplianceResult#getRewritten()}
 *       不再包含原 forbidden 片段（即<b>改写生效</b>校验）。</li>
 *   <li>expectViolation=false：必须 {@code !isHit()}（良性反例不得误杀）。</li>
 * </ul>
 *
 * <p>任一条规则正则失效 / 改写不生效 → 对应用例 {@code passed=false} → 门禁拦截。</p>
 */
public class ComplianceGoldenSuiteEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ComplianceGoldenSuiteEvaluator.class);

    private final ComplianceRuleSet ruleSet;
    private final ComplianceGrader grader;

    /** 默认构造：从 classpath 加载标准规则集（{@code rag/compliance-rules.json}）。 */
    public ComplianceGoldenSuiteEvaluator() {
        this(ComplianceRuleSet.fromClasspath());
    }

    /** 注入指定规则集（便于单测构造「正则失效」场景）。 */
    public ComplianceGoldenSuiteEvaluator(ComplianceRuleSet ruleSet) {
        this.ruleSet = ruleSet != null ? ruleSet : ComplianceRuleSet.fromClasspath();
        this.grader = new ComplianceGrader(this.ruleSet);
    }

    /**
     * 评测全部用例。
     *
     * @param cases 合规测试用例（来自黄金测试集 complianceTests 段，可为空）
     * @return 每条用例的 {@link ComplianceEvaluationResult}（顺序与入参一致）
     */
    public List<ComplianceEvaluationResult> evaluate(List<ComplianceTestCase> cases) {
        List<ComplianceEvaluationResult> results = new ArrayList<>();
        if (cases == null || cases.isEmpty()) {
            log.info("[ComplianceGoldenSuite] 无合规用例，跳过评测");
            return results;
        }
        for (ComplianceTestCase c : cases) {
            results.add(evaluateOne(c));
        }
        long failed = results.stream().filter(r -> !r.isPassed()).count();
        log.info("[ComplianceGoldenSuite] 合规评测完成：共 {} 条，通过 {} 条，失败 {} 条",
                results.size(), results.size() - failed, failed);
        return results;
    }

    private ComplianceEvaluationResult evaluateOne(ComplianceTestCase c) {
        if (c == null || c.getText() == null || c.getText().isBlank()) {
            return ComplianceEvaluationResult.of(
                    c != null ? c.getId() : "?",
                    false,
                    List.of(),
                    c != null ? c.getExpectedRuleIds() : List.of(),
                    null,
                    "用例为空或文本为空");
        }

        ComplianceResult gr = grader.grade(c.getText());
        List<String> matchedIds = gr.matchedRuleIds();
        List<String> expectedIds = c.getExpectedRuleIds();

        boolean passed;
        String message;

        if (c.isExpectViolation()) {
            if (!gr.isHit()) {
                passed = false;
                message = "期望命中规则但文本未命中任何合规规则: id=" + c.getId();
            } else {
                List<String> missing = expectedIds.stream()
                        .filter(id -> !matchedIds.contains(id))
                        .toList();
                if (!missing.isEmpty()) {
                    passed = false;
                    message = "命中规则 " + matchedIds + " 但缺少期望规则 " + missing;
                } else if (!verifyRewriteApplied(gr)) {
                    passed = false;
                    message = "改写未生效：rewritten 仍含原 forbidden 片段（" + gr.getRewritten() + "）";
                } else {
                    passed = true;
                    message = "命中期望规则 " + matchedIds + "，改写生效";
                }
            }
        } else {
            // 良性反例：必须不命中（无误杀）
            if (gr.isHit()) {
                passed = false;
                message = "期望不命中但误命中规则 " + matchedIds + "（误杀）：" + c.getText();
            } else {
                passed = true;
                message = "良性文本未命中，通过";
            }
        }

        return ComplianceEvaluationResult.of(c.getId(), passed, matchedIds, expectedIds,
                gr.getRewritten(), message);
    }

    /**
     * 对命中且 strategy=rewrite 的规则，断言改写后文本不再包含原 forbidden 片段。
     * <p>即校验「改写生效」：grader 应已将 forbidden 片段替换为 rewrite 建议。</p>
     */
    private boolean verifyRewriteApplied(ComplianceResult gr) {
        String rewritten = gr.getRewritten();
        if (rewritten == null) {
            // 期望命中但无改写输出（且存在需改写规则）时视为改写未生效
            return gr.getMatched().stream()
                    .noneMatch(r -> "rewrite".equalsIgnoreCase(r.getStrategy())
                            && r.getRewrite() != null && !r.getRewrite().isBlank());
        }
        for (ComplianceRule rule : gr.getMatched()) {
            if (!"rewrite".equalsIgnoreCase(rule.getStrategy())) continue;
            if (rule.getRewrite() == null || rule.getRewrite().isBlank()) continue;
            String forbidden = findMatchedSubstring(rule, gr.getOriginal());
            if (forbidden != null && !forbidden.isBlank() && rewritten.contains(forbidden)) {
                log.warn("[ComplianceGoldenSuite] 规则 {} 改写未生效，仍含 forbidden 片段『{}』",
                        rule.getId(), forbidden);
                return false;
            }
        }
        return true;
    }

    /** 在原文本中定位规则命中的字面子串（正则大小写不敏感），用于改写生效校验。 */
    private String findMatchedSubstring(ComplianceRule rule, String text) {
        if (text == null) return null;
        try {
            Pattern p = Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group();
            }
        } catch (Exception e) {
            log.debug("[ComplianceGoldenSuite] 规则 {} 正则定位失败，退化为字面量: {}",
                    rule.getId(), e.getMessage());
        }
        return rule.getPattern();
    }

    /**
     * 从黄金测试集 classpath 资源解析 {@code complianceTests} 段。
     *
     * @param classpathResource 黄金测试集资源（如 {@code /eval-test-suite.json}）
     * @return complianceTests 用例列表（缺段或解析失败返回空列表）
     */
    public static List<ComplianceTestCase> loadCases(String classpathResource) {
        try (InputStream is = ComplianceGoldenSuiteEvaluator.class.getResourceAsStream(classpathResource)) {
            if (is == null) {
                log.warn("[ComplianceGoldenSuite] 资源未找到: {}", classpathResource);
                return List.of();
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> suite = mapper.readValue(is, new TypeReference<>() {});
            Object raw = suite.get("complianceTests");
            if (raw instanceof List) {
                List<ComplianceTestCase> list = mapper.convertValue(raw, new TypeReference<>() {});
                log.info("[ComplianceGoldenSuite] 加载 complianceTests {} 条（资源={}）",
                        list.size(), classpathResource);
                return list;
            }
            log.info("[ComplianceGoldenSuite] 资源 {} 无 complianceTests 段", classpathResource);
            return List.of();
        } catch (Exception e) {
            log.warn("[ComplianceGoldenSuite] 加载 complianceTests 失败: {}", e.getMessage());
            return List.of();
        }
    }
}
