/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeywordFastRouteService#parseJsonRules(String)} Jackson 解析验证（P5-D 技术债清理）。
 */
class KeywordFastRouteServiceParseJsonTest {

    private KeywordFastRouteService service;

    @BeforeEach
    void setUp() {
        service = new KeywordFastRouteService(new KeywordFastRouteService.KeywordRouteProperties());
    }

    @Test
    @DisplayName("裸数组形态应解析为规则列表")
    void bareArrayParsed() {
        String json = "["
                + "{\"name\":\"refund\",\"targetAgent\":\"order\",\"intentTag\":\"退款申请\","
                + "\"anyContain\":[\"退款\",\"退货\"],\"exclude\":[\"怎么退款\"],\"confidence\":0.95,\"priority\":10},"
                + "{\"name\":\"query_order\",\"targetAgent\":\"order\",\"intentTag\":\"订单查询\","
                + "\"anyContain\":[\"查订单\"],\"confidence\":0.9,\"priority\":10}"
                + "]";
        List<KeywordFastRouteService.KeywordRule> rules = service.parseJsonRules(json);

        assertEquals(2, rules.size());
        KeywordFastRouteService.KeywordRule r0 = rules.get(0);
        assertEquals("refund", r0.getName());
        assertEquals("order", r0.getTargetAgent());
        assertEquals("退款申请", r0.getIntentTag());
        assertEquals(List.of("退款", "退货"), r0.getAnyContain());
        assertEquals(List.of("怎么退款"), r0.getExclude());
        assertEquals(0.95, r0.getConfidence(), 1e-9);
        assertEquals(10, r0.getPriority());
    }

    @Test
    @DisplayName("对象包裹 {rules:[...]} 形态应解析")
    void wrappedObjectParsed() {
        String json = "{\"rules\":["
                + "{\"name\":\"refund\",\"targetAgent\":\"order\",\"intentTag\":\"退款申请\","
                + "\"anyContain\":[\"退款\"]}"
                + "]}";
        List<KeywordFastRouteService.KeywordRule> rules = service.parseJsonRules(json);
        assertEquals(1, rules.size());
        assertEquals("refund", rules.get(0).getName());
        assertNotNull(rules.get(0).getAnyContain());
    }

    @Test
    @DisplayName("空/空白输入应返回空列表而非抛异常")
    void emptyInputReturnsEmpty() {
        assertTrue(service.parseJsonRules(null).isEmpty());
        assertTrue(service.parseJsonRules("   ").isEmpty());
    }

    @Test
    @DisplayName("非法 JSON 应降级为空列表")
    void invalidJsonReturnsEmpty() {
        assertTrue(service.parseJsonRules("{not valid json").isEmpty());
    }

    @Test
    @DisplayName("非数组形态应返回空列表")
    void nonArrayReturnsEmpty() {
        assertTrue(service.parseJsonRules("{\"foo\":\"bar\"}").isEmpty());
    }

    @Test
    @DisplayName("商品退货条件应视为售后政策咨询而不是商品+退款多意图")
    void refundPolicyWithGenericProductNounRoutesToOrder() {
        service.init();

        KeywordFastRouteService.MatchResult result =
                service.match("商品退货退款需要满足哪些条件？");

        assertNotNull(result);
        assertEquals("order", result.getTargetAgent());
        assertEquals("退款与售后政策", result.getIntentTag());
    }

    @Test
    @DisplayName("退款与具体商品推荐同时出现时仍按多意图处理")
    void refundAndSpecificProductRecommendationKeepsMultiIntent() {
        service.init();

        assertNull(service.match("帮我退款并推荐一款有货的耳机"));
    }

    @Test
    @DisplayName("包含完整商品信息的下单请求应直接路由订单模块")
    void createOrderWithProductDetailsRoutesToOrder() {
        service.init();

        KeywordFastRouteService.MatchResult result = service.match(
                "请购买笔记本电脑商品，金额3798元，收货地址北京市测试路1号");

        assertNotNull(result);
        assertEquals("order", result.getTargetAgent());
        assertEquals("创建订单", result.getIntentTag());
        assertEquals("create_order_fast_route", result.getMatchedRuleName());
    }

    @Test
    @DisplayName("查询热门商品后下单应作为跨模块多意图交给任务规划")
    void popularProductsThenPlaceOrderKeepsMultiIntent() {
        service.init();

        assertNull(service.match("帮我查下热门商品，然后下单"));
    }

    @Test
    @DisplayName("否定的创建订单关键词不得命中创建订单快车道")
    void negatedCreateOrderDoesNotRouteToOrder() {
        service.init();

        assertNull(service.match(
                "帮我查询当前热门耳机，并查询北京今天的天气；本次只查询，不购买，也不要创建订单。"));
    }

    @Test
    @DisplayName("热门商品与天气同时出现时不得被商品快车道吞掉天气意图")
    void productAndWeatherKeepsMultiIntent() {
        service.init();

        assertNull(service.match(
                "查询当前热门商品榜单，并查询上海今天的天气；本次只查询，不创建订单。"));
    }

    @Test
    @DisplayName("只查询约束应抑制购买动作但保留商品查询")
    void readOnlyConstraintKeepsProductQuery() {
        service.init();

        KeywordFastRouteService.MatchResult result =
                service.match("推荐一款耳机，本次只查询，不购买");

        assertNotNull(result);
        assertEquals("product", result.getTargetAgent());
        assertEquals("商品查询", result.getIntentTag());
    }

    @Test
    @DisplayName("否定取消动作时仍可查询已有订单")
    void negatedCancelStillRoutesExistingOrderQuery() {
        service.init();

        KeywordFastRouteService.MatchResult result =
                service.match("不要取消订单，只查询订单状态");

        assertNotNull(result);
        assertEquals("order", result.getTargetAgent());
        assertEquals("订单查询", result.getIntentTag());
    }

    @Test
    @DisplayName("没有只查询全局约束时也应识别局部否定")
    void localNegationDoesNotTriggerCreateOrder() {
        service.init();
        assertNull(service.match("不要创建订单，帮我看看北京天气"));
    }

    @Test
    @DisplayName("只读下单资料说明不得退化为单商品快车道")
    void readOnlyOrderPreparationKeepsCollaborativePath() {
        service.init();
        assertNull(service.match(
                "推荐一款价格合适的耳机并告诉我下单还缺哪些资料；"
                        + "本次只做查询和说明，禁止创建订单和支付"));
    }
}
