package com.example.smartassistant.recommend.controller;

import com.example.smartassistant.recommend.dto.RecommendRequest;
import com.example.smartassistant.recommend.dto.RecommendResult;
import com.example.smartassistant.recommend.service.RecommendService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * P3 推荐服务 REST 控制器。
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@RestController
@RequestMapping("/api/recommend")
public class RecommendController {

    private static final Logger log = LoggerFactory.getLogger(RecommendController.class);

    private final RecommendService recommendService;

    public RecommendController(RecommendService recommendService) {
        this.recommendService = recommendService;
    }

    /**
     * 跨模块推荐（商品 + 协同过滤）。
     *
     * <p>请求示例：</p>
     * <pre>{@code
     * POST /api/recommend
     * {
     *   "userId": 1,
     *   "productCode": "IPHONE-15-PRO",
     *   "maxResults": 5
     * }
     * }</pre>
     */
    @PostMapping
    public ResponseEntity<RecommendResult> recommend(@Valid @RequestBody RecommendRequest request) {
        log.info("[Recommend] 请求: userId={}, productCode={}, maxResults={}",
                request.getUserId(), request.getProductCode(), request.getMaxResults());

        if (request.getUserId() == null && request.getProductCode() == null) {
            return ResponseEntity.badRequest().body(RecommendResult.builder()
                    .items(List.of())
                    .strategy("invalid: userId or productCode required")
                    .build());
        }

        RecommendResult result = recommendService.recommend(request);
        return ResponseEntity.ok(result);
    }

    /**
     * 健康检查端点。
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
