package com.example.smartassistant.router.service.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KeywordFastRouteServiceTest {

    private KeywordFastRouteService service;

    @BeforeEach
    void setUp() {
        service = new KeywordFastRouteService(
                new KeywordFastRouteService.KeywordRouteProperties());
        service.init();
    }

    @Test
    void naturalOrderStatusQueryUsesFastRoute() {
        var match = service.match("查询订单 ORD-LOAD000001003 的当前状态");

        assertNotNull(match);
        assertEquals("order_agent", match.getTargetAgent());
        assertEquals("订单查询", match.getIntentTag());
    }

    @Test
    void bareOrderIdentifierUsesFastRouteWithoutLlmClassification() {
        var match = service.match("ORD-LOAD000001001");

        assertNotNull(match);
        assertEquals("order_agent", match.getTargetAgent());
        assertEquals("\u8ba2\u5355\u67e5\u8be2", match.getIntentTag());
        assertEquals("order_id_fast_route", match.getMatchedRuleName());
        assertEquals(0.99, match.getConfidence());
    }

    @Test
    void lowerCaseOrderIdentifierEmbeddedInMessageUsesFastRoute() {
        var match = service.match("please check ord-load000001001");

        assertNotNull(match);
        assertEquals("order_agent", match.getTargetAgent());
        assertEquals("order_id_fast_route", match.getMatchedRuleName());
    }
}
