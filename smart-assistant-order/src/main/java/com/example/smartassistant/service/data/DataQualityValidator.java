/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.data;

import com.example.smartassistant.entity.TouristAttraction;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据质量验证器
 * 对景点数据进行多维度质量检查
 */
@Component
public class DataQualityValidator {

    private static final Logger log = LoggerFactory.getLogger(DataQualityValidator.class);

    /**
     * 验证景点数据质量
     *
     * @param attraction 待验证的景点
     * @return 是否通过验证
     */
    public boolean validate(TouristAttraction attraction) {
        List<String> errors = new ArrayList<>();

        // 1. 必填字段检查
        validateRequiredFields(attraction, errors);

        // 2. 数据格式验证
        validateDataFormat(attraction, errors);

        // 3. 业务规则验证
        validateBusinessRules(attraction, errors);

        // 4. 数据完整性评分
        double completenessScore = calculateCompleteness(attraction);

        if (!errors.isEmpty()) {
            log.warn("[DataQuality] 验证失败: {} - 错误: {}", 
                    attraction.getName(), String.join("; ", errors));
            return false;
        }

        // 完整性分数低于60分的数据标记为低质量
        if (completenessScore < 60) {
            log.warn("[DataQuality] 数据完整性低: {} - 分数: {}", 
                    attraction.getName(), completenessScore);
            // 仍然允许导入，但记录警告
        }

        return true;
    }

    /**
     * 验证必填字段
     */
    private void validateRequiredFields(TouristAttraction attraction, List<String> errors) {
        if (attraction.getName() == null || attraction.getName().trim().isEmpty()) {
            errors.add("景点名称不能为空");
        }

        if (attraction.getCity() == null || attraction.getCity().trim().isEmpty()) {
            errors.add("城市不能为空");
        }

        if (attraction.getProvince() == null || attraction.getProvince().trim().isEmpty()) {
            errors.add("省份不能为空");
        }
    }

    /**
     * 验证数据格式
     */
    private void validateDataFormat(TouristAttraction attraction, List<String> errors) {
        // 名称长度检查
        if (attraction.getName() != null && attraction.getName().length() > 200) {
            errors.add("景点名称过长（超过200字符）");
        }

        // 坐标范围验证
        if (attraction.getLatitude() != null) {
            if (attraction.getLatitude() < 18 || attraction.getLatitude() > 54) {
                errors.add("纬度超出合理范围 (18-54): " + attraction.getLatitude());
            }
        }

        if (attraction.getLongitude() != null) {
            if (attraction.getLongitude() < 73 || attraction.getLongitude() > 135) {
                errors.add("经度超出合理范围 (73-135): " + attraction.getLongitude());
            }
        }

        // 门票价格验证
        if (attraction.getTicketPrice() != null && attraction.getTicketPrice() < 0) {
            errors.add("门票价格不能为负数");
        }

        // 游玩时长验证
        if (attraction.getSuggestDuration() != null && attraction.getSuggestDuration() <= 0) {
            errors.add("建议游玩时长必须大于0");
        }
    }

    /**
     * 验证业务规则
     */
    private void validateBusinessRules(TouristAttraction attraction, List<String> errors) {
        // 等级格式验证
        if (attraction.getLevel() != null) {
            if (!attraction.getLevel().matches("(5A|4A|3A|2A|A|未评级)")) {
                errors.add("景点等级格式不正确: " + attraction.getLevel());
            }
        }

        // 重复数据检测（基于名称相似度）
        // 这里简化处理，实际应使用更复杂的去重算法
    }

    /**
     * 计算数据完整性分数（0-100）
     */
    private double calculateCompleteness(TouristAttraction attraction) {
        int totalFields = 10; // 总字段数
        int filledFields = 0;

        // 必填字段（权重高）
        if (attraction.getName() != null && !attraction.getName().isEmpty()) filledFields += 2;
        if (attraction.getCity() != null && !attraction.getCity().isEmpty()) filledFields += 2;
        if (attraction.getProvince() != null && !attraction.getProvince().isEmpty()) filledFields += 2;

        // 可选字段
        if (attraction.getDescription() != null && !attraction.getDescription().isEmpty()
                && !attraction.getDescription().equals("暂无描述")) filledFields++;
        if (attraction.getLevel() != null && !attraction.getLevel().equals("未评级")) filledFields++;
        if (attraction.getTicketPrice() != null) filledFields++;
        if (attraction.getOpenTime() != null && !attraction.getOpenTime().isEmpty()) filledFields++;
        if (attraction.getSuggestDuration() != null) filledFields++;
        if (attraction.getTags() != null && !attraction.getTags().isEmpty()) filledFields++;
        if (attraction.getLatitude() != null && attraction.getLongitude() != null) filledFields++;

        return (filledFields * 100.0) / (totalFields + 4); // 调整分母
    }

    /**
     * 批量验证并返回详细报告
     */
    public DataQualityReport batchValidate(List<TouristAttraction> attractions) {
        DataQualityReport report = new DataQualityReport();
        report.setTotalCount(attractions.size());

        int passed = 0;
        int failed = 0;
        double totalScore = 0;

        for (TouristAttraction attraction : attractions) {
            boolean isValid = validate(attraction);
            double score = calculateCompleteness(attraction);
            totalScore += score;

            if (isValid) {
                passed++;
            } else {
                failed++;
                report.addFailedItem(attraction.getName(), "验证失败");
            }

            report.addScore(score);
        }

        report.setPassedCount(passed);
        report.setFailedCount(failed);
        report.setAverageScore(totalScore / attractions.size());

        return report;
    }

    /**
     * 数据质量报告
     */
    @Getter
    public static class DataQualityReport {
        // Getters and Setters
        @Setter
        private int totalCount;
        @Setter
        private int passedCount;
        @Setter
        private int failedCount;
        @Setter
        private double averageScore;
        private final List<FailedItem> failedItems = new ArrayList<>();

        public record FailedItem(String itemName, String reason) {

        }

        public void addFailedItem(String itemName, String reason) {
            failedItems.add(new FailedItem(itemName, reason));
        }

        public void addScore(double score) {
            // 用于计算平均分
        }

        @Override
        public String toString() {
            return String.format(
                    """
                            数据质量报告:
                              总数: %d
                              通过: %d (%.1f%%)
                              失败: %d (%.1f%%)
                              平均分数: %.2f/100""",
                totalCount,
                passedCount, (passedCount * 100.0 / totalCount),
                failedCount, (failedCount * 100.0 / totalCount),
                averageScore
            );
        }
    }
}
