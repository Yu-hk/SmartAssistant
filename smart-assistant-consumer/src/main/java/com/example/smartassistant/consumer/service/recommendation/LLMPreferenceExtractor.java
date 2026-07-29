/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

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
                - complaintReason: 投诉/抱怨的原因，如 "发货慢"、"质量问题"、"退款未到账"等，无则 null
                - preferredChannel: 用户偏好的沟通渠道，从问题中推断（wechat / web / phone），无则 null
                - responsePreference: 回复风格偏好，用户若表达"详细一点"、"说清楚些"则为 detailed；"简洁"、"简单说"则为 concise；无则 normal
                - sensitiveTopics: 用户表现出负面情绪、不满或敏感的话题列表，如 ["物流", "退款"]，无则空数组
                - keyInsights: 隐藏关键信息（潜在需求 / 隐性信号）。用户在对话中不经意透露、未明确表达为偏好的关键上下文，例如 "经常出差"、"家里老人用"、"公司采购"、"预算有限"、"怀孕了"、"亲子/带娃"、"竞品来源"、"时效敏感(赶时间)"、"学生/校园"、"无障碍需求"、"宠物家庭"、"新落户/异地" 等。⭐ 该字段不要求情绪性表述，普通陈述句也要提取，无则空数组
                
                【重要规则】
                1. 只有情绪性表述触发的偏好才进入 user_profile
                2. 普通描述（如"推荐川菜"）不提取为用户画像
                3. user_profile 中的负面偏好需标注"[负向]"标记
                4. 同一类型偏好有多项时全部列出
                5. keyInsights 与情绪无关：任何句子（包括平静陈述）只要透露了用户身份/场景/约束类关键信息，都应提取
                
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
                  },
                  "complaintReason": null,
                  "preferredChannel": null,
                  "responsePreference": "normal",
                  "sensitiveTopics": [],
                  "keyInsights": []
                }
                
                例2: "发货太慢了！我要投诉，我们公司团建急用，请说清楚赔偿方案"
                {
                  "location": null,
                  "purpose": "other",
                  "time": null,
                  "user_profile": {
                    "positive": [],
                    "negative": ["快递慢", "延迟"]
                  },
                  "complaintReason": "发货慢",
                  "preferredChannel": null,
                  "responsePreference": "detailed",
                  "sensitiveTopics": ["物流", "赔偿"],
                  "keyInsights": ["企业采购(B2B)", "时效敏感"]
                }
                
                例3: "北京有哪些川菜馆？"
                {
                  "location": "北京",
                  "purpose": "food",
                  "time": null,
                  "user_profile": {
                    "positive": [],
                    "negative": []
                  },
                  "complaintReason": null,
                  "preferredChannel": null,
                  "responsePreference": "normal",
                  "sensitiveTopics": [],
                  "keyInsights": []
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
                null, null, new ArrayList<>(), new ArrayList<>(), null, new ArrayList<>(), null,
                null, null, "normal", new ArrayList<>(), new ArrayList<>());
        
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

        // ⭐ P1-A：投诉/抱怨检测
        if (tokenizer.containsAnyKeyword(question, Set.of("投诉", "退货", "退款", "赔偿", "差评", "举报"))) {
            // 用反射方式不可行，直接设置 —— 因 record 不可变，这里只记录原始问题文本
            prefs = new ExtractedPreferences(
                    prefs.location(), prefs.purpose(), prefs.foodPreferences(),
                    prefs.travelPreferences(), prefs.budget(), prefs.dietaryRestrictions(),
                    prefs.time(), "用户表达投诉/不满", null, prefs.responsePreference(),
                    prefs.sensitiveTopics(), prefs.keyInsights());
        }

        // ⭐ P1-A：回复偏好检测
        if (tokenizer.containsAnyKeyword(question, Set.of("详细", "具体", "说清楚", "细说"))) {
            prefs = new ExtractedPreferences(
                    prefs.location(), prefs.purpose(), prefs.foodPreferences(),
                    prefs.travelPreferences(), prefs.budget(), prefs.dietaryRestrictions(),
                    prefs.time(), prefs.complaintReason(), null, "detailed",
                    prefs.sensitiveTopics(), prefs.keyInsights());
        } else if (tokenizer.containsAnyKeyword(question, Set.of("简洁", "简单", "简短"))) {
            prefs = new ExtractedPreferences(
                    prefs.location(), prefs.purpose(), prefs.foodPreferences(),
                    prefs.travelPreferences(), prefs.budget(), prefs.dietaryRestrictions(),
                    prefs.time(), prefs.complaintReason(), null, "concise",
                    prefs.sensitiveTopics(), prefs.keyInsights());
        }

        // ⭐ P2-C：潜在关键信息（隐性需求）探测 —— 不依赖情绪性表述，任何语句都可触发
        List<String> latent = detectLatentSignals(question);
        if (!latent.isEmpty()) {
            List<String> merged = new ArrayList<>(prefs.keyInsights());
            for (String s : latent) {
                if (!merged.contains(s)) merged.add(s);
            }
            prefs = new ExtractedPreferences(
                    prefs.location(), prefs.purpose(), prefs.foodPreferences(),
                    prefs.travelPreferences(), prefs.budget(), prefs.dietaryRestrictions(),
                    prefs.time(), prefs.complaintReason(), prefs.preferredChannel(),
                    prefs.responsePreference(), prefs.sensitiveTopics(), merged);
        }

        return prefs;
    }

    /**
     * ⭐ P2-C：探测对话中隐藏的关键信息（潜在需求 / 隐性信号）。
     * <p>纯正则实现，确定性、可被单测覆盖；与情绪无关，平静陈述句也会命中。
     * 命中后返回规整的标签（如 "经常出差"、"企业采购(B2B)"），供画像持久化与个性化使用。</p>
     *
     * @param text 用户原始语句
     * @return 去重后的潜在关键信息标签列表（无则空列表）
     */
    public List<String> detectLatentSignals(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        for (Map.Entry<Pattern, String> e : LATENT_PATTERNS) {
            if (e.getKey().matcher(text).find()) {
                if (!result.contains(e.getValue())) result.add(e.getValue());
            }
        }
        return result;
    }

    /** 潜在关键信息正则 → 规整标签 */
    private static final List<Map.Entry<Pattern, String>> LATENT_PATTERNS = List.of(
            Map.entry(Pattern.compile("经常出差|常年在外|总在外出差|频繁出差|一直在外地"), "经常出差"),
            Map.entry(Pattern.compile("家里老人|给父母|给长辈|我妈|我爸|爷爷奶奶|外公外婆|适老"), "家庭/适老场景"),
            Map.entry(Pattern.compile("公司采购|我们公司|公司用|团队用|单位采购|企业采购|团建"), "企业采购(B2B)"),
            Map.entry(Pattern.compile("预算有限|手头紧|省钱|预算不多|资金紧张|囊中羞涩"), "预算敏感"),
            Map.entry(Pattern.compile("怀孕了|孕期|哺乳期|孕妇|有孕|怀了"), "特殊健康期"),
            Map.entry(Pattern.compile("小孩|孩子|宝宝|亲子|儿子|女儿|带娃|遛娃"), "亲子场景"),
            Map.entry(Pattern.compile("过敏|忌口|不吃.*(海鲜|辣|芒果|香菜)|海鲜过敏"), "饮食过敏/忌口"),
            Map.entry(Pattern.compile("别家|竞品|原来用|以前用|之前在.*(家|平台|App)|一直用.*(家|品牌)"), "竞品来源"),
            Map.entry(Pattern.compile("急用|马上要|赶时间|赶飞机|赶车|赶高铁|急着用|尽快要|等不及"), "时效敏感"),
            Map.entry(Pattern.compile("学生|上学|校园|住校|考研|备考"), "学生群体"),
            Map.entry(Pattern.compile("残疾|行动不便|坐轮椅|无障碍|腿脚不便"), "无障碍需求"),
            Map.entry(Pattern.compile("刚搬家|新城市|异地|外地来的|刚到.*(城市|这边)|搬来"), "新落户/异地"),
            Map.entry(Pattern.compile("准备结婚|婚礼|备婚|婚庆|结婚"), "婚庆场景"),
            Map.entry(Pattern.compile("刚退休|退休了|老年人|上年纪"), "退休群体"),
            Map.entry(Pattern.compile("养宠物|有猫|有狗|毛孩子|猫主子|狗子"), "宠物家庭")
    );
    
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
     * @param complaintReason      投诉/抱怨原因（P1-A）
     * @param preferredChannel     偏好渠道 wechat/web/phone（P1-A）
     * @param responsePreference   回复偏好 concise/detailed/normal（P1-A）
 * @param sensitiveTopics      敏感话题列表（P1-A）
 * @param keyInsights           隐藏关键信息（潜在需求/隐性信号），不依赖情绪性表述，任何语句都可提取（P2-C）
 */
    public record ExtractedPreferences(
        String location,
        String purpose,
        List<String> foodPreferences,
        List<String> travelPreferences,
        String budget,
        List<String> dietaryRestrictions,
        String time,
        String complaintReason,
        String preferredChannel,
        String responsePreference,
        List<String> sensitiveTopics,
        List<String> keyInsights) {
        
        /** 空偏好（无提取结果时的默认值） */
        public static ExtractedPreferences empty() {
            return new ExtractedPreferences(null, null, List.of(), List.of(), null, List.of(), null,
                    null, null, "normal", List.of(), List.of());
        }
        
        /** 不可变更新：预算（record 不可变，upsert 用 with* 语义） */
        public ExtractedPreferences withBudget(String budget) {
            return new ExtractedPreferences(location, purpose, foodPreferences,
                    travelPreferences, budget, dietaryRestrictions, time,
                    complaintReason, preferredChannel, responsePreference, sensitiveTopics, keyInsights);
        }
    }
}
