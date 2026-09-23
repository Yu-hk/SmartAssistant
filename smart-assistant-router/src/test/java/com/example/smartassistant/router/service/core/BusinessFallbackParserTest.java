package com.example.smartassistant.router.service.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BusinessFallbackParserTest {
    private final BusinessFallbackParser parser = new BusinessFallbackParser();
    @Test void dispatchesWithoutBusinessSlotsOrReplyTemplates() {
        assertThat(parser.parse("AirPods Pro多少钱？有货吗？").kind()).isEqualTo(BusinessFallbackParser.Kind.PRODUCT_QUERY);
        assertThat(parser.parse("这个规格是多少").kind()).isEqualTo(BusinessFallbackParser.Kind.PRODUCT_QUERY);
        for (String question : new String[]{"取消订单 ORD-1001", "申请退款 ORD-1001；原因：不喜欢", "下单耳机", "退单"}) {
            var parsed = parser.parse(question);
            assertThat(parsed.kind()).isEqualTo(BusinessFallbackParser.Kind.ORDER);
            assertThat(parsed.question()).isEqualTo(question);
        }
    }
    @Test void keepsNegationAndConditionsForReadOnlyDomainDecision() {
        for (String question : new String[]{"不要取消订单 ORD-1001", "如果有货帮我下单", "先查询然后申请退款 ORD-1001"}) {
            assertThat(parser.parse(question).kind()).isEqualTo(BusinessFallbackParser.Kind.ORDER);
            assertThat(parser.parse(question).question()).isEqualTo(question);
        }
    }
    @Test void unknownOrOversizeRequestStaysUnsupported() {
        for (String question : new String[]{"", "随便聊聊", "下单" + "长".repeat(500)})
            assertThat(parser.parse(question).kind()).isEqualTo(BusinessFallbackParser.Kind.UNKNOWN);
    }
}
