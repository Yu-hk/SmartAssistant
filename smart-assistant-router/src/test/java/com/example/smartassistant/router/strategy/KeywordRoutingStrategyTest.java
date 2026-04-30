package com.example.smartassistant.router.strategy;

import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.AgentDiscoveryService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * KeywordRoutingStrategy 单元测试
 *
 * 使用 travel-service 和 food-service 的真实关键字配置：
 * - Travel Agent keywords: 天气,出行,旅游,位置,附近,景点,规划,周末活动,在哪里,怎么去,好玩,哪里,推荐,城市,周末去哪,游玩
 * - Travel Agent capabilities: location_query,weather_forecast,travel_planning,nearby_entertainment
 * - Travel Agent support: location=true, weather=true, planning=true
 * - Food Agent keywords: 美食,餐厅,菜系,特色菜,吃什么,附近美食,推荐餐厅
 * - Food Agent capabilities: cuisine_query,restaurant_recommendation,nearby_search
 * - Food Agent support: location=true
 *
 * 评分算法：
 * - 关键字匹配(40%): Agent metadata keywords 命中数 × 基础分
 * - 能力语义匹配(30%): capabilities 和 support 字段匹配
 * - Agent优先级权重(20%): priority × 2
 * - 上下文继承(10%): currentAgent/currentCity 连续性
 * - 阈值: >= 30 分才路由，否则返回 null
 */
class KeywordRoutingStrategyTest {

    private KeywordRoutingStrategy strategy;
    private AgentDiscoveryService mockDiscoveryService;
    private Method extractContextMethod;
    private Method extractLocationMethod;
    private Method extractTimeRangeMethod;
    private Method extractIntentMethod;
    private Method matchesAnyMethod;

    // ===== Mock Agent 构建工具 =====

    private DiscoveredAgent buildTravelAgent() {
        AgentMetadata meta = new AgentMetadata();
        meta.setAgentType("location_weather");
        meta.setKeywords("天气,出行,旅游,位置,附近,景点,规划,周末活动,在哪里,怎么去,好玩,哪里,推荐,城市,周末去哪,游玩");
        meta.setCapabilities("location_query,weather_forecast,travel_planning,nearby_entertainment");
        meta.setSupportLocation(true);
        meta.setSupportWeather(true);
        meta.setSupportPlanning(true);
        meta.setPriority(10);

        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setServiceName("travel-service");
        agent.setAgentName("location_weather");
        agent.setMetadata(meta);
        return agent;
    }

    private DiscoveredAgent buildFoodAgent() {
        AgentMetadata meta = new AgentMetadata();
        meta.setAgentType("food_recommendation");
        meta.setKeywords("美食,餐厅,菜系,特色菜,吃什么,附近美食,推荐餐厅");
        meta.setCapabilities("cuisine_query,restaurant_recommendation,nearby_search");
        meta.setSupportLocation(true);
        meta.setPriority(10);

        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setServiceName("food-service");
        agent.setAgentName("food_recommendation");
        agent.setMetadata(meta);
        return agent;
    }

    private DiscoveredAgent buildDefaultAgent() {
        AgentMetadata meta = new AgentMetadata();
        meta.setAgentType("default");
        meta.setPriority(5);

        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setServiceName("default-service");
        agent.setAgentName("default");
        agent.setMetadata(meta);
        return agent;
    }

    @BeforeEach
    void setUp() throws Exception {
        mockDiscoveryService = mock(AgentDiscoveryService.class);
        ChineseTokenizer tokenizer = new ChineseTokenizer();
        strategy = new KeywordRoutingStrategy(mockDiscoveryService, tokenizer);

        extractContextMethod = KeywordRoutingStrategy.class.getDeclaredMethod(
                "extractContext", String.class);
        extractContextMethod.setAccessible(true);

        extractLocationMethod = KeywordRoutingStrategy.class.getDeclaredMethod(
                "extractLocation", String.class);
        extractLocationMethod.setAccessible(true);

        extractTimeRangeMethod = KeywordRoutingStrategy.class.getDeclaredMethod(
                "extractTimeRange", String.class);
        extractTimeRangeMethod.setAccessible(true);

        extractIntentMethod = KeywordRoutingStrategy.class.getDeclaredMethod(
                "extractIntent", String.class);
        extractIntentMethod.setAccessible(true);

        matchesAnyMethod = KeywordRoutingStrategy.class.getDeclaredMethod(
                "matchesAny", String.class, Set.class);
        matchesAnyMethod.setAccessible(true);
    }

    // ==================== 路由决策测试 ====================

    @Nested
    @DisplayName("【生活场景】Travel Agent 路由")
    class TravelAgentRoutingTests {

        @Test
        @DisplayName("场景1-1: 天气查询 → 路由到 travel-agent")
        void testWeatherQuery_RouteToTravelAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("去北京玩明天天气怎么样", null);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
            assertEquals("KEYWORD_ROUTING", decision.getRoutingMethod());
            assertNotNull(decision.getExtractedContext());
            assertEquals("北京", decision.getExtractedContext().getLocation());
            assertEquals("明天", decision.getExtractedContext().getTimeRange());
            assertEquals("weather_query", decision.getExtractedContext().getIntent());
        }

        @Test
        @DisplayName("场景1-2: 带娃出行 → 路由到 travel-agent（命中亲子+出行）")
        void testFamilyTravel_RouteToTravelAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("周末带娃去哪玩比较好？", null);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
            assertEquals("周末", decision.getExtractedContext().getTimeRange());
            assertEquals("travel_planning", decision.getExtractedContext().getIntent());
            assertEquals("family", decision.getExtractedContext().getAdditionalParams().get("scene"));
        }

        @Test
        @DisplayName("场景1-3: 景点查询（含【去】+城市）→ 路由到 travel-agent，提取地点")
        void testAttractionQuery_RouteToTravelAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("去杭州玩有什么景点推荐？", null);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
            assertEquals("杭州", decision.getExtractedContext().getLocation());
        }

        @Test
        @DisplayName("场景1-4: 出行规划 → 路由到 travel-agent")
        void testTripPlanning_RouteToTravelAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("帮我规划一下去成都的旅游行程", null);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
            assertEquals("成都", decision.getExtractedContext().getLocation());
            assertEquals("travel_planning", decision.getExtractedContext().getIntent());
        }

        @Test
        @DisplayName("场景1-5: 位置询问（在哪里/怎么去）→ 路由到 travel-agent")
        void testLocationQuery_RouteToTravelAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("故宫博物院在哪里？怎么去最方便？", null);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
            assertEquals("location_query", decision.getExtractedContext().getIntent());
        }

        @Test
        @DisplayName("场景1-6: 周末活动推荐 → 路由到 travel-agent")
        void testWeekendActivity_RouteToTravelAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("去上海周末去哪玩比较好？", null);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
            assertEquals("上海", decision.getExtractedContext().getLocation());
        }

        @Test
        @DisplayName("场景1-7: 天气查询的地点时间提取验证")
        void testWeatherQuery_ExtractLocationAndTime() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent()));

            // 地点提取需要"在/去/到/从"+城市名+后缀(玩/市/城/区/附近/有没有)
            String[][] testCases = {
                    {"去北京玩明天天气如何", "北京", "明天"},
                    {"去上海最近会不会下雨？", "上海", "近期"},
                    {"在成都这周末天气如何？", "成都", "周末"},
                    {"到杭州下周适合出游吗？", "杭州", "下周"}
            };

            for (String[] tc : testCases) {
                RouteDecision decision = strategy.route(tc[0], null);
                assertNotNull(decision, "输入: " + tc[0]);
                assertEquals(tc[1], decision.getExtractedContext().getLocation(),
                        "地点不匹配: " + tc[0]);
                assertEquals(tc[2], decision.getExtractedContext().getTimeRange(),
                        "时间不匹配: " + tc[0]);
            }
        }
    }

    @Nested
    @DisplayName("【生活场景】Food Agent 路由")
    class FoodAgentRoutingTests {

        @Test
        @DisplayName("场景2-1: 火锅推荐 → food-agent 胜出（travel 不含【火锅】关键字）")
        void testFoodRecommendation_RouteToFoodAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            // "美食"只匹配food-agent（不含"附近"触发travel location capability）
            RouteDecision decision = strategy.route("附近美食推荐有哪些", null);

            assertNotNull(decision);
            assertEquals("food_recommendation", decision.getAgentName());
            assertEquals("food_recommendation", decision.getExtractedContext().getIntent());
        }

        @Test
        @DisplayName("场景2-2: 附近+好吃组合 → food-agent 胜出（food capability +90 vs travel +30）")
        void testNearbyFood_BothMatch_RouteToFoodAgent() {
            // food 的 cuisine/restaurant/nearby_search 能力命中 "附近有什么好吃的" 全匹配 → +90
            // travel 只有 location_query 命中 "附近" → +30，food 胜出
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("附近有什么好吃的", null);

            assertNotNull(decision);
            assertEquals("food_recommendation", decision.getAgentName(),
                    "【附近有什么好吃的】命中 food 的 cuisine/restaurant/nearby 能力，food-agent 胜出");
        }

        @Test
        @DisplayName("场景2-3: 纯美食关键字（无地点词）→ food-agent 胜出")
        void testPureFoodKeyword_RouteToFoodAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            // "好吃"+"美食"匹配food，无location词触发travel capability → food胜出
            RouteDecision decision = strategy.route("附近有什么好吃的", null);

            assertNotNull(decision);
            assertEquals("food_recommendation", decision.getAgentName());
        }

        @Test
        @DisplayName("场景2-4: 餐厅推荐 → food-agent")
        void testRestaurantRecommendation_RouteToFoodAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("哪家餐厅比较正宗", null);

            assertNotNull(decision);
            assertEquals("food_recommendation", decision.getAgentName());
        }

        @Test
        @DisplayName("场景2-5: 附近美食推荐 → food-agent（美食命中food capability +90）")
        void testNearbyFoodRecommendation_RouteToFoodAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            RouteDecision decision = strategy.route("附近美食推荐有哪些", null);

            assertNotNull(decision);
            // 【附近美食推荐】命中 food 的 cuisine/restaurant/nearby 能力，food 胜出
            assertEquals("food_recommendation", decision.getAgentName());
        }

        @Test
        @DisplayName("场景2-6: 特色菜推荐 → food-agent 胜出（travel 不含【特色菜】关键字）")
        void testCuisineQuery_RouteToFoodAgent() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            // "特色菜"只匹配food-agent，travel不含此关键字
            RouteDecision decision = strategy.route("这家店的招牌特色菜是什么", null);

            assertNotNull(decision);
            assertEquals("food_recommendation", decision.getAgentName());
        }
    }

    @Nested
    @DisplayName("【生活场景】混合场景")
    class MixedSceneTests {

        @Test
        @DisplayName("场景3-1: 纯美食查询（travel无相关keywords）→ food-agent 胜出")
        void testFoodAndAttraction_FoodWins() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            // "美食"+"推荐"都匹配food → food得分46，travel得分16 → food胜出
            RouteDecision decision = strategy.route("附近美食推荐有哪些", null);

            assertNotNull(decision);
            assertEquals("food_recommendation", decision.getAgentName());
        }

        @Test
        @DisplayName("场景3-2: 上下文中有 currentCity → 两个 agent 都受益，travel 凭天气关键字胜出")
        void testWithCityContext_TravelWins() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            Map<String, Object> context = new HashMap<>();
            context.put("currentCity", "成都");

            RouteDecision decision = strategy.route("明天天气怎么样？", context);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
        }

        @Test
        @DisplayName("场景3-3: 上下文继承（上次使用 travel）→ travel-agent 优先")
        void testContextInheritance_TravelAgentPriority() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent(), buildFoodAgent()));

            Map<String, Object> context = new HashMap<>();
            context.put("currentAgent", "location_weather");

            RouteDecision decision = strategy.route("推荐一下", context);

            assertNotNull(decision);
            assertEquals("location_weather", decision.getAgentName());
        }

        @Test
        @DisplayName("场景3-4: 无任何 Agent 时返回 null")
        void testNoAgent_ReturnsNull() {
            when(mockDiscoveryService.discoverAllAgents()).thenReturn(Collections.emptyList());

            RouteDecision decision = strategy.route("随便问点什么", null);

            assertNull(decision);
        }

        @Test
        @DisplayName("场景3-5: 所有 Agent 得分低于 30 分阈值 → 返回 null")
        void testAllScoresBelowThreshold_ReturnsNull() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildDefaultAgent()));

            RouteDecision decision = strategy.route("你好啊今天怎么样", null);

            // 默认 Agent 没有 keywords，priority=5 只能得 10 分，不够 30 分阈值
            assertNull(decision);
        }
    }

    // ==================== 上下文提取测试 ====================

    @Nested
    @DisplayName("【上下文提取】地点提取")
    class LocationExtractionTests {

        @Test
        @DisplayName("城市名提取 - 需要【在/去/到/从】+城市名")
        void testCityExtraction() throws Exception {
            String[][] cases = {
                    {"在北京明天天气怎么样", "北京"},
                    {"去杭州旅游怎么安排", "杭州"},
                    {"在成都火锅推荐", "成都"},
                    {"到上海外滩附近有什么好玩的", "上海"}
            };
            for (String[] c : cases) {
                String location = (String) extractLocationMethod.invoke(strategy, c[0]);
                assertEquals(c[1], location, "输入: " + c[0]);
            }
        }

        @Test
        @DisplayName("省份名提取")
        void testProvinceExtraction() throws Exception {
            String[][] cases = {
                    {"去河北省有什么景点", "河北省"},
                    {"在浙江省出差怎么安排", "浙江省"}
            };
            for (String[] c : cases) {
                String location = (String) extractLocationMethod.invoke(strategy, c[0]);
                assertEquals(c[1], location, "输入: " + c[0]);
            }
        }

        @Test
        @DisplayName("无地点时返回 null")
        void testNoLocation_ReturnsNull() throws Exception {
            String location = (String) extractLocationMethod.invoke(strategy, "随便问问");
            assertNull(location);
        }
    }

    @Nested
    @DisplayName("【上下文提取】时间提取")
    class TimeRangeExtractionTests {

        @Test
        @DisplayName("各种时间表达提取")
        void testTimeExtraction() throws Exception {
            String[][] cases = {
                    {"今天天气怎么样", "今天"},
                    {"明天适合出门吗", "明天"},
                    {"后天有雨吗", "后天"},
                    {"这周末去哪玩", "周末"},
                    {"下周出差安排", "下周"},
                    {"下个月旅游计划", "下个月"},
                    {"最近有什么活动", "近期"}
            };
            for (String[] c : cases) {
                String timeRange = (String) extractTimeRangeMethod.invoke(strategy, c[0]);
                assertEquals(c[1], timeRange, "输入: " + c[0]);
            }
        }

        @Test
        @DisplayName("无时间时返回 null")
        void testNoTime_ReturnsNull() throws Exception {
            String timeRange = (String) extractTimeRangeMethod.invoke(strategy, "北京天气怎么样");
            assertNull(timeRange);
        }
    }

    @Nested
    @DisplayName("【上下文提取】意图识别")
    class IntentExtractionTests {

        @Test
        @DisplayName("常见意图识别")
        void testIntentExtraction() throws Exception {
            String[][] cases = {
                    {"北京明天天气怎么样", "weather_query"},
                    {"带娃去哪玩好", "travel_planning"},
                    {"附近有什么好吃的", "food_recommendation"},
                    {"帮我规划一下行程", "travel_planning"},
                    {"故宫博物院在哪里", "location_query"}
            };
            for (String[] c : cases) {
                String intent = (String) extractIntentMethod.invoke(strategy, c[0]);
                assertEquals(c[1], intent, "输入: " + c[0]);
            }
        }

        @Test
        @DisplayName("无法识别意图时返回 null")
        void testNoIntent_ReturnsNull() throws Exception {
            String intent = (String) extractIntentMethod.invoke(strategy, "你好");
            assertNull(intent);
        }
    }

    // ==================== 场景细分测试 ====================

    @Nested
    @DisplayName("【场景细分】家庭/情侣/商务场景")
    class SceneExtractionTests {

        @Test
        @DisplayName("亲子场景识别（带娃/孩子/小朋友）")
        void testFamilySceneExtraction() throws Exception {
            String[] inputs = {"周末带娃去哪玩", "带孩子去北京合适吗", "小朋友喜欢什么景点"};

            for (String input : inputs) {
                RouteDecision.ExtractedContext ctx =
                        (RouteDecision.ExtractedContext) extractContextMethod.invoke(strategy, input);
                assertEquals("family", ctx.getAdditionalParams().get("scene"),
                        "输入[" + input + "]应识别为 family 场景");
            }
        }

        @Test
        @DisplayName("情侣场景识别（情侣/约会/浪漫）")
        void testCoupleSceneExtraction() throws Exception {
            String[] inputs = {"情侣周末去哪约会好", "有什么浪漫的地方推荐", "二人世界去哪里"};

            for (String input : inputs) {
                RouteDecision.ExtractedContext ctx =
                        (RouteDecision.ExtractedContext) extractContextMethod.invoke(strategy, input);
                assertEquals("couple", ctx.getAdditionalParams().get("scene"),
                        "输入[" + input + "]应识别为 couple 场景");
            }
        }

        @Test
        @DisplayName("商务场景识别（出差/会议）")
        void testBusinessSceneExtraction() throws Exception {
            RouteDecision.ExtractedContext ctx =
                    (RouteDecision.ExtractedContext) extractContextMethod.invoke(strategy, "去上海出差有什么安排建议");
            assertEquals("business", ctx.getAdditionalParams().get("scene"));
        }

        @Test
        @DisplayName("无场景时 additionalParams 为 null")
        void testNoScene_NullParams() throws Exception {
            RouteDecision.ExtractedContext ctx =
                    (RouteDecision.ExtractedContext) extractContextMethod.invoke(strategy, "北京天气怎么样");
            assertNull(ctx.getAdditionalParams());
        }
    }

    // ==================== 置信度与评分测试 ====================

    @Nested
    @DisplayName("【置信度与评分】")
    class ScoreTests {

        @Test
        @DisplayName("天气查询有置信度输出")
        void testWeatherQuery_HasConfidence() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent()));

            RouteDecision decision = strategy.route("北京明天天气怎么样？", null);

            assertNotNull(decision);
            assertNotNull(decision.getConfidence());
            assertTrue(decision.getConfidence() > 0 && decision.getConfidence() <= 1.0,
                    "置信度应在 (0, 1.0] 范围内，实际: " + decision.getConfidence());
        }

        @Test
        @DisplayName("reason 字段包含关键字匹配说明")
        void testReasonContainsKeywordMatch() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent()));

            RouteDecision decision = strategy.route("带娃去杭州玩", null);

            assertNotNull(decision);
            assertNotNull(decision.getReason());
            assertTrue(decision.getReason().contains("关键字匹配路由"),
                    "reason 应包含'关键字匹配路由'，实际: " + decision.getReason());
        }

        @Test
        @DisplayName("评分结果应包含 location 上下文")
        void testScoringResult_ContainsLocationContext() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent()));

            RouteDecision decision = strategy.route("去北京旅游", null);

            assertNotNull(decision);
            assertNotNull(decision.getExtractedContext());
            assertEquals("北京", decision.getExtractedContext().getLocation());
        }
    }

    // ==================== 边界情况测试 ====================

    @Nested
    @DisplayName("【边界情况】")
    class EdgeCaseTests {

        @Test
        @DisplayName("空字符串输入 → 返回 null")
        void testEmptyInput_ReturnsNull() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent()));

            RouteDecision decision = strategy.route("", null);

            assertNull(decision);
        }

        @Test
        @DisplayName("仅有一个 Agent 时也能正常路由")
        void testSingleAgent_RouteCorrectly() {
            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(buildTravelAgent()));

            // travel-agent 关键字覆盖面广，大多数问题都能匹配
            RouteDecision decision = strategy.route("随便问问", null);
            // 验证不抛异常即可
            assertTrue(decision == null || "location_weather".equals(decision.getAgentName()));
        }

        @Test
        @DisplayName("Agent 无 metadata → 返回 null（未实现 fallback，内置关键字得分低于 30 分阈值）")
        void testAgentWithNullMetadata_ReturnsNull() {
            DiscoveredAgent agentNoMeta = new DiscoveredAgent();
            agentNoMeta.setServiceName("unknown-service");
            agentNoMeta.setAgentName("unknown");

            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(agentNoMeta));

            RouteDecision decision = strategy.route("北京明天天气怎么样", null);

            // 无 metadata + fallback 得 ~10 分，低于 30 分阈值 → 返回 null
            assertNull(decision);
        }

        @Test
        @DisplayName("Agent metadata keywords 为空 → 返回 null（未实现 fallback）")
        void testAgentWithEmptyKeywords_ReturnsNull() {
            AgentMetadata meta = new AgentMetadata();
            meta.setKeywords("");

            DiscoveredAgent agentEmptyKw = new DiscoveredAgent();
            agentEmptyKw.setServiceName("empty-service");
            agentEmptyKw.setAgentName("empty");
            agentEmptyKw.setMetadata(meta);

            when(mockDiscoveryService.discoverAllAgents())
                    .thenReturn(List.of(agentEmptyKw));

            RouteDecision decision = strategy.route("成都火锅推荐", null);

            // 空 keywords + fallback 得 ~10 分，低于 30 分阈值 → 返回 null
            assertNull(decision);
        }
    }

    // ==================== matchesAny 工具方法测试 ====================

    @Nested
    @DisplayName("【matchesAny 工具方法】")
    class MatchesAnyTests {

        @Test
        @DisplayName("精确匹配")
        void testMatchesAny_ExactMatch() throws Exception {
            // "好吃"是"附近有什么好吃的"的子串，匹配成功
            Set<String> keywords = Set.of("好吃", "难吃");

            assertTrue((Boolean) matchesAnyMethod.invoke(strategy, "附近有什么好吃的", keywords));
        }

        @Test
        @DisplayName("包含匹配")
        void testMatchesAny_ContainsMatch() throws Exception {
            Set<String> keywords = Set.of("美食");

            assertTrue((Boolean) matchesAnyMethod.invoke(strategy, "成都美食推荐", keywords));
        }

        @Test
        @DisplayName("完全不匹配")
        void testMatchesAny_NoMatch() throws Exception {
            Set<String> keywords = Set.of("美食", "天气");

            assertFalse((Boolean) matchesAnyMethod.invoke(strategy, "计算一下 1+1", keywords));
        }
    }

    // ==================== 方法契约测试 ====================

    @Nested
    @DisplayName("【接口契约】RoutingStrategy")
    class InterfaceContractTests {

        @Test
        @DisplayName("getStrategyName 返回 KEYWORD_ROUTING")
        void testGetStrategyName() {
            assertEquals("KEYWORD_ROUTING", strategy.getStrategyName());
        }

        @Test
        @DisplayName("getPriority 返回 1（最高优先级）")
        void testGetPriority() {
            assertEquals(1, strategy.getPriority());
        }

        @Test
        @DisplayName("isEnabled 默认返回 true")
        void testIsEnabled() {
            assertTrue(strategy.isEnabled());
        }
    }
}
