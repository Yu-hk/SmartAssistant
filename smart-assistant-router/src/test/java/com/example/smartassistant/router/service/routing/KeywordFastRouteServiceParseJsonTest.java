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
}
