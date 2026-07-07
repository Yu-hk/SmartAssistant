/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * LLM 智能偏好提取服务
 * 使用 DeepSeek 模型从用户问题中提取结构化偏好信息
 * <p>
 * ⭐ 使用中文分词器增强降级方案的提取能力
 * <p>
 * 偏好提取复用 {@link AiChatService#buildChatClient(ChatModel)} 接入统一 Advisor 链，
 * 并以 {@code entity(ExtractedPreferences.class)} 将 LLM 响应直接绑定为结构化对象，
 * 取代原来脆弱的文本 JSON 解析（手动清理 markdown + readTree + 逐字段映射）。
 */
@Service
public class LLMPreferenceExtractor {
    
    private static final Logger log = LoggerFactory.getLogger(LLMPreferenceExtractor.class);
    
    private final AiChatService aiChatService;
    private final ChatModel lightModel;
    private final ChineseTokenizer tokenizer;
    
    public LLMPreferenceExtractor(AiChatService aiChatService,
                                  @Qualifier("lightChatModel") ChatModel lightModel,
                                  ChineseTokenizer tokenizer) {
        this.aiChatService = aiChatService;
        this.lightModel = lightModel;
        this.tokenizer = tokenizer;
    }
    
    /**
     * 从用户问题中提取结构化偏好
     * 
     * @param question 用户问题
     * @return 提取的偏好信息（结构化对象）
     */
    public ExtractedPreferences extract(String question) {
        if (question == null || question.isBlank()) {
            return ExtractedPreferences.empty();
        }
        
        try {
            String prompt = buildExtractionPrompt(question);
            
            ExtractedPreferences prefs = aiChatService.buildChatClient(lightModel)
                    .prompt()
                    .user(prompt)
                    .call()
                    .entity(ExtractedPreferences.class);
            
            log.debug("[LLM提取] 结构化提取完成: {}", prefs);
            if (prefs == null) {
                return fallbackExtraction(question);
            }
            return prefs;
            
        } catch (Exception e) {
            log.error("[LLM提取] 提取失败，降级到正则提取: {}", e.getMessage());
            // 降级到基于规则的提取
            return fallbackExtraction(question);
        }
    }
    
    /**
     * 构建提取 Prompt
     * <p>
     * 【重要定义】
     * - 用户画像(user_profile)：仅包含用户表达情绪/偏好的关键词
     * - 触发条件：必须出现"喜欢"、"经常"、"讨厌"、"厌烦"、"偏好"、"倾向"等情绪性表述
     * - 其他普通信息（如地点、时间、目的）按正常字段提取，不计入用户画像
     */
    private String buildExtractionPrompt(String question) {
        return """
                你是一个专业的用户偏好分析助手。请从用户的问题中提取结构化信息。
                
                【核心定义：用户画像】
                用户画像(user_profile) 用于构建用户偏好向量，只提取满足以下条件的关键词：
                
                ⭐ 触发条件：用户使用了情绪性表述
                - "喜欢" / "爱" / "偏好" / "倾向于"  →  提取后面的正面偏好
                - "经常" / "总爱" / "习惯"          →  提取习惯性偏好
                - "讨厌" / "厌烦" / "不喜欢" / "厌恶"  →  提取负面偏好（需标注为负向）
                - "不要" / "拒绝" / "排斥"          →  提取排斥项（需标注为负向）
                
                【普通信息字段】（不计入用户画像）
                - location: 地点名称（城市、省份、景区等），如果没有则留空
                - purpose: 目的类型，food/ travel/ weather/ other
                - time: 时间信息，如 "周末"、"明天"，如果未提及则为 null
                
                【重要规则】
                1. 只有情绪性表述触发的偏好才进入 user_profile
                2. 普通描述（如"推荐川菜"）不提取为用户画像
                3. user_profile 中的负面偏好需标注"[负向]"标记
                4. 同一类型偏好有多项时全部列出
                
                【用户问题】
                %s
                
                【输出格式示例】
                
                例1: "我喜欢吃辣，经常去川菜馆"
                {
                  "location": null,
                  "purpose": "food",
                  "time": null,
                  "user_profile": {
                    "positive": ["辣", "川菜"],
                    "negative": []
                  }
                }
                
                例2: "讨厌香菜，不要麻婆豆腐"
                {
                  "location": null,
                  "purpose": "food",
                  "time": null,
                  "user_profile": {
                    "positive": [],
                    "negative": ["香菜", "麻婆豆腐"]
                  }
                }
                
                例3: "北京有哪些川菜馆？"
                {
                  "location": "北京",
                  "purpose": "food",
                  "time": null,
                  "user_profile": {
                    "positive": [],
                    "negative": []
                  }
                }
                
                例4: "周末去杭州玩，想吃清淡的"
                {
                  "location": "杭州",
                  "purpose": "travel",
                  "time": "周末",
                  "user_profile": {
                    "positive": ["清淡"],
                    "negative": []
                  }
                }
                
                【最终输出】
                仅输出 JSON，不要包含 markdown 标记。
                """.formatted(question);
    }
    
    /**
     * 降级方案：基于规则的提取
     * <p>
     * ⭐ 只有检测到情绪性表述时，才提取用户画像
     */
    private ExtractedPreferences fallbackExtraction(String question) {
        log.info("[LLM提取] 使用降级方案（分词器增强提取）");
        
        ExtractedPreferences prefs = new ExtractedPreferences(
                null, null, new ArrayList<>(), new ArrayList<>(), null, new ArrayList<>(), null);
        
        // 检测是否有情绪性表述
        Set<String> emotionalKeywords = Set.of(
            "喜欢", "爱", "偏好", "倾向", "经常", "总爱", "习惯",
            "讨厌", "厌烦", "不喜欢", "厌恶", "不要", "拒绝", "排斥"
        );
        boolean hasEmotional = tokenizer.containsAnyKeyword(question, emotionalKeywords);
        
        // 只有在有情绪性表述时才提取用户画像
        if (hasEmotional) {
            log.info("[LLM提取] 检测到情绪性表述，提取用户画像");
            
            // ⭐ 正面偏好提取（基于情绪性关键词）
            if (tokenizer.containsAnyKeyword(question, Set.of("辣", "麻辣", "香辣"))) {
                prefs.foodPreferences().add("辣");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("清淡", "清香"))) {
                prefs.foodPreferences().add("清淡");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("甜", "甜品", "甜点"))) {
                prefs.foodPreferences().add("甜");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("川菜", "粤菜", "湘菜"))) {
                prefs.foodPreferences().add("川菜");
            }
            
            // ⭐ 负面偏好提取
            if (tokenizer.containsAnyKeyword(question, Set.of("讨厌", "厌烦", "不喜欢", "厌恶"))) {
                // 尝试提取被讨厌的内容
                if (tokenizer.containsAnyKeyword(question, Set.of("香菜"))) {
                    prefs.dietaryRestrictions().add("不喜欢:香菜");
                }
                if (tokenizer.containsAnyKeyword(question, Set.of("辣", "麻辣"))) {
                    prefs.dietaryRestrictions().add("不喜欢:辣");
                }
            }
            
            // ⭐ 旅行偏好提取
            if (tokenizer.containsAnyKeyword(question, Set.of("自然", "山水", "风景"))) {
                prefs.travelPreferences().add("自然");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("文化", "历史", "古迹"))) {
                prefs.travelPreferences().add("文化");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("休闲", "放松", "度假"))) {
                prefs.travelPreferences().add("休闲");
            }
        } else {
            log.info("[LLM提取] 无情绪性表述，跳过用户画像提取");
        }
        
        // 通用信息提取（不受情绪性表述限制）
        // 预算提取
        if (tokenizer.containsAnyKeyword(question, Set.of("便宜", "经济", "实惠", "相因"))) {
            prefs = prefs.withBudget("low");
        } else if (tokenizer.containsAnyKeyword(question, Set.of("高端", "豪华", "奢侈"))) {
            prefs = prefs.withBudget("high");
        } else if (tokenizer.containsAnyKeyword(question, Set.of("人均", "性价比"))) {
            prefs = prefs.withBudget("medium");
        }
        
        // 饮食限制提取（明确限制，非偏好）
        if (tokenizer.containsAnyKeyword(question, Set.of("素食", "斋"))) {
            prefs.dietaryRestrictions().add("素食");
        }
        if (tokenizer.containsAnyKeyword(question, Set.of("清真"))) {
            prefs.dietaryRestrictions().add("清真");
        }
        if (tokenizer.containsAnyKeyword(question, Set.of("无辣", "不辣", "少辣"))) {
            prefs.dietaryRestrictions().add("少辣");
        }
        
        return prefs;
    }
    
    /**
     * 提取的偏好数据结构。
     * <p>声明为不可变 {@code record} 以适配 {@link AiChatService#entity} 的结构化绑定
     * （Spring AI 的 BeanOutputConverter 对 record + Jackson 兼容，直接映射 LLM 返回的 JSON）。</p>
     *
     * @param location             地点（城市/省份/景区），无则 null
     * @param purpose              目的类型 food/travel/weather/other
     * @param foodPreferences      美食偏好关键词
     * @param travelPreferences    旅行偏好关键词
     * @param budget               预算档位 low/medium/high
     * @param dietaryRestrictions  饮食限制/负向偏好
     * @param time                 时间信息
     */
        public record ExtractedPreferences(
            String location,
            String purpose,
            List<String> foodPreferences,
            List<String> travelPreferences,
            String budget,
            List<String> dietaryRestrictions,
            String time) {
        
        /** 空偏好（无提取结果时的默认值） */
        public static ExtractedPreferences empty() {
            return new ExtractedPreferences(null, null, List.of(), List.of(), null, List.of(), null);
        }
        
        /** 不可变更新：预算（record 不可变，upsert 用 with* 语义） */
        public ExtractedPreferences withBudget(String budget) {
            return new ExtractedPreferences(location, purpose, foodPreferences,
                    travelPreferences, budget, dietaryRestrictions, time);
        }
    }
}
