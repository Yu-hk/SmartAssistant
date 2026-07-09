/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库种子数据工厂——为 Order / Product Agent 提供初始知识文档。
 * <p>
 * 参考 RAG 文章的最佳实践：每个文档包含标题、正文、分类、关键词、生效/过期时间。
 * 知识类 λ=0.01，关键词 BM25 加分 0.15。
 * </p>
 */
public class KnowledgeSeedData {

    /** 订单知识库名称 */
    public static final String ORDER_KB = "order_knowledge";

    /** 产品知识库名称 */
    public static final String PRODUCT_KB = "product_knowledge";

    /**
     * 创建并填充订单知识库。
     *
     * @param model     BGE 嵌入模型
     * @param tokenizer 中文分词器（用于 BM25，可为 null）
     * @param reranker  Cross-Encoder 重排序器（可为 null）
     */
    public static InMemoryKnowledgeBase createOrderKnowledgeBase(
            BgeEmbeddingModel model, ChineseTokenizer tokenizer, Reranker reranker) {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase(ORDER_KB, model, tokenizer, reranker);
        kb.addDocuments(orderDocuments());
        kb.reindex();
        return kb;
    }

    /**
     * 创建并填充产品知识库。
     *
     * @param model     BGE 嵌入模型
     * @param tokenizer 中文分词器（用于 BM25，可为 null）
     * @param reranker  Cross-Encoder 重排序器（可为 null）
     */
    public static InMemoryKnowledgeBase createProductKnowledgeBase(
            BgeEmbeddingModel model, ChineseTokenizer tokenizer, Reranker reranker) {
        InMemoryKnowledgeBase kb = new InMemoryKnowledgeBase(PRODUCT_KB, model, tokenizer, reranker);
        kb.addDocuments(productDocuments());
        kb.reindex();
        return kb;
    }

    // ==================== 订单知识 ====================

    /** 暴露订单种子文档（供图谱抽取等复用，不再局限于 KB 构建） */
    public static List<KnowledgeDocument> orderDocuments() {
        List<KnowledgeDocument> docs = new ArrayList<>();
        long now = System.currentTimeMillis();
        long year = 365L * 86400000;

        docs.add(new KnowledgeDocument("ORD-REFUND-001", "7天无理由退货政策",
                "商品签收后7天内支持无理由退货。退货条件：商品完好、不影响二次销售、附件齐全。"
                + "用户需自行承担退货运费（商品质量问题由商家承担）。"
                + "退款将在商家确认收货后3-5个工作日原路返回。",
                "退款政策", "退货,退款,7天无理由,退货运费", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-REFUND-002", "退款处理时间说明",
                "退款申请提交后，商家需在48小时内审核。审核通过后，退款金额将在3-7个工作日原路返回。"
                + "不同支付方式到账时间：微信/支付宝通常1-3个工作日，银行卡3-7个工作日。"
                + "如超出时限未到账，请联系客服查询。",
                "退款政策", "退款时间,退款到账,审核时间", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-SHIP-001", "发货时间规则",
                "商品下单后，商家需在48小时内完成发货（预售商品除外）。"
                + "发货后系统自动更新物流信息。如超时未发货，用户可申请取消订单。"
                + "节假日发货时间可能顺延，具体以商品页标注为准。",
                "发货规则", "发货时间,48小时,预售", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-SHIP-002", "物流查询说明",
                "发货后用户可通过订单详情中的'查看物流'查询实时轨迹。"
                + "物流信息更新可能有2-4小时延迟。如超过24小时无更新，请联系客服。"
                + "签收后物流状态显示'已签收'。",
                "发货规则", "物流查询,物流轨迹,签收", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-PAY-001", "支付方式说明",
                "支持的支付方式：微信支付、支付宝、银行卡。"
                + "微信支付：支持零钱、借记卡、信用卡。"
                + "支付宝：支持余额、借记卡、花呗。"
                + "银行卡：支持大部分主流银行借记卡和信用卡。"
                + "下单后30分钟内未完成支付，订单将自动取消。",
                "支付说明", "微信支付,支付宝,银行卡,支付方式", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-STATUS-001", "订单状态流转说明",
                "订单状态流转：待付款→待发货→已发货→已签收→已完成。"
                + "待付款：用户下单未支付，可取消。"
                + "待发货：已支付等待商家发货，可取消退款。"
                + "已发货：商家已发货等待用户确认，不能取消。"
                + "已签收：用户确认收货或系统自动确认，可申请售后。"
                + "退款中：退款申请已提交，处理完成后自动变更状态。",
                "订单状态", "待付款,待发货,已发货,已签收,退款中", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-CANCEL-001", "取消订单规则",
                "待付款和待发货状态的订单可以直接取消。已发货的订单不能取消，如需退款请使用退款申请。"
                + "取消后已支付金额将原路退回。如使用了优惠券，按规则退回或作废。",
                "取消订单", "取消订单,取消规则", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-COUPON-001", "优惠券使用规则",
                "优惠券分为满减券、折扣券和现金券。每笔订单只能使用一张优惠券。"
                + "优惠券不可叠加使用，不可拆分使用。"
                + "订单取消后，已使用的优惠券按规则退回：未过期的退回，已过期的作废。"
                + "退款时优惠券金额不退现。",
                "优惠券", "满减券,折扣券,现金券,优惠券规则", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-CONTACT-001", "客服联系渠道",
                "工作时间：周一至周日 9:00-21:00。"
                + "在线客服：通过APP/网页右下角在线咨询。"
                + "电话客服：400-xxx-xxxx。"
                + "紧急问题优先通过在线客服处理，响应时间通常在5分钟内。",
                "客服", "客服电话,在线客服,工作时间", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("ORD-REFUND-003", "退款原因分类说明",
                "退款原因分类：1)商品质量问题（如破损、功能故障）2)发错商品 3)不想要了/七天无理由"
                + "4)商品与描述不符 5)其他。"
                + "质量问题和发错商品由商家承担退货运费。七天无理由由用户承担退货运费。"
                + "申请退款时请选择最符合实际情况的原因，有助于快速审核。",
                "退款政策", "退款原因,质量问题,七天无理由,退货运费", now - 30 * 86400000, now + year));

        return docs;
    }

    // ==================== 产品知识 ====================

    /** 暴露产品种子文档（供图谱抽取等复用，不再局限于 KB 构建） */
    public static List<KnowledgeDocument> productDocuments() {
        List<KnowledgeDocument> docs = new ArrayList<>();
        long now = System.currentTimeMillis();
        long year = 365L * 86400000;

        docs.add(new KnowledgeDocument("PROD-CAT-001", "商品分类说明",
                "平台商品分为以下大类：手机数码（手机、平板、耳机、智能穿戴）、"
                + "电脑办公（笔记本电脑、台式机、显示器、打印机）、"
                + "家用电器（冰箱、洗衣机、空调、厨卫）、"
                + "生活百货（家居、个护、图书）。"
                + "每个大类下细分小类，用户可根据分类快速找到需要的商品。",
                "商品分类", "手机数码,电脑办公,家用电器,生活百货", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("PROD-QUERY-001", "商品查询指南",
                "用户查询商品信息时，可以提供以下维度的信息：商品名称、品牌、型号、规格参数、"
                + "价格区间、库存状态、用户评价。"
                + "使用 queryProductInfo 工具查询商品详情（含规格、参数、评价）。"
                + "使用 getPrice 工具查询实时价格。"
                + "使用 checkStock 工具查询库存。",
                "商品查询", "商品信息,查询指南,商品参数", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("PROD-PRICE-001", "价格政策说明",
                "商品价格由商家设置，平台不干预定价。价格可能随时调整，以下单时价格为准。"
                + "促销活动期间价格会有优惠，具体以活动规则为准。"
                + "价格保护：部分商品支持7天价保，购买后7天内降价可申请退差价。"
                + "使用 getPrice 工具可查询商品的实时价格和促销信息。",
                "价格政策", "价格,促销,价保,退差价", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("PROD-STOCK-001", "库存状态说明",
                "库存状态分为：有货（正常下单）、库存紧张（少于10件）、缺货（暂时无货）、采购中（补货中）。"
                + "缺货商品可设置到货提醒，到货后系统会通知用户。"
                + "使用 checkStock 工具查询商品的实时库存状态。"
                + "库存信息实时更新，以查询时的状态为准。",
                "库存", "库存,缺货,到货提醒,有货", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("PROD-EVAL-001", "用户评价说明",
                "用户评价包含评分（1-5星）、文字评价和晒图。"
                + "评价默认按时间倒序排列。可筛选维度：好评、中评、差评、有图。"
                + "带图评价对用户更有参考价值。"
                + "商家回复的评价说明商家关注用户反馈。"
                + "虚假评价一经发现将予以下架处理。",
                "评价", "用户评价,评分,晒图,好评差评", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("PROD-COMPARE-001", "商品对比建议",
                "帮用户对比商品时，建议从以下维度分析：价格、核心功能、规格参数、"
                + "用户评分、售后服务、品牌口碑。"
                + "对比同一品类下的商品时，优先考虑用户的预算和使用场景。"
                + "例如办公用途推荐性能稳定的商务机型，家庭使用推荐性价比高的型号。",
                "商品对比", "商品对比,选购建议,性价比", now - 30 * 86400000, now + year));

        docs.add(new KnowledgeDocument("PROD-AFTERSALE-001", "售后服务说明",
                "平台商品享受国家三包政策。主要品类保修期：手机1年、电脑2年、家电按品类1-3年不等。"
                + "保修期内非人为损坏免费维修。人为损坏需付费维修。"
                + "售后服务通过在线客服申请，提交后48小时内响应。"
                + "维修周期通常为7-15个工作日。",
                "售后服务", "保修,三包,维修,售后", now - 30 * 86400000, now + year));

        return docs;
    }
}
