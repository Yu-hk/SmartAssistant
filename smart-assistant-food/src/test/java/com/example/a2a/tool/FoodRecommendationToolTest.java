/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tool;

import com.example.smartassistant.knowledge.SpecialtyCuisineKnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 美食推荐工具单元测试
 * 重点测试地点识别和标准化功能
 */
class FoodRecommendationToolTest {

    private FoodRecommendationTool foodTool;
    private SpecialtyCuisineKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new SpecialtyCuisineKnowledgeBase();
        foodTool = new FoodRecommendationTool(knowledgeBase);
    }

    @Test
    @DisplayName("测试1: querySpecialtyCuisine - 查询河北特色菜")
    void testQuerySpecialtyCuisine_Hebei() {
        String result = foodTool.querySpecialtyCuisine("河北");
        
        assertNotNull(result, "结果不应为null");
        assertFalse(result.isEmpty(), "结果不应为空");
        assertTrue(result.contains("河北"), "结果应包含'河北'");
        
        // 验证是否返回了河北特色菜
        boolean hasHebeiDish = result.contains("驴肉火烧") || 
                               result.contains("金凤扒鸡") ||
                               result.contains("正定崩肝") ||
                               result.contains("缸炉烧饼");
        assertTrue(hasHebeiDish, "应包含河北特色菜（驴肉火烧、金凤扒鸡等）");
        
        System.out.println("=== 河北特色菜查询结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试2: querySpecialtyCuisine - 查询河北省（带后缀）")
    void testQuerySpecialtyCuisine_HebeiWithSuffix() {
        String result = foodTool.querySpecialtyCuisine("河北省");
        
        assertNotNull(result, "结果不应为null");
        assertFalse(result.isEmpty(), "结果不应为空");
        
        // 应该能正确识别并返回河北特色菜
        boolean hasHebeiDish = result.contains("驴肉火烧") || 
                               result.contains("河北");
        assertTrue(hasHebeiDish, "即使输入'河北省'也应返回河北特色菜");
        
        System.out.println("=== 河北省（带后缀）查询结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试3: querySpecialtyCuisine - 查询成都特色菜")
    void testQuerySpecialtyCuisine_Chengdu() {
        String result = foodTool.querySpecialtyCuisine("成都");
        
        assertNotNull(result);
        assertTrue(result.contains("成都"), "结果应包含'成都'");
        assertTrue(result.contains("麻婆豆腐") || result.contains("火锅"), 
                   "应包含成都特色菜");
        
        System.out.println("=== 成都特色菜查询结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试4: querySpecialtyCuisine - 查询成都市（带后缀）")
    void testQuerySpecialtyCuisine_ChengduWithSuffix() {
        String result = foodTool.querySpecialtyCuisine("成都市");
        
        assertNotNull(result);
        // 应该能正确识别并返回成都特色菜
        assertTrue(result.contains("麻婆豆腐") || result.contains("成都"), 
                   "即使输入'成都市'也应返回成都特色菜");
        
        System.out.println("=== 成都市（带后缀）查询结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试5: querySpecialtyCuisine - 查询四川特色菜")
    void testQuerySpecialtyCuisine_Sichuan() {
        String result = foodTool.querySpecialtyCuisine("四川");
        
        assertNotNull(result);
        assertTrue(result.contains("四川"), "结果应包含'四川'");
        assertTrue(result.contains("麻婆豆腐") || result.contains("回锅肉"), 
                   "应包含四川特色菜");
        
        System.out.println("=== 四川特色菜查询结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试6: querySpecialtyCuisine - 查询麻辣口味")
    void testQuerySpecialtyCuisine_SpicyTaste() {
        String result = foodTool.querySpecialtyCuisine("麻辣");
        
        assertNotNull(result);
        assertTrue(result.contains("麻辣"), "结果应包含'麻辣'");
        assertFalse(result.contains("未找到"), "不应返回未找到");
        
        System.out.println("=== 麻辣口味查询结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试7: querySpecialtyCuisine - 模糊搜索烤鸭")
    void testQuerySpecialtyCuisine_SearchDuck() {
        String result = foodTool.querySpecialtyCuisine("烤鸭");
        
        assertNotNull(result);
        assertTrue(result.contains("烤鸭") || result.contains("北京"), 
                   "应找到烤鸭相关信息");
        
        System.out.println("=== 烤鸭搜索结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试8: querySpecialtyCuisine - 空查询处理")
    void testQuerySpecialtyCuisine_EmptyQuery() {
        String result = foodTool.querySpecialtyCuisine("");
        
        assertNotNull(result);
        assertTrue(result.contains("请提供查询关键词"), 
                   "空查询应提示用户提供关键词");
        
        System.out.println("=== 空查询处理结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试9: querySpecialtyCuisine - null查询处理")
    void testQuerySpecialtyCuisine_NullQuery() {
        String result = foodTool.querySpecialtyCuisine(null);
        
        assertNotNull(result);
        assertTrue(result.contains("请提供查询关键词"), 
                   "null查询应提示用户提供关键词");
        
        System.out.println("=== null查询处理结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试10: querySpecialtyCuisine - 查询不存在的地点")
    void testQuerySpecialtyCuisine_NonExistentLocation() {
        String result = foodTool.querySpecialtyCuisine("火星");
        
        assertNotNull(result);
        // 应该通过模糊搜索返回一些结果或提示未找到
        System.out.println("=== 不存在地点查询结果 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试11: listAvailableCities - 获取城市列表")
    void testListAvailableCities() {
        String result = foodTool.listAvailableCities();
        
        assertNotNull(result);
        assertTrue(result.contains("可查询的城市列表"), "应包含标题");
        assertTrue(result.contains("北京"), "应包含北京");
        assertTrue(result.contains("成都"), "应包含成都");
        assertTrue(result.contains("共"), "应包含数量统计");
        
        System.out.println("=== 城市列表 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试12: listAvailableTastes - 获取口味分类")
    void testListAvailableTastes() {
        String result = foodTool.listAvailableTastes();
        
        assertNotNull(result);
        assertTrue(result.contains("可查询的口味分类"), "应包含标题");
        assertTrue(result.contains("麻辣"), "应包含麻辣");
        assertTrue(result.contains("清淡"), "应包含清淡");
        
        System.out.println("=== 口味分类 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试13: 地点标准化 - 反射测试normalizeLocation方法")
    void testNormalizeLocation() throws Exception {
        // 使用反射访问私有方法
        Method method = FoodRecommendationTool.class.getDeclaredMethod(
            "normalizeLocation", String.class);
        method.setAccessible(true);
        
        // 测试各种输入格式
        assertEquals("河北", method.invoke(foodTool, "河北"));
        assertEquals("河北", method.invoke(foodTool, "河北省"));
        assertEquals("成都", method.invoke(foodTool, "成都"));
        assertEquals("成都", method.invoke(foodTool, "成都市"));
        assertEquals("北京", method.invoke(foodTool, "北京"));
        assertEquals("北京", method.invoke(foodTool, "北京市"));
        assertEquals("广西", method.invoke(foodTool, "广西壮族自治区"));
        
        System.out.println("=== 地点标准化测试通过 ===");
        System.out.println("河北 → " + method.invoke(foodTool, "河北"));
        System.out.println("河北省 → " + method.invoke(foodTool, "河北省"));
        System.out.println("成都 → " + method.invoke(foodTool, "成都"));
        System.out.println("成都市 → " + method.invoke(foodTool, "成都市"));
    }

    @Test
    @DisplayName("测试14: 综合场景 - 模拟用户询问河北美食")
    void testComprehensiveScenario_HebeiTourism() {
        // 模拟用户输入："我要去河北旅游，帮我推荐当地的美食"
        // AI应该提取"河北"并调用querySpecialtyCuisine("河北")
        String result = foodTool.querySpecialtyCuisine("河北");
        
        assertNotNull(result);
        assertTrue(result.contains("河北"), "应识别为河北");
        assertTrue(result.contains("特色菜"), "应返回特色菜信息");
        
        // 验证返回了具体的河北菜品
        boolean hasSpecificDish = result.contains("驴肉火烧") ||
                                  result.contains("金凤扒鸡") ||
                                  result.contains("正定崩肝") ||
                                  result.contains("缸炉烧饼");
        assertTrue(hasSpecificDish, "应包含具体的河北特色菜品");
        
        System.out.println("=== 综合场景：河北旅游美食推荐 ===");
        System.out.println(result);
    }

    @Test
    @DisplayName("测试15: 知识库数据完整性 - 验证河北数据已添加")
    void testKnowledgeBaseIntegrity_HebeiData() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> hebeiDishes = 
            knowledgeBase.getDishesByProvince("河北");
        
        assertNotNull(hebeiDishes);
        assertFalse(hebeiDishes.isEmpty(), "河北应该有特色菜数据");
        assertTrue(hebeiDishes.size() >= 4, "河北至少应有4道特色菜");
        
        // 验证具体菜品存在
        boolean hasLvrouHuoshao = hebeiDishes.stream()
            .anyMatch(d -> d.name().equals("驴肉火烧"));
        assertTrue(hasLvrouHuoshao, "应包含驴肉火烧");
        
        System.out.println("=== 河北知识库数据验证 ===");
        System.out.println("河北特色菜数量: " + hebeiDishes.size());
        hebeiDishes.forEach(dish -> 
            System.out.println("  - " + dish.name() + " (" + dish.city() + ")"));
    }
}
