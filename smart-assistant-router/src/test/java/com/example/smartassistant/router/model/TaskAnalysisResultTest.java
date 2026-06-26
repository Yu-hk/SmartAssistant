package com.example.smartassistant.router.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskAnalysisResult 单元测试——验证新增字段及便利方法。
 *
 * <h3>变更前后对比</h3>
 * <table>
 *   <tr><th>字段</th><th>修改前</th><th>修改后</th></tr>
 *   <tr><td>intent_category</td><td>ORDER/PRODUCT/GENERAL/COMPLEX/UNKNOWN</td><td>不变</td></tr>
 *   <tr><td>entities</td><td>5 个固定字段</td><td>13 个字段（新增 departure_station, arrival_station, departure_time, passenger, seat_type, train_type, departure_date）</td></tr>
 *   <tr><td>sub_intents</td><td>❌ 不支持</td><td>✅ 多意图拆分（依赖关系、执行顺序）</td></tr>
 *   <tr><td>implicit_intents</td><td>❌ 不支持</td><td>✅ 隐含意图推断（expression, inferred_intent, confidence, trigger_basis）</td></tr>
 *   <tr><td>normalized_entities</td><td>❌ 不支持</td><td>✅ 实体归一化值</td></tr>
 *   <tr><td>filled/missing/defaultable slots</td><td>❌ 不支持</td><td>✅ 词槽状态追踪</td></tr>
 *   <tr><td>slot_conflicts</td><td>❌ 不支持</td><td>✅ 词槽冲突检测</td></tr>
 *   <tr><td>needs_clarification</td><td>❌ 不支持</td><td>✅ 澄清判断</td></tr>
 *   <tr><td>standardized_input</td><td>❌ 不支持</td><td>✅ 输入鲁棒性（纠错后文本）</td></tr>
 * </table>
 */
class TaskAnalysisResultTest {

    @Test
    @DisplayName("空结果: isMeaningful() 返回 false")
    void testEmptyResult() {
        TaskAnalysisResult result = TaskAnalysisResult.empty();
        assertFalse(result.isMeaningful());
    }

    @Test
    @DisplayName("意图分类: getter/setter")
    void testIntentCategory() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setIntentCategory("ORDER");
        assertEquals("ORDER", result.getIntentCategory());
    }

    // ==================== 多意图拆分 ====================

    @Test
    @DisplayName("多意图拆分: 单个意图不算多意图")
    void testSingleSubIntent() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        List<Map<String, Object>> intents = new ArrayList<>();
        intents.add(Map.of("intent", "查票", "order", 1));
        result.setSubIntents(intents);
        assertFalse(result.hasSubIntents(), "单个子意图不算多意图");
    }

    @Test
    @DisplayName("多意图拆分: 多个意图含依赖关系")
    void testMultiSubIntents() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        List<Map<String, Object>> intents = new ArrayList<>();
        intents.add(Map.of("intent", "查票", "description", "查明天去上海的票", "depends_on", null, "order", 1));
        intents.add(Map.of("intent", "预订", "description", "合适的就下单", "depends_on", "查票", "order", 2));
        result.setSubIntents(intents);
        assertTrue(result.hasSubIntents());
        assertEquals(2, result.getSubIntents().size());
        assertEquals("查票", result.getSubIntents().get(0).get("intent"));
        assertEquals("预订", result.getSubIntents().get(1).get("intent"));
    }

    // ==================== 隐含意图 ====================

    @Test
    @DisplayName("隐含意图: 可推断用户真实目标")
    void testImplicitIntents() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        List<Map<String, Object>> implicit = new ArrayList<>();
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("expression", "别耽误四点的会");
        intent.put("inferred_intent", "到达时间需早于16点，预留出站缓冲");
        intent.put("confidence", 0.85);
        intent.put("trigger_basis", "业务规则");
        implicit.add(intent);
        result.setImplicitIntents(implicit);
        assertTrue(result.hasImplicitIntents());
        assertEquals("别耽误四点的会", result.getImplicitIntents().get(0).get("expression"));
    }

    // ==================== 词槽状态 ====================

    @Test
    @DisplayName("词槽状态: 缺失/填充/可默认")
    void testSlotStatus() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setFilledSlots(List.of("departure_station", "arrival_station"));
        result.setMissingSlots(List.of("passenger", "departure_date"));
        result.setDefaultableSlots(List.of("seat_type"));

        assertTrue(result.hasMissingSlots());
        assertEquals(2, result.getMissingSlots().size());
        assertTrue(result.getMissingSlots().contains("passenger"));
        assertEquals(1, result.getDefaultableSlots().size());
    }

    // ==================== 词槽冲突 ====================

    @Test
    @DisplayName("词槽冲突: 出发站等于到达站")
    void testSlotConflict() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("slot1", "departure_station");
        conflict.put("slot2", "arrival_station");
        conflict.put("type", "显性冲突");
        conflict.put("reason", "出发站和到达站不能相同");
        conflicts.add(conflict);
        result.setSlotConflicts(conflicts);

        assertTrue(result.hasSlotConflicts());
        assertEquals("departure_station", result.getSlotConflicts().get(0).get("slot1"));
    }

    // ==================== 澄清判断 ====================

    @Test
    @DisplayName("澄清判断: 需要追问")
    void testClarification() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setNeedsClarification(true);
        result.setClarificationReason("信息不完整");
        result.setClarificationQuestions(List.of("请问您要去哪个站？"));

        assertTrue(result.isNeedsClarification());
        assertEquals(1, result.getClarificationQuestions().size());
        assertEquals("请问您要去哪个站？", result.getClarificationQuestions().get(0));
    }

    @Test
    @DisplayName("澄清判断: 不需要追问")
    void testNoClarification() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setNeedsClarification(false);
        assertFalse(result.isNeedsClarification());
    }

    // ==================== 输入鲁棒性 ====================

    @Test
    @DisplayName("输入鲁棒性: 纠错记录")
    void testInputCorrections() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setStandardizedInput("杭州到上海");
        result.setInputCorrections(List.of(
                Map.of("original", "杭洲", "corrected", "杭州", "type", "typo")
        ));
        result.setNoiseTypes(List.of("typo"));

        assertEquals("杭州到上海", result.getStandardizedInput());
        assertTrue(result.hasInputCorrections());
        assertEquals("typo", result.getNoiseTypes().get(0));
    }

    // ==================== 实体归一化 ====================

    @Test
    @DisplayName("实体归一化: 日期归一")
    void testNormalizedEntities() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setNormalizedEntities(Map.of("date", "2026-04-24"));
        result.setNormalizationDetails(List.of(
                Map.of("field", "date", "original", "明天", "normalized", "2026-04-24", "type", "date")
        ));

        assertTrue(result.hasNormalizedEntities());
        assertEquals("2026-04-24", result.getNormalizedEntities().get("date"));
    }

    // ==================== isMeaningful() 扩展 ====================

    @Test
    @DisplayName("isMeaningful(): 新字段也参与判断")
    void testIsMeaningful_NewFields() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        assertFalse(result.isMeaningful());

        // 只要有任一新增字段非空，就视为有意义
        result.setNeedsClarification(true);
        assertTrue(result.isMeaningful());

        result.setNeedsClarification(false);
        assertFalse(result.isMeaningful());

        result.setStandardizedInput("杭州东站");
        assertTrue(result.isMeaningful());
    }

    // ==================== toString 覆盖 ====================

    @Test
    @DisplayName("toString(): 包含新字段")
    void testToString() {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setIntentCategory("ORDER");
        result.setTaskGoal("查明天去上海的票");
        String str = result.toString();
        assertTrue(str.contains("ORDER"));
        assertTrue(str.contains("查明天去上海的票"));
    }
}
