package com.example.smartassistant.tools;

import com.example.smartassistant.entity.TravelNote;
import com.example.smartassistant.entity.TravelNoteChunk;
import com.example.smartassistant.service.TravelNoteService;
import com.example.smartassistant.service.TravelNoteMatchService;
import com.example.smartassistant.service.TravelRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 旅行攻略 RAG MCP 工具
 *
 * <p>提供用户游记/攻略的实时检索功能，用于：</p>
 * <ul>
 *     <li>基于用户历史攻略的个性化推荐</li>
 *     <li>检索用户去过的地方、推荐的活动</li>
 *     <li>获取用户的旅行偏好和经验</li>
 * </ul>
 *
 * <p>典型场景：</p>
 * <ul>
 *     <li>用户问"杭州有什么好玩" → 检索用户是否去过杭州，分享经验</li>
 *     <li>用户问"周末去哪" → 结合用户历史偏好推荐</li>
 *     <li>用户说"我上次去了..." → 检索相关游记作为参考</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TravelRagTool {

    private final TravelNoteService travelNoteService;
    private final TravelNoteMatchService travelNoteMatchService;
    private final TravelRagService travelRagService;

    @Value("${travel.rag.enabled:false}")
    private boolean ragEnabled;

    /**
     * ⭐ 核心工具：根据用户意图，从指定地点的游记中智能筛选最佳推荐
     *
     * <p>当用户表达了明确的出行意图（如"带娃"、"情侣"、"美食"、"拍照"），
     * 此工具会分析该地点所有游记，通过 LLM 推理找出最匹配的那一篇。</p>
     *
     * <p>典型场景：</p>
     * <ul>
     *     <li>用户问"杭州哪里适合带娃玩" → 分析多篇杭州游记，找出亲子相关内容</li>
     *     <li>用户问"周末情侣出行推荐" → 分析多篇游记，找出浪漫/情侣相关内容</li>
     *     <li>用户问"杭州美食推荐" → 筛选包含美食体验的游记</li>
     * </ul>
     *
     * <p><b>参数校验</b>：当 location 或 userIntent 缺失时，返回友好的询问提示，引导用户补充信息。</p>
     *
     * @param userId 用户ID
     * @param location 目的地/地点，如"杭州"、"西湖"。缺失时返回询问提示
     * @param userIntent 用户的当前出行意图，如"亲子游玩"、"美食推荐"、"拍照打卡"。缺失时返回询问提示
     * @return 最佳匹配游记 + LLM 推理过程 + 备选推荐，或引导用户补充信息的提示
     */
    @Tool(description = "⭐【核心工具】根据用户意图从指定地点的游记中智能筛选最佳推荐。当用户表达明确出行意图时优先使用。返回最佳游记+推理过程+备选。location 地点和 userIntent 意图缺失时会返回询问提示。")
    public String selectBestTravelNotes(
            @ToolParam(description = "用户ID", required = true) Long userId,
            @ToolParam(description = "目的地或地点，如杭州、西湖（缺失时返回询问提示）", required = false) String location,
            @ToolParam(description = "用户当前出行意图，如'带娃游玩'、'情侣出行'、'美食'、'拍照打卡'（缺失时返回询问提示）", required = false) String userIntent
    ) {
        log.info("[TravelRagTool] selectBestTravelNotes: userId={}, location={}, intent={}",
                userId, location, userIntent);

        // ========== 参数校验：缺失关键信息时返回询问提示 ==========
        boolean locationMissing = location == null || location.trim().isEmpty();
        boolean intentMissing = userIntent == null || userIntent.trim().isEmpty();

        if (locationMissing && intentMissing) {
            return """
                    ❓ 为了给您个性化的游记推荐，请告诉我：

                    📍 目的地：您想去哪里玩？（如：杭州、成都、上海...）
                    🎯 出行目的：您是带娃出行、情侣约会、美食探店，还是其他？（如：亲子游、情侣、美食、拍照打卡...）

                    告诉我这些信息，我可以帮您从真实游记中找到最匹配的推荐！
                    """;
        }

        if (locationMissing) {
            return """
                    ❓ 请告诉我您想去哪里玩？

                    例如：杭州、成都、上海、北京...

                    知道目的地后，我可以帮您从去过那里的游记中找到最适合您的推荐。
                    """;
        }

        if (intentMissing) {
            return """
                    ❓ 请告诉我您的出行目的？

                    例如：
                    • 带娃游玩、亲子游
                    • 情侣约会、浪漫之旅
                    • 美食探店、小吃之旅
                    • 拍照打卡、网红景点
                    • 文化探索、历史古迹

                    告诉我您的意图，我可以帮您找到最匹配的游记推荐！
                    """;
        }

        // ========== 参数校验通过，执行搜索 ==========
        if (!ragEnabled) {
            return "⚠️ 攻略RAG功能未启用，请在配置中开启 travel.rag.enabled";
        }

        try {
            TravelNoteMatchService.MatchResult result =
                    travelNoteMatchService.selectBestTravelNote(userId, location, userIntent);
            return result.toMcpText();
        } catch (Exception e) {
            log.error("[TravelRagTool] selectBestTravelNotes 失败: {}", e.getMessage());
            return "⚠️ 筛选失败: " + e.getMessage();
        }
    }

    /**
     * 查询用户的历史游记/攻略
     *
     * <p>获取用户上传的所有游记列表</p>
     *
     * @param userId 用户ID
     * @param location 筛选地点（可选），如"杭州"、"北京"
     * @return 用户游记列表
     */
    @Tool(description = "查询用户的历史游记列表，可按地点筛选")
    public String getUserTravelNotes(
            @ToolParam(description = "用户ID", required = true) Long userId,
            @ToolParam(description = "筛选地点，如杭州、北京（可选）", required = false) String location
    ) {
        log.info("[TravelRagTool] 查询用户游记: userId={}, location={}", userId, location);

        if (!ragEnabled) {
            return "⚠️ 攻略RAG功能未启用";
        }

        try {
            List<TravelNote> notes;
            if (location != null && !location.isEmpty()) {
                notes = travelNoteService.searchByLocation(userId, location);
            } else {
                notes = travelNoteService.getUserNotes(userId);
            }

            if (notes.isEmpty()) {
                return "📝 暂无" + (location != null ? location : "") + "相关的游记记录\n" +
                       "提示：用户可以通过上传游记来获得更个性化的推荐";
            }

            return formatNotesList(notes);
        } catch (Exception e) {
            log.error("[TravelRagTool] 查询游记失败: {}", e.getMessage());
            return "⚠️ 查询游记失败: " + e.getMessage();
        }
    }

    /**
     * 检索与当前问题相关的用户攻略
     *
     * <p>基于语义相似度检索，返回最相关的攻略片段</p>
     *
     * @param userId 用户ID
     * @param location 目的地/地点
     * @param query 用户的问题或需求描述
     * @return 相关攻略片段
     */
    @Tool(description = "语义检索用户的历史攻略，返回与当前问题最相关的旅行经验")
    public String searchRelevantTravelNotes(
            @ToolParam(description = "用户ID", required = true) Long userId,
            @ToolParam(description = "目的地或地点，如杭州、西湖", required = true) String location,
            @ToolParam(description = "用户的问题或需求，如'推荐美食'、'最佳路线'", required = true) String query
    ) {
        log.info("[TravelRagTool] 语义检索: userId={}, location={}, query={}", userId, location, query);

        if (!ragEnabled) {
            return "⚠️ 攻略RAG功能未启用";
        }

        try {
            // 检索相关攻略片段
            List<TravelNoteChunk> chunks = travelRagService.retrieveRelevantChunks(location, query, userId);

            if (chunks.isEmpty()) {
                return "📝 未找到与【" + location + "】相关的攻略记录\n" +
                       "问题参考：" + query + "\n" +
                       "提示：用户可以通过上传游记来丰富攻略库";
            }

            // 构建增强上下文（按 contentType 分离正文和建议）
            List<TravelNoteChunk> foodChunks = travelRagService.retrieveFoodSuggestions(location, userId);
            String context = travelRagService.buildRagContext(location, chunks, foodChunks);

            return "📖 找到 " + chunks.size() + " 条相关攻略：\n" +
                   "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                   context +
                   "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                   "💡 可结合上述用户经验进行个性化推荐";
        } catch (Exception e) {
            log.error("[TravelRagTool] 检索失败: {}", e.getMessage());
            return "⚠️ 检索攻略失败: " + e.getMessage();
        }
    }

    /**
     * 获取用户旅行偏好摘要
     *
     * <p>基于用户历史游记，提取旅行偏好标签</p>
     *
     * @param userId 用户ID
     * @param location 可选，指定地点的偏好
     * @return 旅行偏好摘要
     */
    @Tool(description = "根据用户历史游记，提取旅行偏好和经验摘要")
    public String getUserTravelPreferences(
            @ToolParam(description = "用户ID", required = true) Long userId,
            @ToolParam(description = "指定地点的偏好（可选）", required = false) String location
    ) {
        log.info("[TravelRagTool] 获取旅行偏好: userId={}, location={}", userId, location);

        if (!ragEnabled) {
            return "⚠️ 攻略RAG功能未启用";
        }

        try {
            List<TravelNote> notes;
            if (location != null && !location.isEmpty()) {
                notes = travelNoteService.searchByLocation(userId, location);
            } else {
                notes = travelNoteService.getUserNotes(userId);
            }

            if (notes.isEmpty()) {
                return "📝 暂无旅行偏好数据\n" +
                       "建议：用户可以上传游记来建立偏好档案";
            }

            return extractTravelPreferences(notes, location);
        } catch (Exception e) {
            log.error("[TravelRagTool] 获取偏好失败: {}", e.getMessage());
            return "⚠️ 获取偏好失败: " + e.getMessage();
        }
    }

    /**
     * 增强旅行规划提示
     *
     * <p>结合用户历史攻略，生成增强的提示词</p>
     *
     * @param userId 用户ID
     * @param location 目的地
     * @param originalPrompt 原始用户问题
     * @return 增强后的提示词
     */
    @Tool(description = "结合用户历史攻略，增强旅行规划提示词，返回个性化建议")
    public String enhanceTravelPlanning(
            @ToolParam(description = "用户ID", required = true) Long userId,
            @ToolParam(description = "目的地", required = true) String location,
            @ToolParam(description = "用户原始问题或需求", required = true) String originalPrompt
    ) {
        log.info("[TravelRagTool] 增强规划: userId={}, location={}", userId, location);

        if (!ragEnabled) {
            return originalPrompt;
        }

        try {
            String enhanced = travelRagService.enhancePrompt(originalPrompt, location, originalPrompt, userId);

            // 检查是否真的增强了
            if (enhanced.equals(originalPrompt)) {
                return originalPrompt + "\n\n[系统] 暂无【" + location + "】相关攻略，将基于通用知识生成建议";
            }

            return enhanced;
        } catch (Exception e) {
            log.error("[TravelRagTool] 增强失败: {}", e.getMessage());
            return originalPrompt;
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 格式化游记列表
     */
    private String formatNotesList(List<TravelNote> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("📚 找到 ").append(notes.size()).append(" 篇游记：\n\n");

        for (int i = 0; i < notes.size(); i++) {
            TravelNote note = notes.get(i);
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("【").append(i + 1).append("】").append(note.getTitle() != null ? note.getTitle() : "无标题").append("\n");

            if (note.getLocation() != null) {
                sb.append("📍 地点：").append(note.getLocation()).append("\n");
            }
            if (note.getTags() != null && !note.getTags().isEmpty()) {
                sb.append("🏷️ 标签：").append(note.getTags()).append("\n");
            }
            if (note.getCreatedAt() != null) {
                sb.append("📅 时间：").append(note.getCreatedAt().toLocalDate()).append("\n");
            }
            if (note.getContent() != null) {
                String preview = note.getContent().length() > 100
                        ? note.getContent().substring(0, 100) + "..."
                        : note.getContent();
                sb.append("📝 预览：").append(preview).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 提取旅行偏好
     */
    private String extractTravelPreferences(List<TravelNote> notes, String location) {
        StringBuilder sb = new StringBuilder();

        if (location != null) {
            sb.append("【").append(location).append("】旅行偏好\n");
        } else {
            sb.append("📊 旅行偏好摘要\n");
        }
        sb.append("═══════════════════════════════════\n\n");

        // 统计地点
        long totalNotes = notes.size();
        long notesWithLocation = notes.stream()
                .filter(n -> n.getLocation() != null && !n.getLocation().isEmpty())
                .count();

        sb.append("📈 基本统计：\n");
        sb.append("  • 游记数量：").append(totalNotes).append(" 篇\n");
        sb.append("  • 涉及地点：").append(notesWithLocation).append(" 个\n\n");

        // 提取热门标签
        String allTags = notes.stream()
                .map(TravelNote::getTags)
                .filter(t -> t != null && !t.isEmpty())
                .collect(Collectors.joining(","));

        if (!allTags.isEmpty()) {
            sb.append("🏷️ 偏好标签：\n");
            java.util.Arrays.stream(allTags.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
                    .entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(e -> sb.append("  • ").append(e.getKey())
                            .append(" (").append(e.getValue()).append("次)\n"));
        }

        // 提取去过的地方
        sb.append("\n🌍 去过的地点：\n");
        notes.stream()
                .map(TravelNote::getLocation)
                .filter(l -> l != null && !l.isEmpty())
                .distinct()
                .limit(10)
                .forEach(l -> sb.append("  • ").append(l).append("\n"));

        return sb.toString();
    }
}
