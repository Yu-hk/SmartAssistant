/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 特色菜知识库单元测试
 */
class SpecialtyCuisineKnowledgeBaseTest {

    private SpecialtyCuisineKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new SpecialtyCuisineKnowledgeBase();
    }

    @Test
    @DisplayName("测试1: 按城市查询 - 北京特色菜")
    void testGetDishesByCity_Beijing() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.getDishesByCity("北京");

        assertNotNull(dishes);
        assertFalse(dishes.isEmpty(), "北京应该有特色菜");
        
        // 验证包含北京烤鸭
        boolean hasPekingDuck = dishes.stream()
                .anyMatch(d -> d.name().contains("烤鸭"));
        assertTrue(hasPekingDuck, "北京特色菜应包含烤鸭");

        System.out.println("=== 北京特色菜 ===");
        System.out.println(knowledgeBase.formatDishesList(dishes, "北京特色菜"));
    }

    @Test
    @DisplayName("测试2: 按省份查询 - 四川特色菜")
    void testGetDishesByProvince_Sichuan() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.getDishesByProvince("四川");

        assertNotNull(dishes);
        assertFalse(dishes.isEmpty(), "四川应该有特色菜");
        
        // 验证包含麻婆豆腐
        boolean hasMapoTofu = dishes.stream()
                .anyMatch(d -> d.name().contains("麻婆豆腐"));
        assertTrue(hasMapoTofu, "四川特色菜应包含麻婆豆腐");

        System.out.println("=== 四川特色菜 ===");
        System.out.println(knowledgeBase.formatDishesList(dishes, "四川特色菜"));
    }

    @Test
    @DisplayName("测试3: 按口味查询 - 麻辣口味")
    void testGetDishesByTaste_Spicy() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.getDishesByTaste("麻辣");

        assertNotNull(dishes);
        assertFalse(dishes.isEmpty(), "应该有麻辣口味的菜");

        System.out.println("=== 麻辣口味特色菜 ===");
        System.out.println(knowledgeBase.formatDishesList(dishes, "麻辣口味特色菜"));
    }

    @Test
    @DisplayName("测试4: 模糊搜索 - 搜索'鸭'")
    void testSearchDishes_Duck() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.searchDishes("鸭");

        assertNotNull(dishes);
        assertFalse(dishes.isEmpty(), "应该找到包含'鸭'的菜品");
        
        // 验证所有结果都包含"鸭"
        boolean allContainDuck = dishes.stream()
                .allMatch(d -> d.name().contains("鸭"));
        assertTrue(allContainDuck, "所有结果都应包含'鸭'");

        System.out.println("=== 搜索'鸭'的结果 ===");
        System.out.println(knowledgeBase.formatDishesList(dishes, "搜索【鸭】结果"));
    }

    @Test
    @DisplayName("测试5: 获取所有城市列表")
    void testGetAllCities() {
        var cities = knowledgeBase.getAllCities();

        assertNotNull(cities);
        assertFalse(cities.isEmpty(), "应该有城市数据");
        assertTrue(cities.contains("北京"), "应包含北京");
        assertTrue(cities.contains("成都"), "应包含成都");
        assertTrue(cities.contains("广州"), "应包含广州");

        System.out.println("=== 所有城市 ===");
        System.out.println(String.join(", ", cities));
    }

    @Test
    @DisplayName("测试6: 格式化单个菜品信息")
    void testFormatDishInfo() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.getDishesByCity("北京");

        assertFalse(dishes.isEmpty());
        
        String formatted = knowledgeBase.formatDishInfo(dishes.get(0));
        
        assertNotNull(formatted);
        assertTrue(formatted.contains("•"), "应包含项目名称符号");
        assertTrue(formatted.contains("📍"), "应包含地点图标");
        assertTrue(formatted.contains("👅"), "应包含口味图标");

        System.out.println("=== 单个菜品信息示例 ===");
        System.out.println(formatted);
    }

    @Test
    @DisplayName("测试7: 空查询处理")
    void testEmptyQuery() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.searchDishes("");

        assertNotNull(dishes);
        assertTrue(dishes.isEmpty(), "空查询应返回空列表");
    }

    @Test
    @DisplayName("测试8: 不存在的城市查询")
    void testNonExistentCity() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.getDishesByCity("不存在的城市");

        assertNotNull(dishes);
        assertTrue(dishes.isEmpty(), "不存在的城市应返回空列表");
    }

    @Test
    @DisplayName("测试9: 菜品信息完整性验证")
    void testDishInfoCompleteness() {
        List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                knowledgeBase.getDishesByCity("成都");

        assertFalse(dishes.isEmpty());

        // 验证每个菜品都有完整信息
        for (SpecialtyCuisineKnowledgeBase.SpecialtyDish dish : dishes) {
            assertNotNull(dish.name(), "菜名不能为空");
            assertNotNull(dish.city(), "城市不能为空");
            assertNotNull(dish.province(), "省份不能为空");
            assertNotNull(dish.description(), "描述不能为空");
            assertNotNull(dish.taste(), "口味不能为空");
            assertNotNull(dish.ingredients(), "食材列表不能为null");
        }

        System.out.println("=== 菜品信息完整性验证通过 ===");
        System.out.println("验证了 " + dishes.size() + " 道菜品的信息完整性");
    }

    @Test
    @DisplayName("测试10: 多口味分类查询")
    void testMultipleTasteCategories() {
        String[] tastes = {"麻辣", "香辣", "清淡", "酸甜", "鲜香"};
        
        for (String taste : tastes) {
            List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> dishes = 
                    knowledgeBase.getDishesByTaste(taste);
            
            assertNotNull(dishes);
            System.out.println(taste + "口味: " + dishes.size() + " 道菜品");
        }
    }
}
