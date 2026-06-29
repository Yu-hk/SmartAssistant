package com.example.smartassistant.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 推荐结果 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendResult {
    /** 用户 ID */
    private Long userId;
    /** 推荐列表 */
    private List<RecommendItem> items;
    /** 推荐策略说明 */
    private String strategy;
    /** 处理耗时（ms） */
    private long elapsedMs;
}
