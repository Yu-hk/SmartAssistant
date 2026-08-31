package com.example.smartassistant.router.service.evaluation;

import java.util.List;
import java.util.Map;

final class TestSlotSchemas {
    private TestSlotSchemas() { }

    static Map<String, List<SlotStateMachine.SlotDef>> schemas() {
        return Map.of(
                "ORDER/下单", List.of(
                        new SlotStateMachine.SlotDef("departure_station", "出发站", true, true, false, 1),
                        new SlotStateMachine.SlotDef("arrival_station", "到达站", true, false, false, 1),
                        new SlotStateMachine.SlotDef("departure_date", "出发日期", true, false, false, 2),
                        new SlotStateMachine.SlotDef("passenger", "乘客姓名", true, false, true, 3),
                        new SlotStateMachine.SlotDef("ticket_count", "票数", true, false, false, 4)),
                "ORDER/退票", List.of(
                        new SlotStateMachine.SlotDef("order_id", "订单号", true, false, true, 1)),
                "ORDER/查订单", List.of(
                        new SlotStateMachine.SlotDef("order_id", "订单号", false, false, false, 1)));
    }
}
