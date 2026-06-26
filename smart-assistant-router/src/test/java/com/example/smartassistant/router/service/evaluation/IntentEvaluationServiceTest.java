package com.example.smartassistant.router.service.evaluation;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IntentEvaluationService 集成测试——验证编排后处理流程。
 *
 * <h3>变更前后对比</h3>
 * <table>
 *   <tr><th>步骤</th><th>修改前</th><th>修改后</th></tr>
 *   <tr><td>LLM 输出</td><td>→ TaskAnalysisResult（基本字段）</td><td>→ TaskAnalysisResult（基本字段 + 新字段）</td></tr>
 *   <tr><td>意图评测后处理</td><td>无</td><td>IntentEvaluationService.postProcess()</td></tr>
 *   <tr><td>实体归一化</td><td>直接使用 LLM 输出</td><td>EntityNormalizer 规则层纠错/归一</td></tr>
 *   <tr><td>词槽分析</td><td>无</td><td>SlotStateMachine 填充/缺失/冲突检测</td></tr>
 *   <tr><td>澄清建议</td><td>无</td><td>ClarificationService 追问生成</td></tr>
 * </table>
 */
class IntentEvaluationServiceTest {

    private EntityNormalizer entityNormalizer;
    private SlotStateMachine slotStateMachine;
    private ClarificationService clarificationService;
    private IntentEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        entityNormalizer = new EntityNormalizer();
        slotStateMachine = new SlotStateMachine();
        clarificationService = new ClarificationService();
        evaluationService = new IntentEvaluationService(
                entityNormalizer, slotStateMachine, clarificationService);
    }

    @Test
    @DisplayName("完整输入 → 实体归一化 + 词槽分析 + 澄清判断")
    void testFullPipeline() {
        // 模拟 LLM 输出
        TaskAnalysisResult llmResult = new TaskAnalysisResult();
        llmResult.setIntentCategory("ORDER/下单");
        Map<String, Object> entities = new LinkedHashMap<>();
        entities.put("departure_station", "杭州东");
        entities.put("arrival_station", "上海虹桥");
        entities.put("departure_date", "明天");
        entities.put("passenger", "张三");
        entities.put("amount", "二百");
        llmResult.setEntities(entities);

        // 执行后处理
        TaskAnalysisResult result = evaluationService.postProcess(
                "从杭洲东到上海红桥，明天二百以内，买两张", llmResult);

        // 1. 输入鲁棒性：纠错
        assertTrue(result.hasInputCorrections(),
                "应检测到'杭洲'→'杭州'和'红桥'→'虹桥'的纠错");
        assertEquals("从杭州东到上海虹桥", result.getStandardizedInput());

        // 2. 实体归一化
        assertTrue(result.hasNormalizedEntities(),
                "应有归一化实体（日期、金额）");

        // 3. 词槽填充
        assertFalse(result.getFilledSlots().isEmpty(),
                "应有已填充词槽");

        // 4. 词槽缺失
        // ticket_count、seat_type 等未提供
        assertTrue(result.hasMissingSlots() || !result.getDefaultableSlots().isEmpty(),
                "缺少的必填字段应出现在缺失或可默认列表中");

        // 5. 澄清判断
        if (result.hasMissingSlots() || result.hasSlotConflicts()) {
            assertTrue(result.isNeedsClarification(),
                    "有缺失或冲突时需要澄清");
            assertFalse(result.getClarificationQuestions().isEmpty(),
                    "澄清问题不应为空");
        }
    }

    @Test
    @DisplayName("空输入 → 无异常")
    void testEmptyInput() {
        TaskAnalysisResult empty = new TaskAnalysisResult();
        TaskAnalysisResult result = evaluationService.postProcess("", empty);
        assertNotNull(result);
        assertFalse(result.isMeaningful());
    }

    @Test
    @DisplayName("null LLM 结果 → 自动创建空结果")
    void testNullLlmResult() {
        TaskAnalysisResult result = evaluationService.postProcess("hello", null);
        assertNotNull(result);
        assertFalse(result.isMeaningful());
    }

    @Test
    @DisplayName("GENERAL 意图 → 离撇析+词槽全是空")
    void testGeneralIntent() {
        TaskAnalysisResult llmResult = new TaskAnalysisResult();
        llmResult.setIntentCategory("GENERAL");
        llmResult.setEntities(Map.of("location", "杭州"));

        TaskAnalysisResult result = evaluationService.postProcess("杭州天气", llmResult);

        // 纠错：无误纠
        assertFalse(result.hasInputCorrections(), "GENERAL 输入不应有纠错");
        // 无缺失：GENERAL 没有定义词槽表
        assertFalse(result.hasMissingSlots());
    }

    @Test
    @DisplayName("错别字输入 → 纠错后标准化填充")
    void testTypoInput() {
        TaskAnalysisResult llmResult = new TaskAnalysisResult();
        llmResult.setIntentCategory("ORDER/下单");
        llmResult.setEntities(Map.of("location", "杭洲"));

        TaskAnalysisResult result = evaluationService.postProcess("杭洲到上海", llmResult);

        assertTrue(result.hasInputCorrections());
        assertEquals("杭州到上海", result.getStandardizedInput());
    }

    @Test
    @DisplayName("冲突输入 → 检测到词槽矛盾")
    void testConflictInput() {
        TaskAnalysisResult llmResult = new TaskAnalysisResult();
        llmResult.setIntentCategory("ORDER/下单");
        Map<String, Object> entities = new LinkedHashMap<>();
        entities.put("departure_station", "杭州东站");
        entities.put("arrival_station", "杭州东站");
        entities.put("departure_date", "明天");
        entities.put("passenger", "张三");
        llmResult.setEntities(entities);

        TaskAnalysisResult result = evaluationService.postProcess("杭州到杭州", llmResult);

        assertTrue(result.hasSlotConflicts(),
                "出发站 == 到达站应被检测为冲突");
    }
}
