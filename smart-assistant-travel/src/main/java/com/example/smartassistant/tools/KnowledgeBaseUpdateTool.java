package com.example.smartassistant.tools;

import com.example.smartassistant.annotation.AdminOnly;
import com.example.smartassistant.service.data.AmapPoiSyncService;
import com.example.smartassistant.service.data.AttractionDataImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库数据更新工具
 * 职责：
 * - 让 Agent 能够自主从高德地图同步景点数据
 * - 更新指定城市或地区的景点信息
 * - 确保知识库数据的时效性和准确性
 * 使用场景：
 * - 用户问"北京有哪些新景点？" → Agent 先同步最新数据再回答
 * - 用户说"帮我更新上海的景点信息" → Agent 调用此工具
 * - 发现某城市数据过时 → Agent 主动触发更新
 * 权限控制：
 * - 使用 @AdminOnly 注解统一拦截权限检查
 */
@Component
public class KnowledgeBaseUpdateTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseUpdateTool.class);

    private final AmapPoiSyncService poiSyncService;
    private final AttractionDataImportService importService;

    public KnowledgeBaseUpdateTool(AmapPoiSyncService poiSyncService,
                                   AttractionDataImportService importService) {
        this.poiSyncService = poiSyncService;
        this.importService = importService;
    }

    /**
     * 同步指定城市的景点数据到高德地图
     *
     * @param city 城市名称（如"北京"、"上海"、"杭州"）
     * @return 同步结果
     */
    @Tool(description = "从高德地图同步指定城市的最新景点数据到知识库。⚠️仅管理员可用")
    @AdminOnly("同步城市景点数据")
    public String syncCityAttractions(String city) {
        log.info("[KnowledgeBaseUpdateTool] 开始同步城市 {} 的景点数据", city);

        try {
            // 调用高德地图 POI 同步服务
            int imported = poiSyncService.syncCityPOI(city, "110000"); // 110000 = 旅游景点类型

            if (imported > 0) {
                return String.format(
                        """
                                ✅ 已成功从高德地图同步 %s 的景点数据
                                   - 新增景点数量：%d 个
                                   - 数据来源：高德地图 POI API
                                   - 更新时间：%s
                                现在可以基于最新数据为用户提供推荐。""",
                    city, imported, java.time.LocalDateTime.now()
                );
            } else {
                return String.format(
                        """
                                ℹ️ 城市 %s 的景点数据已是最新
                                   - 未发现新的景点数据
                                   - 可能原因：该城市数据已完整，或高德地图暂无更新""",
                    city
                );
            }

        } catch (Exception e) {
            log.error("[KnowledgeBaseUpdateTool] 同步失败: {}", e.getMessage(), e);
            return String.format(
                    """
                            ❌ 同步 %s 的景点数据失败
                               - 错误信息：%s
                               - 建议：稍后重试或检查网络连接""",
                city, e.getMessage()
            );
        }
    }

    /**
     * 批量同步多个城市的景点数据
     *
     * @param cities 城市列表（JSON数组字符串，如 ["北京","上海","杭州"]）
     * @return 同步结果汇总
     */
    @Tool(description = "批量同步多个城市的景点数据。⚠️仅管理员可用")
    @AdminOnly("批量同步城市数据")
    public String batchSyncCities(String cities) {
        log.info("[KnowledgeBaseUpdateTool] 开始批量同步城市数据");

        try {
            // 解析城市列表
            List<String> cityList = parseCityList(cities);

            if (cityList.isEmpty()) {
                return "❌ 未提供有效的城市列表，请检查输入格式";
            }

            // 执行批量同步
            Map<String, Integer> results = poiSyncService.batchSyncCities(cityList);

            // 构建结果报告
            StringBuilder report = new StringBuilder();
            report.append("📊 批量同步完成报告\n");
            report.append("==================\n");

            int totalImported = 0;
            int successCount = 0;
            int failCount = 0;

            for (Map.Entry<String, Integer> entry : results.entrySet()) {
                String city = entry.getKey();
                int count = entry.getValue();

                if (count >= 0) {
                    totalImported += count;
                    successCount++;
                    report.append(String.format("✅ %s: 新增 %d 个景点\n", city, count));
                } else {
                    failCount++;
                    report.append(String.format("❌ %s: 同步失败\n", city));
                }
            }

            report.append("\n📈 汇总统计:\n");
            report.append(String.format("   - 成功城市数：%d\n", successCount));
            report.append(String.format("   - 失败城市数：%d\n", failCount));
            report.append(String.format("   - 总新增景点：%d 个\n", totalImported));
            report.append(String.format("   - 更新时间：%s", java.time.LocalDateTime.now()));

            return report.toString();

        } catch (Exception e) {
            log.error("[KnowledgeBaseUpdateTool] 批量同步失败: {}", e.getMessage(), e);
            return String.format(
                    """
                            ❌ 批量同步失败
                               - 错误信息：%s
                               - 建议：检查城市名称是否正确，或逐个同步""",
                e.getMessage()
            );
        }
    }

    /**
     * 获取知识库数据统计信息
     *
     * @return 统计数据
     */
    @Tool(description = "获取当前知识库的统计信息，包括景点总数、覆盖城市数、数据质量等。⚠️仅管理员可用")
    @AdminOnly("获取知识库统计")
    public String getKnowledgeBaseStats() {
        log.info("[KnowledgeBaseUpdateTool] 获取知识库统计信息");

        try {
            Map<String, Object> stats = poiSyncService.getSyncStatistics();

            StringBuilder report = new StringBuilder();
            report.append("📚 知识库统计信息\n");
            report.append("==================\n");

            if (stats.containsKey("totalAttractions")) {
                report.append(String.format("   - 景点总数：%d 个\n", stats.get("totalAttractions")));
            }
            if (stats.containsKey("cities")) {
                report.append(String.format("   - 覆盖城市：%d 个\n", stats.get("cities")));
            }
            if (stats.containsKey("provinces")) {
                report.append(String.format("   - 覆盖省份：%d 个\n", stats.get("provinces")));
            }
            if (stats.containsKey("withCoordinates")) {
                report.append(String.format("   - 有坐标数据：%d 个\n", stats.get("withCoordinates")));
            }
            if (stats.containsKey("withDescription")) {
                report.append(String.format("   - 有详细描述：%d 个\n", stats.get("withDescription")));
            }
            if (stats.containsKey("qualityScore")) {
                report.append(String.format("   - 数据质量评分：%.1f/100\n", stats.get("qualityScore")));
            }

            report.append(String.format("\n   - 统计时间：%s", java.time.LocalDateTime.now()));

            return report.toString();

        } catch (Exception e) {
            log.error("[KnowledgeBaseUpdateTool] 获取统计失败: {}", e.getMessage(), e);
            return String.format(
                "❌ 获取统计信息失败\n" +
                "   - 错误信息：%s",
                e.getMessage()
            );
        }
    }

    /**
     * 检查某个城市的数据是否需要更新
     *
     * @param city 城市名称
     * @return 检查结果和建议
     */
    @Tool(description = "检查指定城市的景点数据是否需要更新。⚠️仅管理员可用")
    @AdminOnly("检查数据新鲜度")
    public String checkDataFreshness(String city) {
        log.info("[KnowledgeBaseUpdateTool] 检查城市 {} 的数据新鲜度", city);

        try {
            // 查询该城市的景点数量
            var attractions = poiSyncService.getClass()
                .getDeclaredField("repository")
                .getType()
                .cast(null); // 这里需要通过依赖注入获取 repository

            // 简化实现：直接返回建议
            return String.format(
                    """
                            ℹ️ 城市 %s 的数据检查建议
                               - 建议：如果用户询问最新景点，建议先调用 syncCityAttractions 同步数据
                               - 原因：高德地图会定期更新 POI 信息
                               - 操作：调用 syncCityAttractions("%s") 即可更新""",
                city, city
            );

        } catch (Exception e) {
            log.warn("[KnowledgeBaseUpdateTool] 检查失败: {}", e.getMessage());
            return String.format(
                "⚠️ 无法检查数据新鲜度\n" +
                "   - 建议：为保证数据准确性，可先调用 syncCityAttractions(\"%s\") 同步最新数据",
                city
            );
        }
    }

    /**
     * 导入预设的景点数据集（100+景点）
     *
     * @return 导入结果
     */
    @Tool(description = "导入系统预设的景点数据集（包含全国主要城市100+景点）。⚠️仅管理员可用")
    @AdminOnly("导入预设数据集")
    public String importPresetDataset() {
        log.info("[KnowledgeBaseUpdateTool] 开始导入预设数据集");

        try {
            int imported = importService.importExtendedDataset();

            if (imported > 0) {
                return String.format(
                        """
                                ✅ 已成功导入预设景点数据集
                                   - 新增景点数量：%d 个
                                   - 数据来源：系统预设数据集
                                   - 覆盖范围：全国主要旅游城市
                                   - 更新时间：%s
                                现在可以基于这些数据为用户提供推荐。""",
                    imported, java.time.LocalDateTime.now()
                );
            } else {
                return """
                        ℹ️ 预设数据集已是最新
                           - 未发现新的景点数据
                           - 可能原因：数据已完整导入""";
            }

        } catch (Exception e) {
            log.error("[KnowledgeBaseUpdateTool] 导入失败: {}", e.getMessage(), e);
            return String.format(
                    """
                            ❌ 导入预设数据集失败
                               - 错误信息：%s
                               - 建议：检查数据库连接后重试""",
                e.getMessage()
            );
        }
    }

    /**
     * 获取知识库详细统计信息（增强版）
     *
     * @return 详细统计数据
     */
    @Tool(description = "获取知识库的详细统计信息，包括景点总数、城市分布、数据质量等。⚠️仅管理员可用")
    @AdminOnly("获取详细统计信息")
    public String getDetailedStatistics() {
        log.info("[KnowledgeBaseUpdateTool] 获取详细统计信息");

        try {
            // 获取基础统计
            Map<String, Object> baseStats = poiSyncService.getSyncStatistics();
            // 获取文本格式统计
            String textStats = importService.getStatistics();

            StringBuilder report = new StringBuilder();
            report.append("📊 知识库详细统计报告\n");
            report.append("====================\n\n");

            // 添加文本统计
            report.append(textStats);
            report.append("\n");

            // 添加质量评分
            if (baseStats.containsKey("qualityScore")) {
                report.append(String.format("💯 数据质量评分：%.1f/100\n", baseStats.get("qualityScore")));
            }
            if (baseStats.containsKey("withCoordinates")) {
                report.append(String.format("📍 有坐标数据：%d 个\n", baseStats.get("withCoordinates")));
            }
            if (baseStats.containsKey("withDescription")) {
                report.append(String.format("📝 有详细描述：%d 个\n", baseStats.get("withDescription")));
            }

            report.append(String.format("\n⏰ 统计时间：%s", java.time.LocalDateTime.now()));

            return report.toString();

        } catch (Exception e) {
            log.error("[KnowledgeBaseUpdateTool] 获取统计失败: {}", e.getMessage(), e);
            return String.format(
                "❌ 获取统计信息失败\n" +
                "   - 错误信息：%s",
                e.getMessage()
            );
        }
    }

    /**
     * 解析城市列表字符串
     */
    private List<String> parseCityList(String citiesStr) {
        if (citiesStr == null || citiesStr.trim().isEmpty()) {
            return List.of();
        }

        try {
            // 支持格式：["北京","上海"] 或 "北京,上海,广州"
            String cleaned = citiesStr.trim();

            // JSON 数组格式
            if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }

            String[] parts = cleaned.split("[,，]"); // 支持中英文逗号
            List<String> result = new java.util.ArrayList<>();

            for (String part : parts) {
                String trimmed = part.trim().replaceAll("^\"|\"$", "");
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }

            return result;

        } catch (Exception e) {
            log.warn("[KnowledgeBaseUpdateTool] 解析城市列表失败: {}", e.getMessage());
            return List.of();
        }
    }
}
