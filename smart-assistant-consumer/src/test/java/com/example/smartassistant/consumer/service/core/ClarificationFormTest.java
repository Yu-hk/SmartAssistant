package com.example.smartassistant.consumer.service.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClarificationFormTest {
    @Test void onlyAsksForExplicitMissingFieldsAndDoesNotPrefillExamples() {
        var form = ClarificationForm.fromReply("请补充可接受的重量上限（例如重量不超过1.3公斤）。", "推荐笔记本，预算3000元", "COMPLETED");
        assertNotNull(form);
        assertEquals(1, form.fields().size());
        assertEquals("weight", form.fields().getFirst().key());
        assertEquals("", form.fields().getFirst().value());
        assertEquals(1, ClarificationForm.fromReply("已知预算3000元，请补充重量上限。", "", "SUCCESS").fields().size());
        assertEquals("weight", ClarificationForm.fromReply("为了帮您准确筛选，还想确认一下可接受的重量上限（例如重量不超过1.3公斤）。", "", "SUCCESS").fields().getFirst().key());
        assertEquals("product", ClarificationForm.fromReply("你想选购哪类商品？我会保留已提供的预算和特征。", "", "SUCCESS").fields().getFirst().key());
    }

    @Test void keepsKnownValuesEditableAndDeduplicatesFields() {
        var form = ClarificationForm.fromReply("请确认重量上限及预算。请补充预算。", "重量上限为3公斤，预算改为5000元", "CLARIFICATION");
        assertEquals(2, form.fields().size());
        assertEquals("3", form.fields().get(0).value());
        assertEquals("5000", form.fields().get(1).value());
        assertEquals("", ClarificationForm.fromReply("请确认预算。", "预算1万元", "SUCCESS").fields().getFirst().value());
    }

    @Test void doesNotTurnNormalAnswersOptionalOffersOrFailuresIntoForms() {
        for (String reply : new String[]{"售价1999元，重量1公斤。", "如果您想了解商品名称或预算，请告诉我。", "请提供城市或者订单号。", "请确认下单。"}) {
            assertNull(ClarificationForm.fromReply(reply, "", "SUCCESS"));
        }
        assertNull(ClarificationForm.fromReply("请提供订单号。", "", "FAILED"));
        assertNull(ClarificationForm.fromReply("请提供订单号。", "", "AWAITING_APPROVAL"));
    }

    @Test void supportsCityOrderAndProductWithoutCollectingUnrequestedPrivateData() {
        assertEquals("city", ClarificationForm.fromReply("您想查哪个城市或地区的天气？告诉我具体地点，我帮您查一下。", "查询天气", "SUCCESS").fields().getFirst().key());
        assertEquals("city", ClarificationForm.fromReply("请问您想查询哪个城市的天气？", "查询天气", "SUCCESS").fields().getFirst().key());
        var form = ClarificationForm.fromReply("请提供订单号。", "查询退款", "SUCCESS");
        assertEquals(1, form.fields().size());
        assertEquals("orderNumber", form.fields().getFirst().key());
        assertNull(ClarificationForm.fromReply("请提供密码和验证码。", "", "SUCCESS"));
    }

    @Test void historyDoesNotReconstructUnsignedLegacyForms() throws Exception {
        var message = new com.example.smartassistant.consumer.service.admin.AdminService.SessionMessage(
                "a", "assistant", "请提供城市。", "", "r", "fallback", "SUCCESS", null,
                null, null, null, null, null, java.util.List.of());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(message));
        assertTrue(json.path("clarificationForm").isNull());
    }
}
