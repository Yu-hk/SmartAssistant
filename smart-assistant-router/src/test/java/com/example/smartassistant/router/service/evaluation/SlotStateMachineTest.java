package com.example.smartassistant.router.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SlotStateMachine 单元测试——验证词槽填充、缺失检测、冲突检测。
 *
 * <h3>变更前后对比</h3>
 * <table>
 *   <tr><th>维度</th><th>修改前</th><th>修改后</th></tr>
 *   <tr><td>词槽定义</td><td>无</td><td>6 个意图词槽表（ORDER/下单、改签、退票、查订单、PRODUCT/查商品、查库存）</td></tr>
 *   <tr><td>词槽缺失检测</td><td>无</td><td>按必填/可默认/可选分类，区分执行阶段</td></tr>
 *   <tr><td>词槽冲突检测</td><td>无</td><td>出发=到达、时间矛盾、价格矛盾 3 类冲突</td></tr>
 *   <tr><td>追问优先级排序</td><td>无</td><td>按必填缺失→可默认确认优先级排序</td></tr>
 * </table>
 */
class SlotStateMachineTest {

    private final SlotStateMachine machine = new SlotStateMachine();

    // ==================== ORDER/下单 词槽测试 ====================

    @Test
    @DisplayName("ORDER/下单: 完整输入 → 无缺失")
    void testOrderBooking_Complete() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_station", "杭州东站");
        entities.put("arrival_station", "上海虹桥站");
        entities.put("departure_date", "2026-04-24");
        entities.put("passenger", "张三");
        entities.put("ticket_count", 2); // ticket_count 为 ORDER/下单 必填且不可默认，补齐以构成完整输入

        var result = machine.analyzeSlots("ORDER/下单", entities);

        assertFalse(result.hasMissing(), "完整输入不应有缺失");
        assertFalse(result.hasConflicts(), "完整输入不应有冲突");
    }

    @Test
    @DisplayName("ORDER/下单: 缺到达站和乘客 → 检测到缺失")
    void testOrderBooking_MissingRequired() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_station", "杭州东站");
        entities.put("departure_date", "2026-04-24");

        var result = machine.analyzeSlots("ORDER/下单", entities);

        assertTrue(result.hasMissing());
        assertTrue(result.missingSlots().contains("arrival_station"),
                "必填字段 arrival_station 应出现在缺失列表中");
        assertTrue(result.missingSlots().contains("passenger"),
                "必填字段 passenger 应出现在缺失列表中");
    }

    @Test
    @DisplayName("ORDER/下单: 缺出发站 → 可默认（常用出发站）")
    void testOrderBooking_DefaultableDeparture() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("arrival_station", "上海虹桥站");
        entities.put("departure_date", "2026-04-24");
        entities.put("passenger", "张三");

        var result = machine.analyzeSlots("ORDER/下单", entities);

        assertTrue(result.hasDefaultable());
        assertTrue(result.defaultableSlots().contains("departure_station"),
                "departure_station 是可默认字段");
    }

    // ==================== 冲突检测 ====================

    @Test
    @DisplayName("出发站 == 到达站 → 显性冲突")
    void testConflict_SameStation() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_station", "杭州东站");
        entities.put("arrival_station", "杭州东站");

        var result = machine.analyzeSlots("ORDER/下单", entities);

        assertTrue(result.hasConflicts());
        assertEquals("departure_station", result.conflicts().get(0).get("slot1"));
        assertEquals("arrival_station", result.conflicts().get(0).get("slot2"));
    }

    @Test
    @DisplayName("出发时间 > 到达时间 → 显性冲突")
    void testConflict_TimeOrder() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_time", "下午4点");
        entities.put("arrival_time", "下午3点");

        var result = machine.analyzeSlots("ORDER/下单", entities);

        assertTrue(result.hasConflicts());
        assertTrue(((String) result.conflicts().get(0).get("reason")).contains("出发时间"));
    }

    @Test
    @DisplayName("无冲突输入 → 冲突列表为空")
    void testNoConflict() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_station", "杭州东站");
        entities.put("arrival_station", "上海虹桥站");

        var result = machine.analyzeSlots("ORDER/下单", entities);
        assertFalse(result.hasConflicts());
    }

    // ==================== 追问优先级 ====================

    @Test
    @DisplayName("ORDER/下单: 追问优先级排序")
    void testClarificationPriority() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_station", "杭州东站");

        var priority = machine.getClarificationPriority("ORDER/下单", entities);

        // 必填缺失按 askPriority 排序，arrival_station 和 departure_date 应该在前
        assertFalse(priority.isEmpty());
        // 应该包含至少一个缺失的必填字段
        assertTrue(priority.contains("arrival_station") || priority.contains("departure_date")
                || priority.contains("passenger"));
    }

    // ==================== 空 / 边缘情况 ====================

    @Test
    @DisplayName("null 意图 → 空结果")
    void testNullIntent() {
        var result = machine.analyzeSlots(null, new HashMap<>());
        assertFalse(result.hasMissing());
        assertFalse(result.hasConflicts());
    }

    @Test
    @DisplayName("不支持的意图 → 空结果")
    void testUnknownIntent() {
        var result = machine.analyzeSlots("UNKNOWN", new HashMap<>());
        assertFalse(result.hasMissing());
    }

    @Test
    @DisplayName("ORDER/退票: 缺订单号 → 缺失")
    void testRefund_MissingOrderId() {
        var result = machine.analyzeSlots("ORDER/退票", new HashMap<>());
        assertTrue(result.hasMissing());
        assertTrue(result.missingSlots().contains("order_id"));
    }

    @Test
    @DisplayName("ORDER/查订单: 可选字段 → 无缺失")
    void testQueryOrder_Optional() {
        var result = machine.analyzeSlots("ORDER/查订单", new HashMap<>());
        assertFalse(result.hasMissing(), "查订单的所有字段都是可选的");
    }
}
