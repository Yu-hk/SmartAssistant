package com.example.smartassistant.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推荐项 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendItem {
    /** 商品编码 */
    private String productCode;
    /** 商品名称 */
    private String productName;
    /** 推荐理由 */
    private String reason;
    /** 推荐关系类型（SAME_CATEGORY / ACCESSORY / ALTERNATIVE / COLLAB_FILTER 等） */
    private String relationType;
    /** 相关性得分 */
    private double score;
}
