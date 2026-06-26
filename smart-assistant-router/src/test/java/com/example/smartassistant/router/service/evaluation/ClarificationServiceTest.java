package com.example.smartassistant.router.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClarificationService 单元测试——验证澄清判断与追问生成。
 *
 * <h3>变更前后对比</h3>
 * <table>
 *   <tr><th>维度</th><th>修改前</th><th>修改后</th></tr>
 *   <tr><td>澄清判断</td><td>无</td><td>自动判断是否需要追问，区分意图不明/信息不完整/词槽矛盾 3 种场景</td></tr>
 *   <tr><td>追问生成</td><td>无</td><td>按优先级生成自然语言追问，覆盖 15+ 槽位类型</td></tr>
 *   <tr><td>可默认确认</td><td>无</td><td>对常用出发站等可默认字段生成确认问题</td></tr>
 * </table>
 */
class ClarificationServiceTest {

    private final ClarificationService service = new ClarificationService();
    private final SlotStateMachine slotMachine = new SlotStateMachine();

    // ==================== 意图不明 ====================

    @Test
    @DisplayName("意图不明 → 需要澄清主意图")
    void testUnknownIntent() {
        Map<String, Object> entities = new HashMap<>();
        var advice = service.generateFromSlotAnalysis(
                "UNKNOWN", entities,
                slotMachine.analyzeSlots("UNKNOWN", entities));

        assertTrue(advice.needsClarification());
        assertFalse(advice.questions().isEmpty());
        assertTrue(advice.reason().contains("意图"));
    }

    // ==================== 信息不完整 ====================

    @Test
    @DisplayName("ORDER/下单: 缺必填字段 → 需要追问")
    void testOrderBooking_MissingSlots() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_station", "杭州东站");

        var slotResult = slotMachine.analyzeSlots("ORDER/下单", entities);
        var advice = service.generateFromSlotAnalysis("ORDER/下单", entities, slotResult);

        assertTrue(advice.needsClarification());
        assertTrue(advice.reason().contains("不完整"));
        assertFalse(advice.questions().isEmpty());
    }

    @Test
    @DisplayName("订单查询 → 不需要追问")
    void testQueryOrder_NoClarification() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("order_id", "ORD123456");

        var slotResult = slotMachine.analyzeSlots("ORDER/查订单", entities);
        var advice = service.generateFromSlotAnalysis("ORDER/查订单", entities, slotResult);

        assertFalse(advice.needsClarification(), "查询类意图不需要追问");
    }

    @Test
    @DisplayName("GENERAL 意图 → 不需要追问")
    void testGeneralIntent_NoClarification() {
        Map<String, Object> entities = new HashMap<>();
        var slotResult = slotMachine.analyzeSlots("GENERAL", entities);
        var advice = service.generateFromSlotAnalysis("GENERAL", entities, slotResult);

        assertFalse(advice.needsClarification());
    }

    // ==================== 词槽矛盾 ====================

    @Test
    @DisplayName("出发站 == 到达站 → 追问解决矛盾")
    void testConflict_GeneratesQuestions() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("departure_station", "杭州东站");
        entities.put("arrival_station", "杭州东站");

        var slotResult = slotMachine.analyzeSlots("ORDER/下单", entities);
        var advice = service.generateFromSlotAnalysis("ORDER/下单", entities, slotResult);

        assertTrue(advice.needsClarification());
        assertTrue(advice.reason().contains("矛盾") || advice.reason().contains("冲突")
                || advice.reason().contains("矛盾"));
        assertFalse(advice.questions().isEmpty());
    }

    // ==================== 可默认槽位确认 ====================

    @Test
    @DisplayName("缺可默认出发站 → 生成确认问题")
    void testDefaultableSlot_GeneratesConfirmation() {
        Map<String, Object> entities = new HashMap<>();
        entities.put("arrival_station", "上海虹桥站");
        entities.put("departure_date", "2026-04-24");
        entities.put("passenger", "张三");

        var slotResult = slotMachine.analyzeSlots("ORDER/下单", entities);
        var advice = service.generateFromSlotAnalysis("ORDER/下单", entities, slotResult);

        // 所有必填都已提供（arrival_station, departure_date, passenger）
        // 只有 departure_station 可默认
        assertTrue(advice.needsClarification());
        assertFalse(advice.defaultableSlots().isEmpty());
    }

    // ==================== 边界情况 ====================

    @Test
    @DisplayName("null 意图 → 询问需要什么帮助")
    void testNullIntent_GenerateAdvice() {
        var advice = service.generateAdvice(null, new HashMap<>(), "");
        assertTrue(advice.needsClarification());
    }

    @Test
    @DisplayName("空实体 → 可能缺失字段")
    void testEmptyEntities() {
        var slotResult = slotMachine.analyzeSlots("ORDER/下单", new HashMap<>());
        var advice = service.generateFromSlotAnalysis("ORDER/下单", new HashMap<>(), slotResult);

        assertTrue(advice.needsClarification());
        assertFalse(advice.questions().isEmpty());
    }
}
