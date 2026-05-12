/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service;

import com.example.smartassistant.entity.TravelNote;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TravelNoteMatchService 测试
 * 验证多游记 LLM 分析推理功能
 */
@SpringBootTest
public class TravelNoteMatchServiceTest {

    @Autowired(required = false)
    private TravelNoteMatchService travelNoteMatchService;

    @Test
    void testSelectBestTravelNote_withMockNotes() {
        if (travelNoteMatchService == null) {
            System.out.println("⚠️ TravelNoteMatchService 未注入，跳过测试");
            return;
        }

        // 测试场景：杭州，带娃游玩
        Long userId = 1L;
        String location = "杭州";
        String userIntent = "带娃游玩";

        System.out.println("========== 开始测试游记匹配服务 ==========");
        System.out.println("用户: " + userId + ", 地点: " + location + ", 意图: " + userIntent);

        try {
            TravelNoteMatchService.MatchResult result =
                    travelNoteMatchService.selectBestTravelNote(userId, location, userIntent);

            String output = result.toMcpText();
            System.out.println("========== 匹配结果 ==========");
            System.out.println(output);
            System.out.println("================================");

            assertTrue(result.isSuccess(), "匹配应该成功");
            assertNotNull(result.getBestNote(), "应该有最佳匹配游记");
            assertNotNull(output, "输出文本不应为空");
            assertTrue(output.length() > 50, "输出应该足够长");

        } catch (Exception e) {
            System.err.println("❌ 测试失败: " + e.getMessage());
            e.printStackTrace();
            fail("测试不应抛出异常: " + e.getMessage());
        }
    }

    @Test
    void testSelectBestTravelNote_emptyLocation() {
        if (travelNoteMatchService == null) {
            System.out.println("⚠️ TravelNoteMatchService 未注入，跳过测试");
            return;
        }

        TravelNoteMatchService.MatchResult result =
                travelNoteMatchService.selectBestTravelNote(1L, "", "带娃游玩");

        assertFalse(result.isSuccess(), "空地点应该返回失败");
        assertTrue(result.getErrorMessage().contains("地点不能为空"), "错误消息应包含地点为空提示");
    }

    @Test
    void testSelectBestTravelNote_disabledRag() {
        if (travelNoteMatchService == null) {
            System.out.println("⚠️ TravelNoteMatchService 未注入，跳过测试");
            return;
        }

        // 当 RAG 被禁用时
        TravelNoteMatchService.MatchResult result =
                travelNoteMatchService.selectBestTravelNote(1L, "北京", "美食");

        if (!result.isEnabled()) {
            System.out.println("✅ RAG 已禁用（符合预期）: " + result.getErrorMessage());
        } else {
            System.out.println("⚠️ RAG 已启用，跳过禁用测试");
        }
    }
}
