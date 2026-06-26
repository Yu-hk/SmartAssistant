/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExperienceValidator} 单元测试。
 */
class ExperienceValidatorTest {

    private final ExperienceValidator validator = new ExperienceValidator();

    @Test
    void freshExperience_noWarnings() {
        ExperienceModel exp = new ExperienceModel.CommonExperience(
                "test", "order_query", List.of("订单"), "order_agent", "general_agent", 0.8);
        exp.setHitCount(5);
        exp.setCreatedAt(System.currentTimeMillis());
        exp.setLastHitAt(System.currentTimeMillis());

        List<String> warnings = validator.validate(exp);
        assertTrue(warnings.isEmpty(), "近期高频经验不应有警告");
    }

    @Test
    void staleExperience_hasWarnings() {
        ExperienceModel exp = new ExperienceModel.CommonExperience(
                "test", "order_query", List.of("订单"), "order_agent", "general_agent", 0.8);
        exp.setHitCount(1);
        exp.setCreatedAt(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000); // 30天前
        exp.setLastHitAt(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000); // 2天前未命中

        List<String> warnings = validator.validate(exp);
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("过时") || w.contains("置信度")),
                "应包含时效性或置信度警告");
    }

    @Test
    void lowHitCount_hasWarning() {
        ExperienceModel exp = new ExperienceModel.CommonExperience(
                "test", "weather_query", List.of("天气"), "general_agent", null, 0.5);
        exp.setHitCount(1);
        exp.setCreatedAt(System.currentTimeMillis());
        exp.setLastHitAt(System.currentTimeMillis());

        List<String> warnings = validator.validate(exp);
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("命中次数"));
    }

    @Test
    void shouldPrune_oldAndUnused() {
        ExperienceModel exp = new ExperienceModel.CommonExperience(
                "test", "old_query", List.of("旧"), "general_agent", null, 0.3);
        exp.setHitCount(2);
        exp.setCreatedAt(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000); // 60天前
        exp.setLastHitAt(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000); // 30天未命中

        assertTrue(validator.shouldPrune(exp));
    }

    @Test
    void shouldNotPrune_frequent() {
        ExperienceModel exp = new ExperienceModel.CommonExperience(
                "test", "frequent_query", List.of("高频"), "order_agent", null, 0.9);
        exp.setHitCount(20);
        exp.setCreatedAt(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000);
        exp.setLastHitAt(System.currentTimeMillis() - 1L * 24 * 60 * 60 * 1000); // 1天前

        assertFalse(validator.shouldPrune(exp), "高频经验不应淘汰");
    }
}
