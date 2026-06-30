/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.retrieval;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 查询分类器——根据用户查询文本动态选择检索强度配置。
 * <p>
 * 使用关键词规则分类，无需 LLM 调用：
 * <ul>
 *   <li><b>深度检索 (DEEP)</b>：涉及退款/纠纷/合同/法律/投诉等高价值场景</li>
 *   <li><b>轻量检索 (LIGHT)</b>：简单询问/闲聊/天气/时间等低风险高频场景</li>
 *   <li><b>标准检索 (STANDARD)</b>：大多数业务查询</li>
 * </ul>
 * </p>
 */
public class QueryClassifier {

    /** 深度检索触发关键词（高价值/高风险） */
    private static final Set<String> DEEP_KEYWORDS = Set.of(
            "退款", "退货", "投诉", "纠纷", "赔偿", "维权",
            "合同", "协议", "条款", "法律", "诉讼",
            "保价", "价保", "理赔", "保险",
            "发票", "报销",
            "售后", "维修", "保修",
            "客服", "人工",
            "投诉", "举报", "申诉",
            "违规", "欺诈", "争议"
    );

    /** 深度检索触发模式（更长的短语匹配） */
    private static final Pattern DEEP_PATTERN = Pattern.compile(
            "(退款|退货|投诉|合同|法律|保价|理赔|发票|售后|保修|欺诈|争议)"
            + ".*(规则|政策|流程|时间|多久|如何|怎么办)"
            + "|"
            + "(如何|怎么).*(退款|投诉|理赔|保修)"
    );

    /** 轻量检索触发关键词（低风险高频） */
    private static final Set<String> LIGHT_KEYWORDS = Set.of(
            "你好", "您好", "嗨", "早上好", "下午好", "晚上好",
            "天气", "时间", "日期",
            "温度", "长度", "重量", "转换", "换算",
            "计算", "等于",
            "新闻", "热点",
            "测试", "ping", "hello"
    );

    /** 轻量检索触发模式 */
    private static final Pattern LIGHT_PATTERN = Pattern.compile(
            "^(你好|您好|嗨|早上好|下午好|晚上好)[，,!！。]?"
            + "|"
            + "\\d+\\s*[+\\-*/]\\s*\\d+"
            + "|"
            + "\\d+(度|摄氏度|华氏度|厘米|米|公里|斤|公斤|磅|美元|人民币)"
    );

    /**
     * 根据查询文本分类检索配置。
     *
     * @param query 用户查询文本
     * @return 推荐的检索强度
     */
    public static RetrievalProfile classify(String query) {
        if (query == null || query.isBlank()) return RetrievalProfile.STANDARD;
        String q = query.trim().toLowerCase();

        // 深度检索检测
        if (matchesDeep(q)) return RetrievalProfile.DEEP;

        // 轻量检索检测
        if (matchesLight(q)) return RetrievalProfile.LIGHT;

        // 默认标准
        return RetrievalProfile.STANDARD;
    }

    private static boolean matchesDeep(String query) {
        // 关键词匹配
        for (String kw : DEEP_KEYWORDS) {
            if (query.contains(kw)) return true;
        }
        // 模式匹配
        return DEEP_PATTERN.matcher(query).find();
    }

    private static boolean matchesLight(String query) {
        // 关键词匹配
        for (String kw : LIGHT_KEYWORDS) {
            if (query.contains(kw)) return true;
        }
        // 模式匹配
        return LIGHT_PATTERN.matcher(query).find();
    }
}
