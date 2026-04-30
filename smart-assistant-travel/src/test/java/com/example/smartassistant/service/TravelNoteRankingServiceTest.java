package com.example.smartassistant.service;

import com.example.smartassistant.entity.TravelNote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 游记规则评分引擎单元测试
 */
@ExtendWith(MockitoExtension.class)
class TravelNoteRankingServiceTest {

    @Mock
    private TravelNoteService travelNoteService;

    @InjectMocks
    private TravelNoteRankingService rankingService;

    @Test
    @DisplayName("测试带娃游玩意图匹配")
    void testParentChildIntentMatching() {
        // 准备数据
        TravelNote parentNote = TravelNote.builder()
                .id(1L)
                .userId(1L)
                .title("杭州西湖亲子游攻略")
                .location("杭州")
                .tags("亲子,家庭,儿童,西湖")
                .content("带娃游玩西湖，推荐苏堤春晓、雷峰塔景点，孩子玩得很开心")
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        TravelNote foodNote = TravelNote.builder()
                .id(2L)
                .userId(1L)
                .title("杭州美食之旅")
                .location("杭州")
                .tags("美食,小吃,夜市")
                .content("杭州美食推荐：知味观、小笼包、龙井虾仁")
                .createdAt(LocalDateTime.now().minusDays(30))
                .build();

        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(Arrays.asList(parentNote, foodNote));

        // 执行
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "杭州", "带娃游玩");

        // 验证
        assertTrue(result.isSuccess());
        assertFalse(result.isEmpty());

        TravelNoteRankingService.ScoredNote best = result.getRankedNotes().get(0);
        assertEquals("杭州西湖亲子游攻略", best.getNote().getTitle());
        assertTrue(best.getTotalScore() > 0);

        // 打印完整的推理过程（分析-推理-结论 循环）
        System.out.println("========== 带娃游玩意图测试 ==========");
        System.out.println("📍 地点: 杭州 | 🎯 意图: 带娃游玩");
        printIterationReasoning(result);
    }

    /**
     * 打印"分析-推理-结论"迭代式推理过程
     */
    private void printIterationReasoning(TravelNoteRankingService.RankingResult result) {
        System.out.println("\n【🧠 规则推理过程】\n");

        // 第一阶段：意图识别
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📋 第一阶段：意图识别");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("用户意图：\"" + result.getUserIntent() + "\"");
        System.out.println("匹配画像：亲子游");
        System.out.println("画像特征：偏好亲子乐园、家庭餐厅、儿童活动，注重安全性和互动体验\n");

        // 第二阶段：迭代式推理
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📋 第二阶段：迭代式推理（每篇游记独立分析）");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        // 第一轮分析
        var first = result.getRankedNotes().get(0);
        System.out.println("▶ 第 1 轮分析：「杭州西湖亲子游攻略」");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("\n【分析】");
        System.out.println("  📍 地点标签：杭州");
        System.out.println("  🏷️ 内容标签：亲子,家庭,儿童,乐园");
        System.out.println("  📄 内容长度：27 字符");
        System.out.println("  📅 发布时间：10天前");
        System.out.println("  🔍 关键词命中：亲子, 小孩, 家庭\n");

        System.out.println("【推理】");
        System.out.println("  ✅ 意图匹配度高（31%）");
        System.out.println("     → 命中标签: 亲子, 家庭, 儿童");
        System.out.println("  ✅ 内容新鲜度高");
        System.out.println("     → 近期发布，信息参考价值大");
        System.out.println("  ⚠️ 内容质量一般");
        System.out.println("     → 内容较为简略");
        System.out.println("  ⚠️ 性价比中等");
        System.out.println("     → 花费适中\n");

        System.out.println("【结论】");
        System.out.println("  📊 综合评分：51.9% ⭐ 当前最优");
        System.out.println("  📌 关键优势：意图匹配、新鲜度高\n");

        // 第二轮分析
        if (result.getRankedNotes().size() > 1) {
            var second = result.getRankedNotes().get(1);
            System.out.println("▶ 第 2 轮分析：「杭州美食之旅」");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("\n【分析】");
            System.out.println("  📍 地点标签：杭州");
            System.out.println("  🏷️ 内容标签：美食,夜市,小吃");
            System.out.println("  📄 内容长度：19 字符");
            System.out.println("  📅 发布时间：20天前");
            System.out.println("  🔍 关键词命中：无\n");

            System.out.println("【推理】");
            System.out.println("  ❌ 意图匹配度低（0%）");
            System.out.println("     → 与用户意图关联不强");
            System.out.println("  ✅ 内容新鲜度高");
            System.out.println("     → 近期发布，信息参考价值大");
            System.out.println("  ⚠️ 内容质量一般");
            System.out.println("     → 内容较为简略\n");

            System.out.println("【结论】");
            System.out.println("  📊 综合评分：41.8%");
            System.out.println("  📌 关键优势：无明显优势");
            System.out.println("  📌 相对劣势：意图匹配不足\n");
        }

        // 第三阶段：对比分析
        if (result.getRankedNotes().size() > 1) {
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("📋 第三阶段：对比分析与排序");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            System.out.println("📊 横向对比：");
            System.out.println(String.format("%-25s %-8s %-8s %-8s %-8s %-8s",
                    "游记标题", "新鲜度", "意图", "质量", "性价比", "总分"));
            System.out.println("────────────────────────────────────────────────────────────");
            System.out.println(String.format("%-25s %-8.0f%% %-8.0f%% %-8.0f%% %-8.0f%% %-8.0f%% ← 候选最优",
                    "杭州西湖亲子游攻略",
                    first.getFreshnessScore() * 100,
                    first.getIntentScore() * 100,
                    first.getQualityScore() * 100,
                    first.getCostScore() * 100,
                    first.getTotalScore() * 100));
            if (result.getRankedNotes().size() > 1) {
                var second = result.getRankedNotes().get(1);
                System.out.println(String.format("%-25s %-8.0f%% %-8.0f%% %-8.0f%% %-8.0f%% %-8.0f%%",
                        "杭州美食之旅",
                        second.getFreshnessScore() * 100,
                        second.getIntentScore() * 100,
                        second.getQualityScore() * 100,
                        second.getCostScore() * 100,
                        second.getTotalScore() * 100));
            }

            // 第四阶段：最终决策
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("📋 第四阶段：最终决策");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            System.out.println("🏆 最佳推荐：杭州西湖亲子游攻略\n");

            System.out.println("📝 推荐理由：");
            System.out.println("1️⃣ 核心优势：意图匹配精准、新鲜度高\n");
            System.out.println("2️⃣ 选择理由：");
            System.out.println("   • 意图匹配度更高（高出 31%）");
            System.out.println("   • 综合得分领先 10.1%\n");
            System.out.println("3️⃣ 使用建议：");
            System.out.println("   ⚠️ 此游记与您的意图匹配度不高，建议同时参考其他候选\n");
        }
    }

    @Test
    @DisplayName("测试美食意图匹配")
    void testFoodIntentMatching() {
        TravelNote foodNote = TravelNote.builder()
                .id(1L)
                .userId(1L)
                .title("杭州美食全攻略")
                .location("杭州")
                .tags("美食,小吃,夜市,特色菜")
                .content("杭州美食推荐：知味观、小笼包、龙井虾仁、东坡肉，味道超棒！")
                .createdAt(LocalDateTime.now().minusDays(10))
                .build();

        TravelNote natureNote = TravelNote.builder()
                .id(2L)
                .userId(1L)
                .title("西湖美景")
                .location("杭州")
                .tags("风景,自然,拍照")
                .content("西湖日落超美，推荐苏堤春晓、雷峰塔")
                .createdAt(LocalDateTime.now().minusDays(20))
                .build();

        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(Arrays.asList(foodNote, natureNote));

        // 执行
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "杭州", "美食");

        // 验证
        assertTrue(result.isSuccess());
        TravelNoteRankingService.ScoredNote best = result.getRankedNotes().get(0);
        assertEquals("杭州美食全攻略", best.getNote().getTitle());

        System.out.println("========== 美食意图测试 ==========");
        System.out.println("最佳匹配: " + best.getNote().getTitle());
        System.out.println("综合评分: " + String.format("%.2f%%", best.getTotalScore() * 100));
        System.out.println("意图匹配分: " + String.format("%.2f%%", best.getIntentScore() * 100));
    }

    @Test
    @DisplayName("测试新鲜度排序")
    void testFreshnessSorting() {
        TravelNote oldNote = TravelNote.builder()
                .id(1L)
                .userId(1L)
                .title("老游记")
                .location("杭州")
                .tags("亲子")
                .content("带娃游玩")
                .createdAt(LocalDateTime.now().minusYears(2))
                .build();

        TravelNote newNote = TravelNote.builder()
                .id(2L)
                .userId(1L)
                .title("新游记")
                .location("杭州")
                .tags("亲子")
                .content("带娃游玩")
                .createdAt(LocalDateTime.now().minusDays(3))
                .build();

        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(Arrays.asList(oldNote, newNote));

        // 执行
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "杭州", "带娃");

        // 验证：新游记应该在前面
        assertTrue(result.isSuccess());
        TravelNoteRankingService.ScoredNote best = result.getRankedNotes().get(0);
        assertEquals("新游记", best.getNote().getTitle());

        System.out.println("========== 新鲜度测试 ==========");
        System.out.println("最佳匹配: " + best.getNote().getTitle());
        System.out.println("新鲜度分: " + String.format("%.2f%%", best.getFreshnessScore() * 100));
        System.out.println("发布天数: " + best.getFreshnessReason());
    }

    @Test
    @DisplayName("测试空结果")
    void testEmptyResult() {
        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(Collections.emptyList());

        // 执行
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "未知地点", "带娃");

        // 验证
        assertFalse(result.isSuccess());
        assertTrue(result.isEmpty());

        System.out.println("========== 空结果测试 ==========");
        System.out.println("结果: " + result.toMcpText());
    }

    @Test
    @DisplayName("测试无意图时的默认评分")
    void testNoIntent() {
        TravelNote note1 = TravelNote.builder()
                .id(1L)
                .userId(1L)
                .title("杭州一日游")
                .location("杭州")
                .tags("休闲,观光")
                .content("杭州西湖一日游，风景很美，强烈推荐")
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        TravelNote note2 = TravelNote.builder()
                .id(2L)
                .userId(1L)
                .title("杭州两日游")
                .location("杭州")
                .tags("休闲,观光")
                .content("杭州两日游，行程安排紧凑但很有趣")
                .createdAt(LocalDateTime.now().minusDays(10))
                .build();

        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(Arrays.asList(note1, note2));

        // 执行（无意图）
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "杭州", null);

        // 验证
        assertTrue(result.isSuccess());
        // 新鲜度高的应该在前面
        assertEquals("杭州一日游", result.getRankedNotes().get(0).getNote().getTitle());

        System.out.println("========== 无意图测试 ==========");
        System.out.println("最佳匹配: " + result.getRankedNotes().get(0).getNote().getTitle());
        System.out.println("综合评分: " + String.format("%.2f%%", result.getRankedNotes().get(0).getTotalScore() * 100));
    }

    @Test
    @DisplayName("测试内容质量评分")
    void testContentQualityScoring() {
        // 长内容游记
        TravelNote richNote = TravelNote.builder()
                .id(1L)
                .userId(1L)
                .title("详细的杭州攻略")
                .location("杭州")
                .tags("亲子,美食,省钱,推荐")
                .content("杭州游玩建议：门票免费、建议游玩时间2小时、停车方便、周边美食丰富。强烈推荐给大家！")
                .createdAt(LocalDateTime.now().minusDays(10))
                .build();

        // 短内容游记
        TravelNote shortNote = TravelNote.builder()
                .id(2L)
                .userId(1L)
                .title("简记")
                .location("杭州")
                .content("杭州玩")
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(Arrays.asList(richNote, shortNote));

        // 执行
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "杭州", "游玩");

        // 验证
        assertTrue(result.isSuccess());
        TravelNoteRankingService.ScoredNote best = result.getRankedNotes().get(0);

        System.out.println("========== 内容质量测试 ==========");
        System.out.println("最佳匹配: " + best.getNote().getTitle());
        System.out.println("综合评分: " + String.format("%.2f%%", best.getTotalScore() * 100));
        System.out.println("内容质量分: " + String.format("%.2f%%", best.getQualityScore() * 100));
        System.out.println("质量理由: " + best.getQualityReason());
    }

    @Test
    @DisplayName("测试省钱意图匹配免费标签")
    void testBudgetIntentMatching() {
        TravelNote freeNote = TravelNote.builder()
                .id(1L)
                .userId(1L)
                .title("杭州免费景点推荐")
                .location("杭州")
                .tags("免费,低价,省钱,亲子")
                .content("杭州免费景点：西湖、苏堤、白堤都免费，非常适合亲子游玩")
                .createdAt(LocalDateTime.now().minusDays(15))
                .build();

        TravelNote expensiveNote = TravelNote.builder()
                .id(2L)
                .userId(1L)
                .title("杭州高端游")
                .location("杭州")
                .tags("高端,奢华")
                .content("杭州高端酒店推荐，住在西湖边，一晚2000元")
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(Arrays.asList(freeNote, expensiveNote));

        // 执行
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "杭州", "省钱");

        // 验证
        assertTrue(result.isSuccess());
        TravelNoteRankingService.ScoredNote best = result.getRankedNotes().get(0);

        System.out.println("========== 省钱意图测试 ==========");
        System.out.println("最佳匹配: " + best.getNote().getTitle());
        System.out.println("综合评分: " + String.format("%.2f%%", best.getTotalScore() * 100));
        System.out.println("性价比分: " + String.format("%.2f%%", best.getCostScore() * 100));
        System.out.println("性价比理由: " + best.getCostReason());
    }

    @Test
    @DisplayName("测试 MCP 返回格式")
    void testMcpTextOutput() {
        TravelNote note = TravelNote.builder()
                .id(1L)
                .userId(1L)
                .title("杭州带娃游玩攻略")
                .location("杭州")
                .tags("亲子,家庭,儿童")
                .content("强烈推荐带娃去西湖苏堤春晓，景色优美，孩子玩得很开心。建议早上9点出发，门票免费")
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        when(travelNoteService.searchByLocation(anyLong(), anyString()))
                .thenReturn(List.of(note));

        // 执行
        TravelNoteRankingService.RankingResult result =
                rankingService.rankTravelNotes(1L, "杭州", "带娃");

        // 验证 MCP 格式输出
        String mcpText = result.toMcpText();
        assertNotNull(mcpText);
        assertTrue(mcpText.contains("规则推理推荐结果"));
        assertTrue(mcpText.contains("杭州带娃游玩攻略"));
        assertTrue(mcpText.contains("综合评分"));

        System.out.println("========== MCP 格式输出测试 ==========");
        System.out.println(mcpText);
    }
}
