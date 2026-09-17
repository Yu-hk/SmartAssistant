package com.example.smartassistant.consumer.service.sentiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SentimentAnalysisService 单元测试。
 */
class SentimentAnalysisServiceTest {
    @org.junit.jupiter.api.Test
    void repeatedUnansweredQuestionNeedsEmpathyInsteadOfReusingNeutralAnswer() {
        var service = new SentimentAnalysisService();
        var result = service.analyze("问了半天还没说明白，AirPods Pro到底多少钱，有没有货？");
        org.junit.jupiter.api.Assertions.assertEquals(3, result.level());
        var insight = TurnInsight.analyzed(result, 1);
        org.junit.jupiter.api.Assertions.assertTrue(insight.bypassAnswerCache());
        org.junit.jupiter.api.Assertions.assertEquals("NORMAL", insight.suggestedPriority());
        org.junit.jupiter.api.Assertions.assertTrue(insight.adaptReply("售价1999元，库存充足。").startsWith("抱歉"));
        org.junit.jupiter.api.Assertions.assertEquals(2, service.analyze("不是没说明白，我只是再确认一下价格。").level());
    }

    private final SentimentAnalysisService service = new SentimentAnalysisService();

    @Test
    @DisplayName("正面情绪：谢谢")
    void analyze_thanks_shouldBePositive() {
        var result = service.analyze("谢谢");
        assertEquals(1, result.level(), "谢谢应为正面(level=1)");
        assertEquals(95, result.confidence(), "关键词命中应返回高置信度");
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
        assertFalse(result.needHandoff(), "负面情绪不代表已经请求转人工");
    }

    @Test
    @DisplayName("愤怒：投诉")
    void analyze_angry_shouldBeLevel5() {
        var result = service.analyze("我要投诉你们");
        assertEquals(5, result.level(), "投诉应为愤怒(level=5)");
        assertFalse(result.needHandoff(), "投诉不应中断业务处理");
    }

    @Test
    @DisplayName("空输入返回中性")
    void analyze_empty_shouldBeNeutral() {
        var result = service.analyze("");
        assertEquals(2, result.level(), "空输入默认中性");
        assertEquals(0, result.confidence(), "空输入不应伪造分析置信度");
    }

    @Test
    @DisplayName("getTonePrefix: level=3 返回道歉前缀")
    void getTonePrefix_level3_shouldReturnApology() {
        String prefix = service.getTonePrefix(3);
        assertTrue(prefix.contains("抱歉"), "level=3 应包含道歉");
    }

    @Test
    void emotionalReplyDoesNotInventPriorityOrCompletedActions() {
        String prefix = service.getTonePrefix(4);
        assertTrue(prefix.contains("抱歉"));
        for (String promise : java.util.List.of("立即", "已加急", "已催办", "已转", "别着急", "冷静")) {
            assertFalse(prefix.contains(promise));
        }
    }

    @Test
    @DisplayName("getTonePrefix: level=5 返回转人工")
    void getTonePrefix_level5_shouldReturnHandoff() {
        String prefix = service.getTonePrefix(5);
        assertTrue(prefix.contains("可以联系人工客服"));
        assertFalse(prefix.contains("正在"));
    }

    @Test
    @DisplayName("转人工回复不重复提示")
    void getHandoffResponse_level5_shouldNotDuplicateHandoffText() {
        String response = service.getHandoffResponse(5);
        assertEquals("非常抱歉给您带来不好的体验。可以联系人工客服进一步协助。", response);
        assertEquals(response.indexOf("人工客服"), response.lastIndexOf("人工客服"));
    }

    @Test
    void businessWordsAndNegationDoNotTriggerAnger() {
        for (String text : java.util.List.of("请问赔偿规则是什么", "投诉流程怎么走", "315是什么", "一般几天到账", "不是不满意")) {
            assertEquals(2, service.analyze(text).level(), text);
            assertFalse(service.analyze(text).needHandoff(), text);
        }
        assertEquals(3, service.analyze("不太满意").level());
        assertTrue(service.analyze("请转人工").needHandoff());
        assertFalse(service.analyze("不用转接人工客服").needHandoff());
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
