package com.example.a2a.router.service;

import com.example.smartassistant.router.service.extraction.KeywordExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关键词提取服务测试
 */
@SpringBootTest
class KeywordExtractionServiceTest {
    
    @Autowired
    private KeywordExtractionService keywordExtractionService;
    
    @Test
    void testExtractKeywords_LocationAndWeather() {
        String question = "北京明天天气怎么样？";
        String instruction = keywordExtractionService.extractKeywordsAsInstruction(question);
        
        System.out.println("原始问题: " + question);
        System.out.println("精简指令: " + instruction);
        
        assertNotNull(instruction);
        assertTrue(instruction.contains("地点: 北京"));
        assertTrue(instruction.contains("时间: 明天"));
        assertTrue(instruction.contains("意图: 天气查询"));
    }
    
    @Test
    void testExtractKeywords_FoodRecommendation() {
        String question = "成都有什么好吃的餐厅推荐？";
        String instruction = keywordExtractionService.extractKeywordsAsInstruction(question);
        
        System.out.println("\n原始问题: " + question);
        System.out.println("精简指令: " + instruction);
        
        assertNotNull(instruction);
        assertTrue(instruction.contains("地点: 成都"));
        assertTrue(instruction.contains("意图: 美食推荐"));
        assertTrue(instruction.contains("需求: 推荐"));
    }
    
    @Test
    void testExtractKeywords_TravelPlanning() {
        String question = "帮我规划一下杭州周末的旅游行程";
        String instruction = keywordExtractionService.extractKeywordsAsInstruction(question);
        
        System.out.println("\n原始问题: " + question);
        System.out.println("精简指令: " + instruction);
        
        assertNotNull(instruction);
        assertTrue(instruction.contains("地点: 杭州"));
        assertTrue(instruction.contains("时间: 周末"));
        assertTrue(instruction.contains("意图: 旅游规划"));
        assertTrue(instruction.contains("需求: 规划"));
    }
    
    @Test
    void testExtractKeywords_ComplexQuestion() {
        String question = "上海下周有什么特色美食和旅游景点？";
        String instruction = keywordExtractionService.extractKeywordsAsInstruction(question);
        
        System.out.println("\n原始问题: " + question);
        System.out.println("精简指令: " + instruction);
        
        assertNotNull(instruction);
        assertTrue(instruction.contains("地点: 上海"));
        assertTrue(instruction.contains("时间: 下周"));
        // 可能识别为美食或旅游，取决于优先级
        assertTrue(instruction.contains("意图:"));
    }
    
    @Test
    void testExtractKeywords_NoLocation() {
        String question = "今天天气如何？";
        String instruction = keywordExtractionService.extractKeywordsAsInstruction(question);
        
        System.out.println("\n原始问题: " + question);
        System.out.println("精简指令: " + instruction);
        
        assertNotNull(instruction);
        assertFalse(instruction.contains("地点:"));
        assertTrue(instruction.contains("时间: 今天"));
        assertTrue(instruction.contains("意图: 天气查询"));
    }
    
    @Test
    void testBatchExtractKeywords() {
        java.util.List<String> questions = java.util.List.of(
            "北京明天天气怎么样？",
            "成都有什么好吃的？",
            "帮我规划杭州的行程"
        );
        
        java.util.List<String> instructions = keywordExtractionService.batchExtractKeywords(questions);
        
        assertEquals(3, instructions.size());
        
        System.out.println("\n批量提取结果:");
        for (int i = 0; i < questions.size(); i++) {
            System.out.println((i + 1) + ". " + questions.get(i));
            System.out.println("   → " + instructions.get(i));
        }
    }
    
    @Test
    void testTokenReduction() {
        String originalQuestion = "我想了解一下北京明天的天气情况，看看适不适合出门游玩";
        String instruction = keywordExtractionService.extractKeywordsAsInstruction(originalQuestion);
        
        System.out.println("\n=== Token 节省测试 ===");
        System.out.println("原始问题长度: " + originalQuestion.length() + " 字符");
        System.out.println("精简指令长度: " + instruction.length() + " 字符");
        System.out.println("精简指令: " + instruction);
        
        double reduction = (1.0 - (double) instruction.length() / originalQuestion.length()) * 100;
        System.out.printf("Token 节省: %.1f%%\n", reduction);
        
        assertTrue(reduction > 50, "应该至少节省 50% 的 token");
    }
    
    @Test
    void testExtractKeywordsFromFullPrompt_WithUserProfile() {
        // 模拟 Consumer 发送的完整 Prompt（带明确标记）
        String fullPrompt = """
            【用户画像】
            用户偏好：喜欢川菜和火锅，经常查询北京的餐厅
            历史行为：上周查询过“北京火锅推荐”
            
            【历史对话】
            用户: 北京有什么好吃的？
            Agent: 推荐您尝试全聚德烤鸭，还有鼎泰丰的小笼包也很不错...
            用户: 那附近有什么景点可以逛？
            Agent: 您可以去故宫、天坛，距离都不远。
            
            【当前问题】
            明天天气怎么样？适合出门吃火锅吗？
            """;
        
        String optimizedInstruction = keywordExtractionService.extractKeywordsFromFullPrompt(fullPrompt);
        
        System.out.println("\n=== 完整 Prompt 优化测试 ===");
        System.out.println("原始 Prompt 长度: " + fullPrompt.length() + " 字符");
        System.out.println("优化后长度: " + optimizedInstruction.length() + " 字符");
        System.out.println("\n原始 Prompt:\n" + fullPrompt);
        System.out.println("\n优化后 Instruction:\n" + optimizedInstruction);
        
        // 验证用户画像完整保留
        assertTrue(optimizedInstruction.contains("【用户画像】"));
        assertTrue(optimizedInstruction.contains("用户偏好：喜欢川菜和火锅"));
        
        // 验证历史对话被精简但保留关键信息
        assertTrue(optimizedInstruction.contains("【历史对话】"));
        assertTrue(optimizedInstruction.contains("用户:"), "应该保留用户发言");
        assertTrue(optimizedInstruction.contains("Agent:"), "应该保留 Agent 回复");
        // 历史对话中的地点应该被提取
        assertTrue(optimizedInstruction.contains("地点:北京") || optimizedInstruction.contains("北京"));
        
        // 验证当前问题被精简
        assertTrue(optimizedInstruction.contains("【当前问题】"));
        assertTrue(optimizedInstruction.contains("地点: 北京"));
        assertTrue(optimizedInstruction.contains("时间: 明天"));
        assertTrue(optimizedInstruction.contains("意图: 天气查询"));
        
        // 验证 token 节省
        double reduction = (1.0 - (double) optimizedInstruction.length() / fullPrompt.length()) * 100;
        System.out.printf("\nToken 节省: %.1f%%\n", reduction);
        assertTrue(reduction > 25, "应该至少节省 25% 的 token（包含历史对话优化）");
    }
    
    @Test
    void testExtractKeywordsFromFullPrompt_SimpleQuestion() {
        // 没有用户画像的简单问题
        String simplePrompt = "【当前问题】\n北京明天天气怎么样？";
        
        String optimizedInstruction = keywordExtractionService.extractKeywordsFromFullPrompt(simplePrompt);
        
        System.out.println("\n=== 简单问题优化测试 ===");
        System.out.println("原始: " + simplePrompt);
        System.out.println("优化后: " + optimizedInstruction);
        
        assertTrue(optimizedInstruction.contains("地点: 北京"));
        assertTrue(optimizedInstruction.contains("时间: 明天"));
        assertTrue(optimizedInstruction.contains("意图: 天气查询"));
    }
    
    @Test
    void testCacheMechanism() {
        String question = "北京明天天气怎么样？";
        
        // 第一次调用（缓存未命中）
        long start1 = System.currentTimeMillis();
        String result1 = keywordExtractionService.extractKeywordsAsInstruction(question);
        long duration1 = System.currentTimeMillis() - start1;
        
        // 第二次调用（缓存命中）
        long start2 = System.currentTimeMillis();
        String result2 = keywordExtractionService.extractKeywordsAsInstruction(question);
        long duration2 = System.currentTimeMillis() - start2;
        
        System.out.println("\n=== 缓存机制测试 ===");
        System.out.println("第一次调用耗时: " + duration1 + " ms");
        System.out.println("第二次调用耗时: " + duration2 + " ms");
        System.out.println("加速比: " + (duration1 > 0 ? duration1 / Math.max(duration2, 1) : "N/A") + "x");
        
        // 验证结果一致
        assertEquals(result1, result2);
        
        // 验证缓存统计
        var stats = keywordExtractionService.getCacheStats();
        System.out.println("缓存大小: " + stats.get("cacheSize"));
        System.out.println("最大缓存: " + stats.get("maxCacheSize"));
        
        assertTrue((int) stats.get("cacheSize") > 0);
    }
    
    @Test
    void testAdaptiveLearning() {
        // 模拟多次调用，观察权重变化
        String foodQuestion1 = "推荐成都火锅餐厅";
        String foodQuestion2 = "有什么好吃的川菜馆";
        
        keywordExtractionService.extractKeywordsAsInstruction(foodQuestion1);
        keywordExtractionService.extractKeywordsAsInstruction(foodQuestion2);
        
        // 获取当前权重
        var weights = keywordExtractionService.getKeywordWeights();
        
        System.out.println("\n=== 自适应学习测试 ===");
        System.out.println("当前关键词权重数量: " + weights.size());
        
        if (!weights.isEmpty()) {
            weights.forEach((keyword, weight) -> 
                System.out.printf("  '%s' = %.2f\n", keyword, weight)
            );
        }
        
        // 验证权重已被记录
        assertTrue(weights.size() > 0, "应该有学习的关键词权重");
    }
    
    @Test
    void testComplexQuestionWithLLM() {
        // 复杂问题：包含多个意图和修饰词
        String complexQuestion = "我想了解一下如果周末去杭州玩的话，那边的天气情况如何，有没有什么值得推荐的特色美食和景点可以安排进行程里？";
        
        String instruction = keywordExtractionService.extractKeywordsAsInstruction(complexQuestion);
        
        System.out.println("\n=== 复杂问题 LLM 辅助测试 ===");
        System.out.println("原始问题长度: " + complexQuestion.length() + " 字符");
        System.out.println("精简指令: " + instruction);
        
        assertNotNull(instruction);
        assertTrue(instruction.contains("地点: 杭州"));
        assertTrue(instruction.contains("时间: 周末"));
        // 应该识别出主要意图（可能是旅游或美食）
        assertTrue(instruction.contains("意图:"), "应该识别出意图");
    }
    
    @Test
    void testHistoryOptimization() {
        // 模拟多轮历史对话
        String history = """
            用户: 北京明天天气怎么样？
            Agent: 北京明天晴转多云，气温15-25度，适合出门。
            用户: 有什么好吃的餐厅推荐？
            Agent: 推荐您尝试全聚德烤鸭、鼎泰丰小笼包、海底捞火锅等。
            用户: 那附近有什么景点？
            Agent: 您可以去故宫、天坛、颐和园，都是北京的著名景点。
            """;
        
        // 通过完整 Prompt 来测试历史对话优化
        String fullPrompt = "【历史对话】\n" + history + "\n\n【当前问题】\n帮我规划一下行程";
        
        String optimized = keywordExtractionService.extractKeywordsFromFullPrompt(fullPrompt);
        
        System.out.println("\n=== 历史对话优化测试 ===");
        System.out.println("原始历史长度: " + history.length() + " 字符");
        
        // 提取优化后的历史部分
        int historyStart = optimized.indexOf("【历史对话】");
        int currentStart = optimized.indexOf("【当前问题】");
        if (historyStart != -1 && currentStart != -1) {
            String optimizedHistory = optimized.substring(historyStart, currentStart).trim();
            System.out.println("优化后历史长度: " + optimizedHistory.length() + " 字符");
            System.out.println("\n优化后的历史对话:\n" + optimizedHistory);
            
            double reduction = (1.0 - (double) optimizedHistory.length() / history.length()) * 100;
            System.out.printf("历史对话 Token 节省: %.1f%%\n", reduction);
            
            // 验证关键信息保留
            assertTrue(optimizedHistory.contains("用户:"), "应保留用户发言标记");
            assertTrue(optimizedHistory.contains("Agent:"), "应保留 Agent 回复标记");
            // 验证地点被提取
            assertTrue(optimizedHistory.contains("北京") || optimizedHistory.contains("地点:"), 
                "应保留地点信息");
        }
    }
}
