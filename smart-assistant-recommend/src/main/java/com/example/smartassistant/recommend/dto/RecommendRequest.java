package com.example.smartassistant.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 推荐请求 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendRequest {
    /** 用户 ID（必须） */
    private Long userId;
    /** 当前浏览/提问的商品编码（可选，基于商品推荐） */
    private String productCode;
    /** 推荐数量（默认 5） */
    @Builder.Default
    private int maxResults = 5;
}
