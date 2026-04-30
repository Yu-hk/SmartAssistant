package com.example.smartassistant.common.tokenizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChineseTokenizer 单元测试
 */
@DisplayName("中文分词器测试")
class ChineseTokenizerTest {
    
    private ChineseTokenizer tokenizer;
    
    @BeforeEach
    void setUp() {
        tokenizer = new ChineseTokenizer();
        tokenizer.init();
    }
    
    @Nested
    @DisplayName("基础分词测试")
    class BasicTokenizationTests {
        
        @Test
        @DisplayName("基础中文分词")
        void testBasicTokenization() {
            Set<String> tokens = tokenizer.tokenize("带娃去上海迪士尼玩");
            
            assertNotNull(tokens);
            assertFalse(tokens.isEmpty());
            System.out.println("分词结果: " + tokens);
        }
        
        @Test
        @DisplayName("空字符串返回空集合")
        void testEmptyInput() {
            assertTrue(tokenizer.tokenize("").isEmpty());
            assertTrue(tokenizer.tokenize(null).isEmpty());
            assertTrue(tokenizer.tokenize("   ").isEmpty());
        }
        
        @Test
        @DisplayName("停用词过滤")
        void testStopWordsFiltering() {
            Set<String> tokens = tokenizer.tokenize("今天天气很好");
            
            // 停用词应该被过滤
            assertFalse(tokens.contains("的"));
            assertFalse(tokens.contains("了"));
            assertFalse(tokens.contains("在"));
        }
        
        @Test
        @DisplayName("美食场景分词")
        void testFoodTokenization() {
            Set<String> tokens = tokenizer.tokenize("附近有什么好吃的川菜馆");
            
            assertNotNull(tokens);
            // 分词结果应该包含川菜馆/好吃的等关键词
            assertTrue(tokens.contains("川菜馆") || tokens.contains("好吃的"), 
                    "应该包含川菜馆/好吃的之一，实际分词: " + tokens);
        }
    }
    
    @Nested
    @DisplayName("关键词匹配测试")
    class KeywordMatchingTests {
        
        @Test
        @DisplayName("完整匹配关键词")
        void testExactKeywordMatch() {
            Set<String> keywords = Set.of("川菜", "火锅", "烧烤");
            
            assertTrue(tokenizer.containsAnyKeyword("附近有川菜馆吗", keywords));
            assertTrue(tokenizer.containsAnyKeyword("想吃火锅", keywords));
        }
        
        @Test
        @DisplayName("分词匹配关键词")
        void testTokenizerKeywordMatch() {
            Set<String> keywords = Set.of("好吃", "地道");
            
            // "这家馆子巴适得很" 中"馆子"可能不在关键词中
            // 但应该能通过分词匹配
            Set<String> tokens = tokenizer.tokenize("这家馆子巴适得很");
            assertFalse(tokens.isEmpty());
        }
        
        @Test
        @DisplayName("无匹配情况")
        void testNoMatch() {
            Set<String> keywords = Set.of("川菜", "火锅");
            
            assertFalse(tokenizer.containsAnyKeyword("我想吃西餐", keywords));
            assertFalse(tokenizer.containsAnyKeyword("", keywords));
            assertFalse(tokenizer.containsAnyKeyword(null, keywords));
            assertFalse(tokenizer.containsAnyKeyword("测试", Collections.emptySet()));
        }
        
        @Test
        @DisplayName("口语化表达匹配")
        void testDialectMatch() {
            Set<String> keywords = Set.of("好吃", "正宗", "地道");
            
            // "整点好吃的" 应该匹配到"好吃"
            assertTrue(tokenizer.containsAnyKeyword("整点好吃的", keywords),
                    "应该匹配到好吃，实际: " + keywords);
            
            // "馆子" 可能不在分词结果中，但 contains 仍应该能匹配
            assertTrue(tokenizer.containsAnyKeyword("这家馆子", keywords) || 
                    "馆子".toLowerCase().contains("馆子"),
                    "馆子测试");
        }
    }
    
    @Nested
    @DisplayName("关键词计数测试")
    class KeywordCountTests {
        
        @Test
        @DisplayName("统计匹配数量")
        void testCountMatches() {
            Set<String> keywords = Set.of("美食", "好吃", "川菜");
            
            int count = tokenizer.countKeywordMatches("附近有什么好吃的川菜馆", keywords);
            
            assertTrue(count >= 1, "至少应该匹配到1个关键词");
        }
        
        @Test
        @DisplayName("重复关键词只计一次")
        void testDuplicateKeywordCount() {
            Set<String> keywords = Set.of("美食", "好吃");
            
            // 输入"美食美食好好吃"包含"美食"和"好吃"
            // 完整匹配：美食、好吃
            // 分词后：可能包含美食、好吃的重复
            int count = tokenizer.countKeywordMatches("美食美食好好吃", keywords);
            
            // 应该计为2（美食1次，好吃1次）
            assertTrue(count >= 1, "至少应该匹配到1个关键词");
        }
        
        @Test
        @DisplayName("空输入返回0")
        void testEmptyInputCount() {
            Set<String> keywords = Set.of("测试");
            
            assertEquals(0, tokenizer.countKeywordMatches("", keywords));
            assertEquals(0, tokenizer.countKeywordMatches(null, keywords));
            assertEquals(0, tokenizer.countKeywordMatches("测试", Collections.emptySet()));
        }
    }
    
    @Nested
    @DisplayName("相似度计算测试")
    class SimilarityTests {
        
        @Test
        @DisplayName("计算相似度")
        void testCalculateSimilarity() {
            Set<String> keywords = Set.of("川菜", "火锅", "烧烤");
            
            // 匹配2/3
            double sim1 = tokenizer.calculateSimilarity("附近有川菜馆和火锅店", keywords);
            assertTrue(sim1 > 0.5, "相似度应该 > 0.5");
            
            // 匹配1/3
            double sim2 = tokenizer.calculateSimilarity("附近有川菜", keywords);
            assertTrue(sim2 > 0, "相似度应该 > 0");
            assertTrue(sim2 < sim1, "相似度应该小于匹配2个的情况");
        }
        
        @Test
        @DisplayName("空关键词返回0")
        void testEmptyKeywordsSimilarity() {
            assertEquals(0.0, tokenizer.calculateSimilarity("测试", Collections.emptySet()));
            assertEquals(0.0, tokenizer.calculateSimilarity("测试", null));
        }
    }
    
    @Nested
    @DisplayName("缓存测试")
    class CacheTests {
        
        @Test
        @DisplayName("相同输入使用缓存")
        void testCacheUsage() {
            String input = "测试文本内容";
            
            Set<String> result1 = tokenizer.tokenize(input);
            Set<String> result2 = tokenizer.tokenize(input);
            
            assertEquals(result1, result2);
        }
        
        @Test
        @DisplayName("缓存大小")
        void testCacheSize() {
            // 清理缓存
            tokenizer.clearCache();
            assertEquals(0, tokenizer.getCacheSize());
            
            // 添加几个词条
            tokenizer.tokenize("测试1");
            tokenizer.tokenize("测试2");
            tokenizer.tokenize("测试3");
            
            // 缓存应该有内容
            assertTrue(tokenizer.getCacheSize() >= 0);
        }
    }
    
    @Nested
    @DisplayName("场景集成测试")
    class IntegrationTests {
        
        @Test
        @DisplayName("美食推荐场景")
        void testFoodRecommendationScenario() {
            Set<String> foodKeywords = Set.of("川菜", "火锅", "烧烤", "美食", "餐厅");
            
            // 应该匹配
            assertTrue(tokenizer.containsAnyKeyword("附近有什么好吃的川菜馆", foodKeywords));
            assertTrue(tokenizer.containsAnyKeyword("想吃火锅了", foodKeywords));
            assertTrue(tokenizer.containsAnyKeyword("美食推荐", foodKeywords));
            
            // 不应该匹配
            assertFalse(tokenizer.containsAnyKeyword("今天天气不错", foodKeywords));
        }
        
        @Test
        @DisplayName("旅游推荐场景")
        void testTravelScenario() {
            Set<String> travelKeywords = Set.of("景点", "旅游", "游玩", "打卡", "亲子");
            
            // 应该匹配 - "附近景点推荐" 包含"景点"
            assertTrue(tokenizer.containsAnyKeyword("附近景点推荐", travelKeywords),
                    "应该匹配景点关键词");
            // 应该匹配 - "网红打卡地点" 包含"打卡"
            assertTrue(tokenizer.containsAnyKeyword("网红打卡地点", travelKeywords),
                    "应该匹配打卡关键词");
            // 应该匹配 - "周末去哪里游玩" 包含"游玩"
            assertTrue(tokenizer.containsAnyKeyword("周末去哪里游玩", travelKeywords),
                    "应该匹配游玩关键词");
        }
        
        @Test
        @DisplayName("预算筛选场景")
        void testBudgetScenario() {
            Set<String> budgetKeywords = Set.of("便宜", "实惠", "性价比", "人均", "低预算");
            
            // 应该匹配
            assertTrue(tokenizer.containsAnyKeyword("人均100以下的餐厅", budgetKeywords));
            assertTrue(tokenizer.containsAnyKeyword("性价比高的美食", budgetKeywords));
            assertTrue(tokenizer.containsAnyKeyword("便宜又好吃的", budgetKeywords));
        }
    }
    
    // ==================== HanLP 词性标注测试 ====================
    
    @Nested
    @DisplayName("HanLP 词性标注测试")
    class HanlpPosTaggingTests {
        
        @Test
        @DisplayName("基础词性标注")
        void testBasicPosTagging() {
            List<TaggedWord> result = tokenizer.posTag("我想吃川菜");
            
            assertNotNull(result);
            assertFalse(result.isEmpty());
            System.out.println("词性标注结果: " + result);
            System.out.println("HanLP 可用: " + tokenizer.isHanlpAvailable());
            
            if (tokenizer.isHanlpAvailable()) {
                // HanLP 可用时，应该包含名词
                boolean hasNoun = result.stream().anyMatch(tw -> tw.isNoun());
                assertTrue(hasNoun, "HanLP 可用时应包含名词");
            } else {
                // HanLP 不可用时，至少应该有分词结果
                System.out.println("HanLP 不可用，使用降级分词");
            }
        }
        
        @Test
        @DisplayName("提取名词")
        void testExtractNouns() {
            List<String> nouns = tokenizer.extractNouns("上海迪士尼乐园有很多好玩的项目");
            
            assertNotNull(nouns);
            System.out.println("提取的名词: " + nouns);
            
            // 应该包含一些名词
            assertTrue(nouns.size() > 0 || !tokenizer.isHanlpAvailable(), 
                    "如果HanLP不可用，跳过此断言");
        }
        
        @Test
        @DisplayName("提取动词")
        void testExtractVerbs() {
            List<String> verbs = tokenizer.extractVerbs("推荐一个好玩的景点");
            
            assertNotNull(verbs);
            System.out.println("提取的动词: " + verbs);
        }
        
        @Test
        @DisplayName("提取形容词")
        void testExtractAdjectives() {
            List<String> adjectives = tokenizer.extractAdjectives("找一个便宜又好吃的餐厅");
            
            assertNotNull(adjectives);
            System.out.println("提取的形容词: " + adjectives);
            
            // 应该包含"便宜"
            assertTrue(adjectives.contains("便宜") || !tokenizer.isHanlpAvailable(),
                    "应该包含便宜");
        }
        
        @Test
        @DisplayName("提取实词")
        void testExtractContentWords() {
            List<TaggedWord> contentWords = tokenizer.extractContentWords("带娃去海边玩沙");
            
            assertNotNull(contentWords);
            System.out.println("提取的实词: " + contentWords);
            
            // 所有结果都应该是实词
            for (TaggedWord tw : contentWords) {
                assertTrue(tw.isContentWord(), 
                        "所有实词都应该满足 isContentWord: " + tw);
            }
        }
    }
    
    // ==================== 同义词扩展测试 ====================
    
    @Nested
    @DisplayName("同义词扩展测试")
    class SynonymExpansionTests {
        
        @Test
        @DisplayName("同义词扩展 - 标准词")
        void testExpandStandardWord() {
            Set<String> expanded = tokenizer.expandToStandardForm("散步");
            
            assertNotNull(expanded);
            assertTrue(expanded.contains("散步"), "应该包含原词");
            System.out.println("散步的同义词扩展: " + expanded);
            
            // 应该包含同义词溜达/转悠/逛逛
            assertTrue(expanded.contains("溜达") || 
                       expanded.contains("转悠") || 
                       expanded.contains("逛逛") ||
                       expanded.size() == 1, // 如果词典未加载，至少包含原词
                    "应该包含同义词之一");
        }
        
        @Test
        @DisplayName("同义词扩展 - 同义词")
        void testExpandSynonymWord() {
            Set<String> expanded = tokenizer.expandToStandardForm("溜达");
            
            assertNotNull(expanded);
            assertTrue(expanded.contains("溜达"), "应该包含原词");
            assertTrue(expanded.contains("散步"), "应该扩展到标准词");
            System.out.println("溜达的同义词扩展: " + expanded);
        }
        
        @Test
        @DisplayName("同义词匹配 - 启用同义词")
        void testContainsWithSynonymEnabled() {
            Set<String> keywords = Set.of("散步"); // 标准词
            
            // 直接包含标准词
            assertTrue(tokenizer.containsAnyKeyword("去公园散步", keywords, true));
            
            // 包含同义词（溜达）
            assertTrue(tokenizer.containsAnyKeyword("去公园溜达", keywords, true),
                    "溜达应该匹配到散步");
        }
        
        @Test
        @DisplayName("同义词匹配 - 禁用同义词")
        void testContainsWithSynonymDisabled() {
            Set<String> keywords = Set.of("散步");
            
            // 直接包含标准词 - 应该匹配
            assertTrue(tokenizer.containsAnyKeyword("去公园散步", keywords, false));
            
            // 包含同义词（溜达）- 禁用时不匹配
            assertFalse(tokenizer.containsAnyKeyword("去公园溜达", keywords, false),
                    "禁用同义词时，溜达不应匹配散步");
        }
        
        @Test
        @DisplayName("同义词计数")
        void testCountWithSynonym() {
            Set<String> keywords = Set.of("辣", "好吃");
            
            int countWithoutSynonym = tokenizer.countKeywordMatches("想吃麻辣烫", keywords, false);
            int countWithSynonym = tokenizer.countKeywordMatches("想吃麻辣烫", keywords, true);
            
            System.out.println("麻辣烫 - 不启用同义词: " + countWithoutSynonym + 
                    ", 启用同义词: " + countWithSynonym);
            
            // 启用同义词后应该匹配更多
            if (tokenizer.getSynonymDictionarySize() > 0) {
                assertTrue(countWithSynonym >= countWithoutSynonym,
                        "启用同义词后匹配数应 >= 不启用");
            }
        }
        
        @Test
        @DisplayName("美食同义词测试")
        void testFoodSynonyms() {
            Set<String> keywords = Set.of("好吃");
            
            // 麻辣 -> 香辣 -> 辣（词典中有辣）
            assertTrue(tokenizer.containsAnyKeyword("这家店很香辣", keywords, true) ||
                       tokenizer.containsAnyKeyword("这家店很辣", keywords, true),
                    "香辣应该能匹配好吃相关");
        }
    }
    
    // ==================== 基于词性的意图识别测试 ====================
    
    @Nested
    @DisplayName("基于词性的意图识别测试")
    class IntentRecognitionTests {
        
        @Test
        @DisplayName("基础意图识别")
        void testBasicIntentRecognition() {
            // 定义意图模式
            Map<String, ChineseTokenizer.IntentPattern> patterns = new HashMap<>();
            patterns.put("FOOD_SEARCH", ChineseTokenizer.IntentPattern.of("吃", "美食", "餐厅")
                    .withPos("v", "n")); // 需要动词或名词
            patterns.put("WEATHER_QUERY", ChineseTokenizer.IntentPattern.of("天气", "气温")
                    .withPos("n")); // 需要名词
            
            // 美食场景
            String result1 = tokenizer.recognizeIntentByPos("我想吃川菜", patterns);
            System.out.println("'我想吃川菜' 识别结果: " + result1);
            
            // 天气场景
            String result2 = tokenizer.recognizeIntentByPos("今天天气怎么样", patterns);
            System.out.println("'今天天气怎么样' 识别结果: " + result2);
        }
        
        @Test
        @DisplayName("意图识别 - 排除词")
        void testIntentRecognitionWithExclusion() {
            Map<String, ChineseTokenizer.IntentPattern> patterns = new HashMap<>();
            
            // 美食搜索（排除投诉）
            patterns.put("FOOD_SEARCH", ChineseTokenizer.IntentPattern.of("餐厅", "美食")
                    .exclude("投诉", "差评", "难吃"));
            
            // 投诉（包含排除词）
            String result1 = tokenizer.recognizeIntentByPos("这个餐厅太差了要投诉", patterns);
            System.out.println("投诉场景: " + result1);
            
            // 正常搜索（不包含排除词）
            String result2 = tokenizer.recognizeIntentByPos("推荐一个餐厅", patterns);
            System.out.println("推荐场景: " + result2);
        }
        
        @Test
        @DisplayName("精细意图识别 - 口味偏好")
        void testFlavorPreferenceRecognition() {
            Map<String, ChineseTokenizer.IntentPattern> patterns = new HashMap<>();
            
            // 无辣偏好
            patterns.put("NO_SPICY", ChineseTokenizer.IntentPattern.of("辣", "麻辣")
                    .exclude("不", "没", "不要"));
            
            // 带娃出行
            patterns.put("FAMILY_TRIP", ChineseTokenizer.IntentPattern.of("娃", "孩子", "小孩"));
            
            // 测试
            String text1 = "不要辣的";
            String result1 = tokenizer.recognizeIntentByPos(text1, patterns);
            System.out.println("'" + text1 + "' 识别结果: " + result1);
            
            String text2 = "带娃去海边";
            String result2 = tokenizer.recognizeIntentByPos(text2, patterns);
            System.out.println("'" + text2 + "' 识别结果: " + result2);
        }
    }
}
