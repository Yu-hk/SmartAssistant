package com.example.smartassistant.router.service.core;

import java.util.List;

/** Identifies parameter-collection replies that are valid intermediate answers, not failures. */
final class ClarificationReplyDetector {

    private static final List<String> ASK_MARKERS = List.of(
            "请提供", "请告诉", "请问", "需要您提供", "还需要", "请补充");
    private static final List<String> REQUIRED_PARAMETERS = List.of(
            "城市", "订单号", "商品名称", "出发地", "出发站", "到达地", "到达站",
            "日期", "地址", "手机号", "联系方式", "数量", "必要参数", "必要信息");

    private ClarificationReplyDetector() {
    }

    static boolean isRequiredParameterClarification(String reply) {
        if (reply == null || reply.isBlank()) return false;
        return ASK_MARKERS.stream().anyMatch(reply::contains)
                && REQUIRED_PARAMETERS.stream().anyMatch(reply::contains);
    }
}
