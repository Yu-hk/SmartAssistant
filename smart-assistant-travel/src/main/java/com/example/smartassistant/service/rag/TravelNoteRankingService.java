package com.example.smartassistant.service.rag;

import com.example.smartassistant.entity.TravelNote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 游记规则评分引擎
 *
 * <p>纯规则推理，不依赖外部 LLM 模型。</p>
 * <p>通过多维度规则评分，从游记库中找出最匹配的推荐。</p>
 *
 * <p><b>核心设计</b>：评分基于两个维度</p>
 * <ul>
 *     <li><b>意图类型 (IntentType)</b>：从用户当前意图推断的出行类型（亲子、美食、拍照等）</li>
 *     <li><b>用户偏好 (UserPreference)</b>：从用户历史游记中提取的长期偏好特征</li>
 * </ul>
 *
 * <p>评分维度与权重：</p>
 * <ul>
 *     <li>新鲜度 (15%): 游记发布时间越近，价值越高</li>
 *     <li>意图匹配 (30%): 关键词/标签与用户当前意图的匹配程度</li>
 *     <li>偏好匹配 (15%): 与用户历史偏好的一致性</li>
 *     <li>内容质量 (20%): 内容长度、标签完整性等</li>
 *     <li>性价比 (20%): 景点价格与体验价值的平衡</li>
 * </ul>
 *
 * <p>典型场景：</p>
 * <ul>
 *     <li>用户问"杭州带娃玩什么" → 意图类型=亲子、用户偏好=深度游 → 推荐亲子+深度体验型游记</li>
 *     <li>用户问"杭州美食推荐" → 意图类型=美食、用户偏好=打卡游 → 推荐美食+网红打卡型游记</li>
 * </ul>
 */
@Service
@Slf4j
public class TravelNoteRankingService {

    // ==================== 权重配置 ====================
    private static final double WEIGHT_FRESHNESS = 0.15;       // 新鲜度
    private static final double WEIGHT_INTENT_MATCH = 0.30;    // 意图匹配
    private static final double WEIGHT_PREFERENCE_MATCH = 0.15; // 用户偏好匹配
    private static final double WEIGHT_CONTENT_QUALITY = 0.20; // 内容质量
    private static final double WEIGHT_COST_EFFICIENCY = 0.20; // 性价比

    // ==================== 常量配置 ====================
    private static final int MAX_NOTES_FOR_ANALYSIS = 20;     // 最多分析20篇
    private static final int MIN_CONTENT_LENGTH = 100;         // 最低内容长度
    private static final int OPTIMAL_CONTENT_LENGTH = 500;    // 最佳内容长度
    private static final int MAX_DAYS_FOR_FULL_SCORE = 30;     // 30天内新鲜度满分

    // ==================== 意图类型定义（TravelProfile）====================
    /**
     * 意图类型 - 基于用户当前意图推断的出行类型
     *
     * <p>这是对用户"当前想要什么"的分类，而非用户的长期偏好。</p>
     * <p>例如：用户说"带娃玩"，意图类型=亲子；用户说"美食之旅"，意图类型=美食</p>
     */
    private static final Map<String, TravelProfile> INTENT_TYPES = new LinkedHashMap<>();

    static {
        // ========== 亲子/带娃 ==========
        INTENT_TYPES.put("亲子", TravelProfile.builder()
                .name("亲子游")
                // 意图关键词匹配
                .keywords(List.of("亲子", "小孩", "儿童", "家庭", "小朋友", "宝贝", "娃", "孩子", "宝宝", "带娃"))
                // 正向标签（匹配加分）
                .positiveTags(Set.of("亲子", "家庭", "儿童", "乐园", "动物园", "博物馆", "海洋馆",
                        "儿童乐园", "水上乐园", "科技馆", "亲子餐厅", "户外拓展"))
                // 负向标签（匹配减分）
                .negativeTags(Set.of("夜生活", "酒吧", "极限运动", "高空项目", "刺激项目"))
                // 景点类型偏好
                .preferredAttractionTypes(Set.of("主题乐园", "动物园", "博物馆", "科技馆", "游乐场", "公园"))
                .dislikedAttractionTypes(Set.of("酒吧", "夜店", "密室", "剧本杀"))
                // 游玩节奏
                .pacePreference("relaxed")
                .costPreference("medium")
                .build());

        // 简化别名
        INTENT_TYPES.put("带娃", INTENT_TYPES.get("亲子"));

        // ========== 情侣/浪漫 ==========
        INTENT_TYPES.put("情侣", TravelProfile.builder()
                .name("浪漫游")
                .keywords(List.of("情侣", "浪漫", "二人世界", "蜜月", "约会", "双人", "夫妻"))
                .positiveTags(Set.of("浪漫", "海边", "日出", "日落", "夜景", "烛光", "度假", "温泉"))
                .negativeTags(Set.of("亲子", "儿童", "嘈杂", "大妈团"))
                .preferredAttractionTypes(Set.of("海边", "度假村", "温泉", "风景区", "古镇"))
                .dislikedAttractionTypes(Set.of("游乐场", "儿童区"))
                .pacePreference("romantic")
                .costPreference("high")
                .build());

        // ========== 美食 ==========
        INTENT_TYPES.put("美食", TravelProfile.builder()
                .name("美食之旅")
                .keywords(List.of("美食", "好吃", "餐厅", "小吃", "特色菜", "夜市", "烧烤", "火锅", "当地", "美食街", "探店"))
                .positiveTags(Set.of("美食", "小吃", "夜市", "特色", "餐厅", "当地美食", "网红店", "老字号"))
                .negativeTags(Set.of())
                .preferredAttractionTypes(Set.of("美食街", "夜市", "老街"))
                .dislikedAttractionTypes(Set.of())
                .pacePreference("foodie")
                .costPreference("low")
                .build());

        // ========== 拍照/打卡 ==========
        INTENT_TYPES.put("拍照", TravelProfile.builder()
                .name("拍照打卡")
                .keywords(List.of("拍照", "打卡", "网红", "出片", "摄影", "风景", "ins风", "好看"))
                .positiveTags(Set.of("网红", "拍照", "日落", "日出", "海景", "花海", "天空之境"))
                .negativeTags(Set.of())
                .preferredAttractionTypes(Set.of("网红打卡点", "自然风光", "海景", "花海", "日出日落"))
                .dislikedAttractionTypes(Set.of())
                .pacePreference("photo")
                .costPreference("medium")
                .build());

        INTENT_TYPES.put("打卡", INTENT_TYPES.get("拍照"));

        // ========== 省钱/穷游 ==========
        INTENT_TYPES.put("省钱", TravelProfile.builder()
                .name("省钱游")
                .keywords(List.of("省钱", "穷游", "免费", "性价比", "优惠", "学生票", "攻略", "低成本"))
                .positiveTags(Set.of("免费", "低价", "学生票", "优惠", "免费景点"))
                .negativeTags(Set.of("高端", "奢华", "高消费"))
                .preferredAttractionTypes(Set.of("免费景点", "公园", "博物馆", "古镇"))
                .dislikedAttractionTypes(Set.of("高端餐厅", "私人会所"))
                .pacePreference("budget")
                .costPreference("very_low")
                .build());

        INTENT_TYPES.put("穷游", INTENT_TYPES.get("省钱"));

        // ========== 文化/历史 ==========
        INTENT_TYPES.put("文化", TravelProfile.builder()
                .name("文化探索")
                .keywords(List.of("文化", "历史", "古迹", "博物馆", "寺庙", "展览", "建筑", "人文", "古迹"))
                .positiveTags(Set.of("历史", "文化", "博物馆", "古迹", "寺庙", "建筑", "非遗", "传承"))
                .negativeTags(Set.of("游乐场", "主题公园", "水上乐园"))
                .preferredAttractionTypes(Set.of("博物馆", "古迹", "寺庙", "历史街区", "古镇", "书院"))
                .dislikedAttractionTypes(Set.of("游乐场", "水上乐园"))
                .pacePreference("cultural")
                .costPreference("low")
                .build());

        INTENT_TYPES.put("历史", INTENT_TYPES.get("文化"));

        // ========== 自然/户外 ==========
        INTENT_TYPES.put("自然", TravelProfile.builder()
                .name("自然户外")
                .keywords(List.of("自然", "户外", "徒步", "登山", "风景", "山水", "森林", "草原", "露营"))
                .positiveTags(Set.of("自然", "山水", "徒步", "森林", "湖泊", "草原", "日出", "日落", "星空"))
                .negativeTags(Set.of("室内", "商场"))
                .preferredAttractionTypes(Set.of("自然风光", "森林公园", "湖泊", "山脉", "草原"))
                .dislikedAttractionTypes(Set.of("商场", "室内游乐园"))
                .pacePreference("active")
                .costPreference("low")
                .build());

        // ========== 休闲/放松 ==========
        INTENT_TYPES.put("休闲", TravelProfile.builder()
                .name("休闲度假")
                .keywords(List.of("休闲", "放松", "度假", "慢生活", "舒适", "躺平", "慢节奏"))
                .positiveTags(Set.of("温泉", "度假", "SPA", "海滨", "休闲", "慢生活", "养生"))
                .negativeTags(Set.of("暴走", "赶路", "特种兵"))
                .preferredAttractionTypes(Set.of("温泉", "海滨", "度假村", "民宿", "茶园"))
                .dislikedAttractionTypes(Set.of("高强度徒步"))
                .pacePreference("relaxed")
                .costPreference("medium")
                .build());

        // ========== 购物 ==========
        INTENT_TYPES.put("购物", TravelProfile.builder()
                .name("购物之旅")
                .keywords(List.of("购物", "逛街", "买买买", "免税店", "商场", "奥特莱斯"))
                .positiveTags(Set.of("购物", "免税", "商场", "奥特莱斯", "特产"))
                .negativeTags(Set.of())
                .preferredAttractionTypes(Set.of("商场", "免税店", "奥特莱斯", "商业街"))
                .dislikedAttractionTypes(Set.of())
                .pacePreference("shopping")
                .costPreference("medium")
                .build());
    }

    // ==================== 依赖服务 ====================
    private final TravelNoteService travelNoteService;

    public TravelNoteRankingService(TravelNoteService travelNoteService) {
        this.travelNoteService = travelNoteService;
    }

    // ==================== 核心方法 ====================

    /**
     * 规则推理：找出最匹配的游记
     *
     * <p>评分基于两个维度：</p>
     * <ul>
     *     <li><b>意图类型</b>：从用户当前意图推断（亲子、美食、拍照等）</li>
     *     <li><b>用户偏好</b>：从用户历史游记中提取的长期偏好特征</li>
     * </ul>
     *
     * @param userId    用户ID（用于提取用户偏好）
     * @param location  目的地/地点
     * @param userIntent 用户意图（如"带娃游玩"、"美食"等）
     * @return 评分结果列表
     */
    public RankingResult rankTravelNotes(Long userId, String location, String userIntent) {
        log.info("[Ranking] 开始规则推理: userId={}, location={}, intent={}", userId, location, userIntent);

        // Step 1: 搜索该地点的游记
        List<TravelNote> allNotes = travelNoteService.searchByLocation(location);
        if (allNotes.isEmpty()) {
            log.info("[Ranking] 该地点无游记: location={}", location);
            return RankingResult.empty(location);
        }
        log.info("[Ranking] Step1: 找到 {} 篇游记", allNotes.size());

        // Step 2: 筛选最新发布
        List<TravelNote> recentNotes = filterRecentNotes(allNotes);
        log.info("[Ranking] Step2: 筛选最新后剩 {} 篇", recentNotes.size());

        // Step 3: 解析意图类型
        TravelProfile intentType = parseIntentToProfile(userIntent);
        log.info("[Ranking] Step3: 意图类型={}, 节奏={}, 消费={}",
                intentType != null ? intentType.getName() : "通用",
                intentType != null ? intentType.getPacePreference() : "normal",
                intentType != null ? intentType.getCostPreference() : "medium");

        // Step 4: 提取用户偏好（如果有 userId）
        UserPreference userPref = extractUserPreference(userId);
        log.info("[Ranking] Step4: 用户偏好={}",
                userPref != null ? userPref.getSummary() : "无历史数据");

        // Step 5: 多维度评分
        List<ScoredNote> scoredNotes = scoreNotes(recentNotes, intentType, userPref);

        // Step 6: 排序返回
        scoredNotes.sort((a, b) -> Double.compare(b.totalScore, a.totalScore));

        log.info("[Ranking] Step6: 评分完成，最高分={}",
                scoredNotes.isEmpty() ? 0 : scoredNotes.get(0).totalScore);

        return RankingResult.of(scoredNotes, location, userIntent, intentType);
    }

    /**
     * Step 4: 提取用户偏好
     *
     * <p>从用户的历史游记中提取长期偏好特征，这是真正的"用户画像"。</p>
     */
    private UserPreference extractUserPreference(Long userId) {
        if (userId == null) {
            return null;
        }

        try {
            List<TravelNote> userNotes = travelNoteService.getUserNotes(userId);
            if (userNotes == null || userNotes.isEmpty()) {
                return null;
            }

            // 统计标签频率
            Map<String, Long> tagFrequency = new HashMap<>();
            int totalContentLength = 0;

            for (TravelNote note : userNotes) {
                String tagsStr = note.getTags();
                if (tagsStr != null && !tagsStr.isEmpty()) {
                    for (String tag : tagsStr.split(",")) {
                        tagFrequency.merge(tag.toLowerCase().trim(), 1L, Long::sum);
                    }
                }
                if (note.getContent() != null) {
                    totalContentLength += note.getContent().length();
                }
            }

            if (tagFrequency.isEmpty()) {
                return null;
            }

            // 分析偏好特征
            return UserPreference.builder()
                    .totalNotes(userNotes.size())
                    .avgContentLength(userNotes.isEmpty() ? 0 : totalContentLength / userNotes.size())
                    .topTags(tagFrequency.entrySet().stream()
                            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                            .limit(5)
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toList()))
                    .build();

        } catch (Exception e) {
            log.warn("[Ranking] 提取用户偏好失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Step 2: 筛选最新发布的游记
     */
    private List<TravelNote> filterRecentNotes(List<TravelNote> notes) {
        return notes.stream()
                .sorted(Comparator.comparing(
                        n -> n.getCreatedAt() != null ? n.getCreatedAt() : LocalDateTime.MIN,
                        Comparator.reverseOrder()))
                .limit(MAX_NOTES_FOR_ANALYSIS)
                .collect(Collectors.toList());
    }

    /**
     * Step 3: 解析意图为旅行画像
     */
    private TravelProfile parseIntentToProfile(String userIntent) {
        if (userIntent == null || userIntent.isEmpty()) {
            return null;
        }

        String normalized = userIntent.toLowerCase().trim();

        // 直接匹配
        if (INTENT_TYPES.containsKey(normalized)) {
            return INTENT_TYPES.get(normalized);
        }

        // 模糊匹配关键词
        for (Map.Entry<String, TravelProfile> entry : INTENT_TYPES.entrySet()) {
            TravelProfile profile = entry.getValue();
            for (String keyword : profile.getKeywords()) {
                if (normalized.contains(keyword.toLowerCase())) {
                    log.info("[Ranking] 意图 '{}' 匹配到画像 '{}'", userIntent, profile.getName());
                    return profile;
                }
            }
        }

        return null;
    }

    /**
     * Step 5: 多维度评分
     */
    private List<ScoredNote> scoreNotes(List<TravelNote> notes, TravelProfile intentType, UserPreference userPref) {
        List<ScoredNote> scored = new ArrayList<>();

        for (TravelNote note : notes) {
            // 1. 计算各维度评分
            double freshnessScore = calculateFreshnessScore(note);
            String freshnessReason = generateFreshnessReason(note);

            double intentScore = calculateIntentMatchScore(note, intentType);
            String intentReason = generateIntentReason(note, intentType);

            double preferenceScore = calculatePreferenceMatchScore(note, userPref);
            String preferenceReason = generatePreferenceReason(note, userPref);

            double qualityScore = calculateContentQualityScore(note);
            String qualityReason = generateQualityReason(note);

            double costScore = calculateCostEfficiencyScore(note, intentType);
            String costReason = generateCostReason(note, intentType);

            // 2. 综合评分
            double totalScore = WEIGHT_FRESHNESS * freshnessScore
                    + WEIGHT_INTENT_MATCH * intentScore
                    + WEIGHT_PREFERENCE_MATCH * preferenceScore
                    + WEIGHT_CONTENT_QUALITY * qualityScore
                    + WEIGHT_COST_EFFICIENCY * costScore;

            // 3. 构建评分对象
            ScoredNote sn = ScoredNote.builder()
                    .note(note)
                    .totalScore(totalScore)
                    .freshnessScore(freshnessScore)
                    .intentScore(intentScore)
                    .preferenceScore(preferenceScore)
                    .qualityScore(qualityScore)
                    .costScore(costScore)
                    .freshnessReason(freshnessReason)
                    .intentReason(intentReason)
                    .preferenceReason(preferenceReason)
                    .qualityReason(qualityReason)
                    .costReason(costReason)
                    .build();

            scored.add(sn);
        }

        return scored;
    }

    // ==================== 评分计算方法 ====================

    /**
     * 计算新鲜度评分
     * 30天内满分，之后线性衰减
     */
    private double calculateFreshnessScore(TravelNote note) {
        if (note.getCreatedAt() == null) {
            return 0.5;
        }

        long daysDiff = ChronoUnit.DAYS.between(note.getCreatedAt(), LocalDateTime.now());

        if (daysDiff <= MAX_DAYS_FOR_FULL_SCORE) {
            return 1.0;
        } else if (daysDiff <= 180) {
            return Math.max(0.3, 1.0 - (daysDiff - MAX_DAYS_FOR_FULL_SCORE) / 180.0 * 0.4);
        } else if (daysDiff <= 365) {
            return Math.max(0.2, 0.6 - (daysDiff - 180) / 365.0 * 0.3);
        } else {
            return Math.max(0.1, 0.3 - Math.min(0.2, (daysDiff - 365) / 1000.0));
        }
    }

    private String generateFreshnessReason(TravelNote note) {
        if (note.getCreatedAt() == null) {
            return "发布时间未知";
        }
        long daysDiff = ChronoUnit.DAYS.between(note.getCreatedAt(), LocalDateTime.now());
        if (daysDiff <= 7) return "🔥 最新发布";
        if (daysDiff <= 30) return "📅 近期发布";
        if (daysDiff <= 180) return "📆 半年内";
        if (daysDiff <= 365) return "📆 一年内";
        return "📆 一年前";
    }

    /**
     * 计算意图匹配评分
     * 匹配正向标签和关键词
     */
    private double calculateIntentMatchScore(TravelNote note, TravelProfile profile) {
        if (profile == null || profile.getKeywords().isEmpty()) {
            return 0.6;
        }

        String tags = nullToEmpty(note.getTags()).toLowerCase();
        String content = nullToEmpty(note.getContent()).toLowerCase();
        String title = nullToEmpty(note.getTitle()).toLowerCase();

        double score = 0.0;
        int matchCount = 0;

        // 匹配正向标签
        for (String tag : profile.getPositiveTags()) {
            if (tags.contains(tag.toLowerCase()) || content.contains(tag.toLowerCase())) {
                matchCount++;
                score += 0.25;
            }
        }

        // 匹配关键词（标题权重更高）
        for (String keyword : profile.getKeywords()) {
            if (title.contains(keyword.toLowerCase())) {
                matchCount++;
                score += 0.35;
            } else if (content.contains(keyword.toLowerCase())) {
                matchCount++;
                score += 0.15;
            }
        }

        // 扣分项
        for (String negTag : profile.getNegativeTags()) {
            if (tags.contains(negTag.toLowerCase())) {
                score -= 0.2;
            }
        }

        // 归一化
        int totalWeight = profile.getPositiveTags().size() * 3 + profile.getKeywords().size() * 4;
        return Math.max(0, Math.min(1.0, score / Math.max(1, totalWeight / 10.0)));
    }

    private String generateIntentReason(TravelNote note, TravelProfile profile) {
        if (profile == null) {
            return "无明确意图匹配";
        }

        String tags = nullToEmpty(note.getTags());
        List<String> matchedTags = new ArrayList<>();

        for (String tag : profile.getPositiveTags()) {
            if (tags.contains(tag)) {
                matchedTags.add(tag);
            }
        }

        if (matchedTags.isEmpty()) {
            return "未匹配特定意图标签";
        }
        return "✅ 匹配: " + String.join(", ", matchedTags.stream().limit(3).toList());
    }

    /**
     * 计算用户偏好匹配评分
     *
     * <p>这是真正的"用户画像"评分 - 基于用户历史偏好，而非当前意图</p>
     */
    private double calculatePreferenceMatchScore(TravelNote note, UserPreference userPref) {
        if (userPref == null || userPref.getTopTags() == null || userPref.getTopTags().isEmpty()) {
            return 0.5;  // 无偏好数据时给基础分
        }

        String noteTags = nullToEmpty(note.getTags()).toLowerCase();
        String noteContent = nullToEmpty(note.getContent()).toLowerCase();
        String noteTitle = nullToEmpty(note.getTitle()).toLowerCase();
        String fullText = noteTitle + " " + noteTags + " " + noteContent;

        double score = 0.3;  // 基础分

        // 检查游记标签是否匹配用户的热门偏好
        for (String topTag : userPref.getTopTags()) {
            if (fullText.contains(topTag.toLowerCase())) {
                score += 0.14;  // 每个匹配+14%，最多5个标签匹配
            }
        }

        return Math.min(1.0, score);
    }

    private String generatePreferenceReason(TravelNote note, UserPreference userPref) {
        if (userPref == null) {
            return "👤 无历史偏好";
        }

        String noteTags = nullToEmpty(note.getTags()).toLowerCase();

        // 检查匹配了用户的哪个偏好标签
        for (String topTag : userPref.getTopTags()) {
            if (noteTags.contains(topTag.toLowerCase())) {
                return "👤 匹配您的偏好 [" + topTag + "]";
            }
        }

        return "👤 符合您的一般风格";
    }

    /**
     * 计算内容质量评分
     */
    private double calculateContentQualityScore(TravelNote note) {
        String content = nullToEmpty(note.getContent());

        // 1. 内容长度评分 (0-0.5)
        double lengthScore;
        if (content.length() < MIN_CONTENT_LENGTH) {
            lengthScore = 0.1;
        } else if (content.length() <= OPTIMAL_CONTENT_LENGTH) {
            lengthScore = 0.1 + 0.4 * content.length() / OPTIMAL_CONTENT_LENGTH;
        } else {
            lengthScore = 0.5 + 0.2 * Math.min(1.0, (content.length() - OPTIMAL_CONTENT_LENGTH) / 2000.0);
        }

        // 2. 标签完整性 (0-0.2)
        double tagScore = 0.0;
        if (note.getTags() != null && !note.getTags().isEmpty()) {
            tagScore = Math.min(0.2, note.getTags().split(",").length * 0.05);
        }

        // 3. 标题完整性 (0-0.15)
        double titleScore = note.getTitle() != null && note.getTitle().length() > 5 ? 0.15 : 0.05;

        // 4. 内容丰富度 (0-0.15)
        double detailScore = 0.0;
        if (content.contains("建议") || content.contains("推荐")) detailScore += 0.05;
        if (content.contains("门票") || content.contains("价格")) detailScore += 0.05;
        if (content.contains("时间") || content.contains("小时")) detailScore += 0.05;

        return Math.min(1.0, lengthScore + tagScore + titleScore + detailScore);
    }

    private String generateQualityReason(TravelNote note) {
        StringBuilder sb = new StringBuilder();
        if (note.getTags() != null && !note.getTags().isEmpty()) {
            sb.append("🏷️ 标签: ").append(note.getTags().split(",").length).append("个");
        }
        if (note.getContent() != null) {
            sb.append(" | 📝 ").append(note.getContent().length()).append("字");
        }
        return !sb.isEmpty() ? sb.toString() : "内容较简略";
    }

    /**
     * 计算性价比评分
     */
    private double calculateCostEfficiencyScore(TravelNote note, TravelProfile profile) {
        String tags = nullToEmpty(note.getTags()).toLowerCase();
        String content = nullToEmpty(note.getContent()).toLowerCase();

        String costPref = profile != null ? profile.getCostPreference() : "medium";

        double score = 0.5;

        if (costPref.equals("very_low") || costPref.equals("low")) {
            if (tags.contains("免费") || content.contains("免费")) score += 0.3;
            if (tags.contains("低价") || content.contains("便宜")) score += 0.2;
            if (content.contains("学生票") || content.contains("优惠")) score += 0.15;
            if (content.contains("团购")) score += 0.1;
        } else if (costPref.equals("high")) {
            score = 0.6;
        }

        return Math.min(1.0, score);
    }

    private String generateCostReason(TravelNote note, TravelProfile profile) {
        String tags = nullToEmpty(note.getTags());
        String costPref = profile != null ? profile.getCostPreference() : "medium";

        if (tags.contains("免费")) return "💰 免费景点";
        if (tags.contains("低价")) return "💰 低价推荐";
        if (costPref.equals("high")) return "💰 品质优先";
        return "💰 性价比适中";
    }

    // ==================== 辅助方法 ====================

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    // ==================== 内部类 ====================

    /**
     * 意图类型 - 基于用户当前意图推断的出行类型
     *
     * <p>这是对用户"当前想要什么"的分类，而非用户的长期偏好。</p>
     * <p>例如：用户说"带娃玩"，意图类型=亲子；用户说"美食之旅"，意图类型=美食</p>
     *
     * <p>区别于 UserPreference（用户画像），IntentType 描述的是用户当前的意图。</p>
     */
    @lombok.Data
    @lombok.Builder
    private static class TravelProfile {
        private String name;                          // 类型名称
        private List<String> keywords;                 // 意图关键词
        private Set<String> positiveTags;              // 正向标签
        private Set<String> negativeTags;              // 负向标签
        private Set<String> preferredAttractionTypes;   // 偏好的景点类型
        private Set<String> dislikedAttractionTypes;   // 不喜欢的景点类型
        private String pacePreference;                 // 游玩节奏: relaxed/active/budget/foodie/cultural/photo/shopping
        private String costPreference;                 // 消费偏好: very_low/low/medium/high
    }

    /**
     * 用户偏好 - 从用户历史游记中提取的长期偏好特征
     *
     * <p>这是真正的"用户画像"，反映用户的旅行风格，而非临时意图。</p>
     * <p>例如：用户经常写美食类游记 → 偏好美食；用户偏好深度体验 → 偏好详细攻略</p>
     */
    @lombok.Data
    @lombok.Builder
    private static class UserPreference {
        private int totalNotes;              // 游记总数
        private int avgContentLength;         // 平均内容长度
        private List<String> topTags;         // 热门标签 Top 5

        public String getSummary() {
            if (topTags == null || topTags.isEmpty()) {
                return "新用户";
            }
            return "偏好: " + String.join(", ", topTags.stream().limit(3).toArray(String[]::new));
        }
    }

    /**
     * 评分后的游记
     */
    @lombok.Data
    @lombok.Builder
    public static class ScoredNote {
        private TravelNote note;
        private double totalScore;           // 综合评分

        // 分项评分
        private double freshnessScore;       // 新鲜度
        private double intentScore;          // 意图匹配
        private double preferenceScore;       // 用户偏好匹配
        private double qualityScore;          // 内容质量
        private double costScore;            // 性价比

        // 评分理由
        private String freshnessReason;
        private String intentReason;
        private String preferenceReason;
        private String qualityReason;
        private String costReason;
    }

    /**
     * 评分结果
     */
    @lombok.Data
    @lombok.Builder
    public static class RankingResult {
        private boolean success;
        private String location;
        private String userIntent;
        private String intentTypeName;  // 意图类型名称
        private List<ScoredNote> rankedNotes;

        public static RankingResult of(List<ScoredNote> scored, String location, String intent, TravelProfile intentType) {
            return RankingResult.builder()
                    .success(true)
                    .location(location)
                    .userIntent(intent)
                    .intentTypeName(intentType != null ? intentType.getName() : "通用")
                    .rankedNotes(scored)
                    .build();
        }

        public static RankingResult empty(String location) {
            return RankingResult.builder()
                    .success(false)
                    .location(location)
                    .rankedNotes(List.of())
                    .build();
        }

        public boolean isEmpty() {
            return rankedNotes == null || rankedNotes.isEmpty();
        }

        public TravelNote getBestMatch() {
            if (isEmpty()) return null;
            return rankedNotes.get(0).getNote();
        }

        public List<TravelNote> getTopNotes(int n) {
            if (isEmpty()) return List.of();
            return rankedNotes.stream()
                    .limit(n)
                    .map(ScoredNote::getNote)
                    .collect(Collectors.toList());
        }

        /**
         * 格式化为 MCP 返回文本
         */
        public String toMcpText() {
            if (isEmpty()) {
                return "📝 暂无【" + location + "】相关的游记记录\n" +
                       "提示：可以尝试上传游记来丰富内容库";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🗺️ 【规则推理推荐结果】\n");
            sb.append("📍 目的地：").append(location).append("\n");
            if (userIntent != null) {
                sb.append("🎯 匹配意图：").append(userIntent).append("\n");
            }
            if (intentTypeName != null) {
                sb.append("🏷️ 意图类型：").append(intentTypeName).append("\n");
            }
            sb.append("━".repeat(40)).append("\n\n");

            // 最佳推荐
            ScoredNote best = rankedNotes.get(0);
            TravelNote bestNote = best.getNote();
            sb.append("✅ 【最佳推荐】\n");
            sb.append("📌 ").append(nullToEmpty(bestNote.getTitle())).append("\n");
            sb.append("⭐ 综合评分：").append(String.format("%.2f", best.totalScore * 100)).append("/100\n");
            sb.append("\n📊 评分明细：\n");
            sb.append("   • 新鲜度：").append(String.format("%.1f%%", best.freshnessScore * 100))
                    .append(" | ").append(best.freshnessReason).append("\n");
            sb.append("   • 意图匹配：").append(String.format("%.1f%%", best.intentScore * 100))
                    .append(" | ").append(best.intentReason).append("\n");
            sb.append("   • 偏好匹配：").append(String.format("%.1f%%", best.preferenceScore * 100))
                    .append(" | ").append(best.preferenceReason).append("\n");
            sb.append("   • 内容质量：").append(String.format("%.1f%%", best.qualityScore * 100))
                    .append(" | ").append(best.qualityReason).append("\n");
            sb.append("   • 性价比：").append(String.format("%.1f%%", best.costScore * 100))
                    .append(" | ").append(best.costReason).append("\n");

            sb.append("\n📝 内容预览：\n");
            String content = nullToEmpty(bestNote.getContent());
            sb.append(content.length() > 300 ? content.substring(0, 300) + "..." : content);
            sb.append("\n");

            // 备选推荐
            if (rankedNotes.size() > 1) {
                sb.append("\n📋 【备选推荐】\n");
                for (int i = 1; i < Math.min(rankedNotes.size(), 4); i++) {
                    ScoredNote alt = rankedNotes.get(i);
                    TravelNote altNote = alt.getNote();
                    sb.append("   ").append(i).append(". ")
                            .append(nullToEmpty(altNote.getTitle()))
                            .append(" (⭐").append(String.format("%.1f", alt.totalScore * 100)).append(")");
                    if (altNote.getTags() != null) {
                        sb.append(" [").append(altNote.getTags()).append("]");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        }

        private String nullToEmpty(String s) {
            return s != null ? s : "";
        }
    }
}
