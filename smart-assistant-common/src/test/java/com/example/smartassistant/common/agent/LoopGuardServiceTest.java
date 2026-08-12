package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopGuardServiceTest {

    private final LoopGuardService guard = new LoopGuardService();

    @Test
    void weatherResultContainingConfirmDoesNotRequestUserDecision() {
        assertEquals(LoopGuardService.GuardAction.CONTINUE,
                guard.analyze("查询结果如下，请确认：北京今天晴，26°C。天气数据仅供参考。")
                        .action());
    }

    @Test
    void explicitActionConfirmationStillWaitsForUser() {
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("请确认是否继续创建订单").action());
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("订单信息如下，请确认后再下单").action());
    }

    @Test
    void explicitChoiceStillWaitsForUser() {
        assertEquals(LoopGuardService.GuardAction.AWAIT_CONFIRMATION,
                guard.analyze("请选择退款还是换货").action());
    }
}
