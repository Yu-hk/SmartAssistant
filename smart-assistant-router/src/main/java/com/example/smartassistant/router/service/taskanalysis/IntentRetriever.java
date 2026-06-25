/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 意图向量检索器。
 *
 * <p>将预定义的意图定义向量化（BGE ONNX），在 {@link TaskAnalysisService} 构建 prompt 时
 * 检索与用户问题最相关的 Top-K 意图定义，替代将所有意图定义硬编码到 system prompt 中的做法。</p>
 *
 * <p>检索策略：BGE 向量余弦相似度（主） + 关键词命中加权（辅）。
 * 意图定义可在不修改代码的情况下扩展（通过增加 {code IntentDef} 列表条目）。</p>
 *
 * <p>降级：BGE 服务不可用时，回退到纯关键词匹配。</p>
 *
 * @see IntentDef
 * @see BgeOnnxEmbeddingService
 */
@Component
public class IntentRetriever {

    private static final Logger log = LoggerFactory.getLogger(IntentRetriever.class);

    /** 默认返回的 Top-K 意图数 */
    private static final int DEFAULT_TOP_K = 3;

    /** 关键词权重（BGE 不可用时的降级因子） */
    private static final double KEYWORD_WEIGHT = 0.3;

    /** 所有预定义的意图定义 */
    private static final List<IntentDef> ALL_INTENTS = List.of(
            new IntentDef("ORDER", "订单/物流/退款",
                    "用户查询订单状态、物流信息、退款进度、退货处理、快递签收等与订单售后相关的问题",
                    List.of("订单", "物流", "退款", "退货", "快递", "签收", "售后", "发票"),
                    "示例: '我的订单到哪了', '帮我查退款进度', '怎么申请退货'",
                    "相关工具: query_order, pay_order, cancel_order, queryUserCoupons"),

            new IntentDef("PRODUCT", "商品查询/库存/价格",
                    "用户询问商品详情、库存状态、价格信息、商品推荐等与商品相关的问题",
                    List.of("商品", "库存", "价格", "详情", "推荐", "产品", "规格"),
                    "示例: '这个手机多少钱', 'XX商品有货吗', '推荐一款耳机'",
                    "相关工具: query_product, check_stock, queryPrice"),

            new IntentDef("GENERAL", "问答/计算/天气/新闻",
                    "用户进行通用问答、数学计算、单位转换、汇率查询、天气查询、获取新闻热点等",
                    List.of("天气", "新闻", "计算", "换算", "汇率", "热点", "问答", "百科"),
                    "示例: '今天天气怎么样', '100美元等于多少人民币', '最新新闻'",
                    "相关工具: getHotNews, calculate, convertCurrency, convertTemperature, searchWeb, queryWeather"),

            new IntentDef("COMPLEX", "跨领域多任务",
                    "用户请求包含多个不同领域的子任务，需要多个专业 Agent 协作完成",
                    List.of("同时", "并且", "然后", "帮我", "安排", "计划", "综合"),
                    "示例: '推荐北京景点和川菜馆', '帮我查订单并看看有什么促销'",
                    "相关工具: 跨域路由 -> 任务分解 -> 并行/串行执行"),

            new IntentDef("UNKNOWN", "拒识/越界",
                    "用户请求与电商客服业务范围完全无关（政治、股票、色情等），或请求越界（买昨天票、绕过实名）",
                    List.of("政治", "股票", "违法", "越界", "绕过"),
                    "示例: '帮我预测股票', '怎么绕过实名认证'",
                    "相关工具: 无（直接拒识，返回 action_constraints）")
    );

    /** BGE 嵌入服务（可为 null，不可用时降级） */
    private final BgeOnnxEmbeddingService embeddingService;

    /** 预计算的 BGE 向量（intent ordinal -> float[]，仅 BGE 可用时初始化） */
    private Map<Integer, float[]> intentEmbeddings;

    public IntentRetriever(BgeOnnxEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() {
        if (embeddingService != null) {
            try {
                intentEmbeddings = new HashMap<>();
                for (int i = 0; i < ALL_INTENTS.size(); i++) {
                    String embedText = toEmbedText(ALL_INTENTS.get(i));
                    intentEmbeddings.put(i, embeddingService.embed(embedText));
                }
                log.info("[IntentRetriever] 意图 BGE 向量就绪: {} 个", ALL_INTENTS.size());
            } catch (Exception e) {
                log.warn("[IntentRetriever] 意图 BGE 向量初始化失败，降级为关键词匹配: {}", e.getMessage());
                intentEmbeddings = null;
            }
        } else {
            log.info("[IntentRetriever] BGE 服务不可用，降级为关键词匹配");
            intentEmbeddings = null;
        }
    }

    /**
     * 检索与用户问题最相关的 Top-K 意图定义。
     *
     * @param question 用户原始问题
     * @param topK     返回的最大意图数
     * @return 按相关性降序排列的意图列表；无匹配时返回空列表
     */
    public List<IntentDef> retrieve(String question, int topK) {
        if (question == null || question.isBlank()) return List.of();

        int k = Math.max(1, Math.min(topK, ALL_INTENTS.size()));

        if (intentEmbeddings != null) {
            return retrieveByVector(question, k);
        } else {
            return retrieveByKeyword(question, k);
        }
    }

    /** BGE 向量检索：嵌入问题 → 余弦相似度排序 → Top-K */
    private List<IntentDef> retrieveByVector(String question, int topK) {
        try {
            float[] queryVec = embeddingService.embed(question);

            // 计算得分：cosine + 关键词命中加权
            List<ScoredIntent> scored = new ArrayList<>();
            for (int i = 0; i < ALL_INTENTS.size(); i++) {
                float[] intentVec = intentEmbeddings.get(i);
                double cosineSim = cosineSimilarity(queryVec, intentVec);
                double keywordScore = keywordHitRate(question, ALL_INTENTS.get(i).keywords());
                double total = cosineSim + KEYWORD_WEIGHT * keywordScore;
                scored.add(new ScoredIntent(ALL_INTENTS.get(i), total));
            }

            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
            return scored.stream()
                    .limit(topK)
                    .map(ScoredIntent::intent)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("[IntentRetriever] 向量检索失败，降级关键词: {}", e.getMessage());
            return retrieveByKeyword(question, topK);
        }
    }

    /** 纯关键词降级检索 */
    private List<IntentDef> retrieveByKeyword(String question, int topK) {
        String q = question.toLowerCase();

        List<ScoredIntent> scored = new ArrayList<>();
        for (IntentDef intent : ALL_INTENTS) {
            double score = 0;
            for (String kw : intent.keywords()) {
                if (q.contains(kw.toLowerCase())) {
                    score += 1.0;
                }
            }
            if (score > 0) {
                scored.add(new ScoredIntent(intent, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream()
                .limit(topK)
                .map(ScoredIntent::intent)
                .collect(Collectors.toList());
    }

    /**
     * 将检索到的意图定义格式化为 prompt 片段。
     *
     * @param intents 检索到的意图（通常来自 {@link #retrieve(String, int)}）
     * @return 格式化的 Markdown 文本；输入为空时返回 null
     */
    public String buildIntentSection(List<IntentDef> intents) {
        if (intents == null || intents.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用意图说明（以下为与当前问题最相关的意图）\n");
        for (IntentDef intent : intents) {
            sb.append("- **").append(intent.id()).append("**（").append(intent.name()).append("）：");
            sb.append(intent.description()).append("\n");
            sb.append("  ").append(intent.examples()).append("\n");
            sb.append("  ").append(intent.relevantTools()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部工具 ====================

    /** 生成用于嵌入的文本 */
    private static String toEmbedText(IntentDef intent) {
        return intent.id() + "：" + intent.name() + "。" + intent.description()
                + "关键词：" + String.join("、", intent.keywords());
    }

    /** 关键词命中率 */
    private static double keywordHitRate(String question, List<String> keywords) {
        String q = question.toLowerCase();
        long hits = keywords.stream().filter(kw -> q.contains(kw.toLowerCase())).count();
        return keywords.isEmpty() ? 0 : (double) hits / keywords.size();
    }

    /** 余弦相似度（384 维归一化向量） */
    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double norm = Math.sqrt(na) * Math.sqrt(nb);
        return norm == 0 ? 0 : dot / norm;
    }

    /** 带分值的意图包装 */
    private record ScoredIntent(IntentDef intent, double score) {}
}
