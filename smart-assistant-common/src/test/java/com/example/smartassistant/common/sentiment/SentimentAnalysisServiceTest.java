package com.example.smartassistant.common.sentiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SentimentAnalysisService 单元测试。
 */
class SentimentAnalysisServiceTest {

    private final SentimentAnalysisService service = new SentimentAnalysisService();

    @Test
    @DisplayName("正面情绪：谢谢")
    void analyze_thanks_shouldBePositive() {
        var result = service.analyze("谢谢");
        assertEquals(1, result.level(), "谢谢应为正面(level=1)");
        assertFalse(result.needHandoff(), "正面情绪不需要转人工");
    }

    @Test
    @DisplayName("中性情绪：请问")
    void analyze_ask_shouldBeNeutral() {
        var result = service.analyze("请问一下退款的流程");
        assertEquals(2, result.level(), "请问应为中性(level=2)");
        assertFalse(result.needHandoff());
    }

    @Test
    @DisplayName("轻微负面：有点慢")
    void analyze_slightlyNegative_shouldBeLevel3() {
        var result = service.analyze("你们这个速度有点慢啊");
        assertEquals(3, result.level(), "有点慢应为轻微负面(level=3)");
        assertFalse(result.needHandoff(), "level=3 不需要转人工");
    }

    @Test
    @DisplayName("负面：等了很久")
    void analyze_negative_shouldBeLevel4() {
        var result = service.analyze("等了很久了，太慢了");
        assertEquals(4, result.level(), "等了很久应为负面(level=4)");
        assertTrue(result.needHandoff(), "level>=4 需要转人工");
    }

    @Test
    @DisplayName("愤怒：投诉")
    void analyze_angry_shouldBeLevel5() {
        var result = service.analyze("我要投诉你们");
        assertEquals(5, result.level(), "投诉应为愤怒(level=5)");
        assertTrue(result.needHandoff(), "level=5 需要转人工");
    }

    @Test
    @DisplayName("空输入返回中性")
    void analyze_empty_shouldBeNeutral() {
        var result = service.analyze("");
        assertEquals(2, result.level(), "空输入默认中性");
    }

    @Test
    @DisplayName("getTonePrefix: level=3 返回道歉前缀")
    void getTonePrefix_level3_shouldReturnApology() {
        String prefix = service.getTonePrefix(3);
        assertTrue(prefix.contains("抱歉"), "level=3 应包含道歉");
    }

    @Test
    @DisplayName("getTonePrefix: level=5 返回转人工")
    void getTonePrefix_level5_shouldReturnHandoff() {
        String prefix = service.getTonePrefix(5);
        assertTrue(prefix.contains("转接"), "level=5 应包含转接人工");
    }

    @Test
    @DisplayName("getTonePrefix: level=1 返回空字符串")
    void getTonePrefix_level1_shouldReturnEmpty() {
        assertEquals("", service.getTonePrefix(1), "正面情绪无需调整语气");
    }

    @Test
    @DisplayName("情感分析结果包含 responseStrategy")
    void analyze_shouldIncludeResponseStrategy() {
        var result = service.analyze("太慢了");
        assertNotNull(result.responseStrategy());
        assertFalse(result.responseStrategy().isBlank());
    }
}
