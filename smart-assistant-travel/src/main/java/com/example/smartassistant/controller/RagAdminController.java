/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.service.rag.TravelNoteRankingService;
import com.example.smartassistant.service.rag.TravelNoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG 管理接口
 * 用于手动触发分块、向量化等操作
 */
@Slf4j
@RestController
@RequestMapping("/admin/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final TravelNoteService travelNoteService;
    private final TravelNoteRankingService rankingService;

    /**
     * 手动触发指定游记的分块和向量化
     * POST /admin/rag/rebuild/{noteId}
     */
    @PostMapping("/rebuild/{noteId}")
    public Map<String, Object> rebuildNote(@PathVariable Long noteId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("[RagAdmin] 手动触发分块向量化: noteId={}", noteId);
            
            // 获取游记
            var note = travelNoteService.getNoteById(noteId);
            if (note == null) {
                result.put("success", false);
                result.put("message", "游记不存在: " + noteId);
                return result;
            }
            
            // 删除旧分块
            travelNoteService.deleteChunks(noteId);
            
            // 重新分块和向量化
            travelNoteService.rebuildChunks(noteId, note.getContent(), note.getLocation(), note.getTags());
            
            result.put("success", true);
            result.put("message", "分块和向量化完成");
            result.put("noteId", noteId);
            result.put("title", note.getTitle());
            
        } catch (Exception e) {
            log.error("[RagAdmin] 分块向量化失败", e);
            result.put("success", false);
            result.put("message", "失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 对所有游记重新分块和向量化
     * POST /admin/rag/rebuild-all
     */
    @PostMapping("/rebuild-all")
    public Map<String, Object> rebuildAll() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("[RagAdmin] 触发全量分块向量化");
            
            int count = travelNoteService.rebuildAllChunks();
            
            result.put("success", true);
            result.put("message", "全量分块完成");
            result.put("count", count);
            
        } catch (Exception e) {
            log.error("[RagAdmin] 全量分块失败", e);
            result.put("success", false);
            result.put("message", "失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 测试向量检索
     * GET /admin/rag/test-search?location=杭州&query=美食
     */
    @GetMapping("/test-search")
    public Map<String, Object> testSearch(
            @RequestParam String location,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "1") Long userId) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("[RagAdmin] 测试检索: location={}, query={}, userId={}", location, query, userId);
            
            var chunks = travelNoteService.searchChunks(location, query, userId);
            
            result.put("success", true);
            result.put("location", location);
            result.put("query", query);
            result.put("chunks", chunks);
            result.put("count", chunks.size());
            
        } catch (Exception e) {
            log.error("[RagAdmin] 测试检索失败", e);
            result.put("success", false);
            result.put("message", "失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 测试游记规则评分（TravelNoteRankingService）
     * GET /admin/rag/test-ranking?location=杭州&userIntent=亲子游&userId=1
     */
    @GetMapping("/test-ranking")
    public Map<String, Object> testRanking(
            @RequestParam String location,
            @RequestParam String userIntent,
            @RequestParam(defaultValue = "1") Long userId) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("[RagAdmin] 测试规则评分: location={}, userIntent={}, userId={}", 
                    location, userIntent, userId);
            
            var rankingResult = rankingService.rankTravelNotes(userId, location, userIntent);
            
            result.put("success", !rankingResult.isEmpty());
            result.put("location", location);
            result.put("userIntent", userIntent);
            result.put("intentTypeName", rankingResult.getIntentTypeName());
            result.put("noteCount", rankingResult.getRankedNotes().size());
            
            // 前3名推荐
            var top3 = rankingResult.getRankedNotes().stream()
                    .limit(3)
                    .map(sn -> {
                        var note = new HashMap<String, Object>();
                        note.put("title", sn.getNote().getTitle());
                        note.put("score", sn.getTotalScore());
                        note.put("freshnessScore", sn.getFreshnessScore());
                        note.put("intentScore", sn.getIntentScore());
                        note.put("preferenceScore", sn.getPreferenceScore());
                        note.put("qualityScore", sn.getQualityScore());
                        note.put("costScore", sn.getCostScore());
                        return note;
                    })
                    .toList();
            result.put("top3", top3);
            
            // 规则评分引擎无详细推理过程，仅返回评分结果
            result.put("note", "规则评分引擎，无 LLM 推理过程");
            
        } catch (Exception e) {
            log.error("[RagAdmin] 测试规则评分失败", e);
            result.put("success", false);
            result.put("message", "失败: " + e.getMessage());
        }
        
        return result;
    }
}
