package com.example.smartassistant.router.service.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestionExtractorTest {

    @Test
    void currentQuestion_shouldExcludeConversationHistoryAndProcessingInstructions() {
        String prompt = """
                【当前问题】
                ORD-LOAD000001001
                【历史对话】
                用户：查询我的订单物流进度
                助手：查到最近3笔订单，也可以继续查询物流轨迹或售后资格。
                【处理要求】
                请结合上一轮目标继续处理。
                """;

        assertEquals("ORD-LOAD000001001", QuestionExtractor.extractRawQuestion(prompt));
    }

    @Test
    void plainQuestion_shouldRemainUnchanged() {
        assertEquals("查询我的订单物流进度",
                QuestionExtractor.extractRawQuestion("  查询我的订单物流进度  "));
    }
}
