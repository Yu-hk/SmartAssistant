/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 幻觉检测器 — 检查答案中的关键断言是否被检索到的上下文支持。
 * <p>
 * 对应面试题 Q09 "如何减少和规避幻觉问题" 中"校验（answer verification）"环节。
 * 采用多层检测策略：
 * <ol>
 *   <li><b>数字断言检测</b>：提取答案中的数字断言，检查是否在上下文中出现</li>
 *   <li><b>命名实体检测</b>：提取专有名词，检查上下文是否包含</li>
 *   <li><b>否定反转检测</b>：检测答案中否定词后的断言是否有上下文证据</li>
 *   <li><b>日期/时间一致性</b>：答案中的时间引用是否与上下文匹配</li>
 * </ol>
 * <p>
 * 注意：此检测器基于规则，只能检测 <b>明显</b> 幻觉（上下文明确不支持的断言）。
 * 对于"语义一致但上下文缺失"的微妙幻觉，建议使用 LLM-as-Judge 方案。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
public class HallucinationDetector {

    private static final Logger log = LoggerFactory.getLogger(HallucinationDetector.class);

    /** 数字模式：匹配独立数字（整数、小数、百分比） */
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "(?<![\\d.])(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?%?)(?![\\d.])");

    /** 日期模式：匹配常见中文日期格式 */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}年\\d{1,2}月|\\d{1,2}月\\d{1,2}日|\\d{4}年)");

    /** 否定词集合 */
    private static final Set<String> NEGATORS = Set.of("没有", "不是", "不会", "不能", "无法", "不存在", "无", "否", "禁止");

    /** 中文专有名词模式：带引号或书名号的内容 */
    private static final Pattern QUOTED_ENTITY = Pattern.compile(
            "[「『《【「『《【]([^」』》】」』》】]{2,20})[」』》】」』》】]");

    /**
     * 检测答案中的幻觉断言。
     *
     * @param answer    模型生成的答案
     * @param context   检索到的上下文文本（拼接后的完整上下文）
     * @return 幻觉检测结果
     */
    public HallucinationResult detect(String answer, String context) {
        if (answer == null || answer.isBlank()) {
            return new HallucinationResult(false, 0.0, List.of(), "答案为空，无需检测");
        }
        if (context == null || context.isBlank()) {
            return new HallucinationResult(true, 1.0,
                    List.of(new HallucinationClaim("整体", "答案无上下文支撑", "", 1.0)),
                    "上下文为空，答案存在高危幻觉风险");
        }

        List<HallucinationClaim> claims = new ArrayList<>();

        // 1. 数字断言检测
        claims.addAll(detectNumberHallucinations(answer, context));

        // 2. 否定反转检测
        claims.addAll(detectNegationHallucinations(answer, context));

        // 3. 日期一致性检测
        claims.addAll(detectDateHallucinations(answer, context));

        // 4. 命名实体检测（带引号的内容）
        claims.addAll(detectEntityHallucinations(answer, context));

        // 计算幻觉率
        double hallucinationRate = calculateHallucinationRate(claims);
        
        boolean hasHallucination = !claims.isEmpty();
        String detail = hasHallucination
                ? "发现 " + claims.size() + " 个潜在幻觉断言"
                : "未发现明显幻觉，通过检测";

        return new HallucinationResult(hasHallucination, hallucinationRate, claims, detail);
    }

    /**
     * 检测数字断言幻觉。
     * 答案中出现的数字，如果在上下文中找不到对应的数值上下文，
     * 标记为潜在幻觉。
     */
    private List<HallucinationClaim> detectNumberHallucinations(String answer, String context) {
        List<HallucinationClaim> claims = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(answer);
        Set<String> seen = new HashSet<>();

        while (matcher.find()) {
            String number = matcher.group(1);
            if (seen.contains(number)) continue;
            seen.add(number);

            // 获取数字前后的上下文（各 15 个字符）
            int start = Math.max(0, matcher.start() - 15);
            int end = Math.min(answer.length(), matcher.end() + 15);
            String contextAround = answer.substring(start, end).replace('\n', ' ').trim();

            // 检查数字是否在检索上下文中出现
            if (!context.contains(number)) {
                claims.add(new HallucinationClaim(
                        "数字断言",
                        "答案中的数字 '" + number + "' 未在检索上下文出现",
                        contextAround,
                        0.7
                ));
            }
        }
        return claims;
    }

    /**
     * 检测否定反转幻觉。
     * 当答案使用了否定断言（"没有X"）时，检查上下文是否有明确的反向证据。
     */
    private List<HallucinationClaim> detectNegationHallucinations(String answer, String context) {
        List<HallucinationClaim> claims = new ArrayList<>();

        for (String negator : NEGATORS) {
            int idx = answer.indexOf(negator);
            while (idx >= 0) {
                // 提取否定断言后的 20 个字符
                int end = Math.min(answer.length(), idx + negator.length() + 20);
                String negatedPhrase = answer.substring(idx, end).replace('\n', ' ').trim();

                // 提取该断言的核心内容（去掉否定词本身）
                String coreClaim = negatedPhrase.substring(negator.length()).trim();

                // 检查上下文是否明确包含该断言的正向内容
                if (!coreClaim.isEmpty() && context.contains(coreClaim)) {
                    claims.add(new HallucinationClaim(
                            "否定反转",
                            "答案说'" + negatedPhrase + "'，但上下文中包含同主题正向内容",
                            negatedPhrase,
                            0.5
                    ));
                }
                idx = answer.indexOf(negator, idx + 1);
            }
        }
        return claims;
    }

    /**
     * 检测日期一致性。
     * 答案中的日期若与上下文日期不一致，标记为潜在幻觉。
     */
    private List<HallucinationClaim> detectDateHallucinations(String answer, String context) {
        List<HallucinationClaim> claims = new ArrayList<>();

        Matcher answerDates = DATE_PATTERN.matcher(answer);
        Matcher contextDates = DATE_PATTERN.matcher(context);

        Set<String> answerDateSet = new HashSet<>();
        while (answerDates.find()) {
            answerDateSet.add(answerDates.group(1));
        }

        Set<String> contextDateSet = new HashSet<>();
        while (contextDates.find()) {
            contextDateSet.add(contextDates.group(1));
        }

        // 如果答案中有日期但上下文中完全没有日期，不标记（日期可能由模型基于上下文推理得出）
        // 但如果答案日期是上下文中日期的未来/过去年份不同，标记
        for (String ad : answerDateSet) {
            if (contextDateSet.isEmpty()) continue;
            // 精确长度匹配检查：答案中的年份是否在上下文中出现
            String yearPart = ad.length() >= 4 ? ad.substring(0, Math.min(4, ad.length())) : ad;
            boolean yearFound = contextDateSet.stream().anyMatch(cd -> cd.contains(yearPart));
            if (!yearFound && yearPart.matches("\\d{4}")) {
                claims.add(new HallucinationClaim(
                        "日期不一致",
                        "答案中的日期 '" + ad + "' 年份与上下文不匹配",
                        ad,
                        0.4
                ));
            }
        }
        return claims;
    }

    /**
     * 检测命名实体幻觉。
     * 检查答案中引用的专有名词（如产品名、人名、地名）是否在上下文中有对应。
     */
    private List<HallucinationClaim> detectEntityHallucinations(String answer, String context) {
        List<HallucinationClaim> claims = new ArrayList<>();

        Matcher matcher = QUOTED_ENTITY.matcher(answer);
        Set<String> seen = new HashSet<>();

        while (matcher.find()) {
            String entity = matcher.group(1);
            if (seen.contains(entity)) continue;
            seen.add(entity);

            // 实体较短（< 4 字）可能是通用词，跳过
            if (entity.length() < 4) continue;

            // 检查实体是否在上下文中出现
            if (!context.contains(entity)) {
                claims.add(new HallucinationClaim(
                        "实体不存在",
                        "答案引用的 '" + entity + "' 未在检索上下文中出现",
                        "「" + entity + "」",
                        0.6
                ));
            }
        }
        return claims;
    }

    /**
     * 计算幻觉率。
     * <p>
     * 采用加权幻觉得分 / 最大潜在得分的方式归一化。
     * 每条幻觉有置信度权重（0.0~1.0），求和后除以断言总数加 1。
     * </p>
     *
     * @param claims 检测到的幻觉断言列表
     * @return 幻觉率（0.0 ~ 1.0，0=无幻觉，1=完全幻觉）
     */
    private double calculateHallucinationRate(List<HallucinationClaim> claims) {
        if (claims.isEmpty()) return 0.0;
        double totalWeight = claims.stream()
                .mapToDouble(HallucinationClaim::confidence)
                .sum();
        // 归一化：平均置信度
        return Math.min(1.0, totalWeight / claims.size());
    }

    // ==================== 内部类型 ====================

    /**
     * 单个幻觉断言记录。
     */
    public record HallucinationClaim(
            /** 幻觉类型（数字断言/否定反转/日期不一致/实体不存在） */
            String type,
            /** 详细描述 */
            String description,
            /** 答案中的原文片段 */
            String snippet,
            /** 置信度（0.0~1.0，越高越可能是真幻觉） */
            double confidence
    ) {}

    /**
     * 幻觉检测结果。
     */
    public record HallucinationResult(
            /** 是否发现潜在幻觉 */
            boolean hasHallucination,
            /** 幻觉率（0.0~1.0） */
            double hallucinationRate,
            /** 所有潜在幻觉断言列表 */
            List<HallucinationClaim> claims,
            /** 汇总描述 */
            String detail
    ) {}
}
