package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.data.HybridDataQueryService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 数据查询 REST API — 独立端点（不再混入 ChatController）
 * <p>
 * 职责：处理管理员的数据统计/查询请求（SQL/MCP 查询），
 * 不涉及 Agent 路由，独立于对话流。
 */
@RestController
@RequestMapping("/api/data")
public class DataQueryController {

    private static final Logger log = LoggerFactory.getLogger(DataQueryController.class);

    private final HybridDataQueryService hybridDataQueryService;
    private final ChatClient chatClient;

    public DataQueryController(
            HybridDataQueryService hybridDataQueryService,
            ChatClient.Builder chatClientBuilder) {
        this.hybridDataQueryService = hybridDataQueryService;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 智能数据查询接口 — 仅限 ADMIN
     * POST /api/data/query
     * Body: {"message": "查询用户总数"}
     */
    @PostMapping("/query")
    @RateLimiter(name = "chatRateLimiter")
    public Mono<Map<String, Object>> query(
            @RequestHeader(value = "X-User-Role", required = false) String userRoleFromHeader,
            @RequestBody Map<String, String> request) {
        long startTime = System.currentTimeMillis();

        // 1. 权限校验：数据查询仅限 ADMIN
        String role = (userRoleFromHeader != null) ? userRoleFromHeader : "ROLE_USER";
        if (!isAdmin(role)) {
            log.warn("[DataQuery] ⛔ 拒绝非管理员数据查询请求: role={}", role);
            return Mono.just(buildForbiddenResponse());
        }

        // 2. 提取参数
        String message = request.getOrDefault("question", request.get("message"));
        if (message == null || message.isBlank()) {
            return Mono.just(Map.of("error", "查询内容不能为空"));
        }

        log.info("[DataQuery] 收到数据查询请求: message={}", message);

        // 3. 执行查询（关键词 + LLM 双层判断）
        try {
            if (isDataQueryRequest(message)) {
                String result = handleDataQuery(message);
                Map<String, Object> response = new HashMap<>();
                response.put("result", result);
                response.put("duration_ms", System.currentTimeMillis() - startTime);
                return Mono.just(response);
            }

            // LLM 二次判断
            if (isDataQueryByLLM(message)) {
                String result = handleDataQuery(message);
                Map<String, Object> response = new HashMap<>();
                response.put("result", result);
                response.put("duration_ms", System.currentTimeMillis() - startTime);
                return Mono.just(response);
            }

            // 非数据查询请求
            return Mono.just(Map.of(
                    "error", "无法识别为数据查询请求",
                    "suggestions", List.of(
                            "查询用户总数",
                            "统计注册用户数量",
                            "显示所有订单"
                    )
            ));

        } catch (Exception e) {
            log.error("[DataQuery] 查询异常", e);
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("error", "数据查询出错：" + e.getMessage());
            return Mono.just(errorMap);
        }
    }

    /**
     * 处理数据查询请求
     */
    private String handleDataQuery(String message) {
        Map<String, Object> result = hybridDataQueryService.naturalLanguageQuery(message);
        if (Boolean.TRUE.equals(result.get("success"))) {
            String answer = (String) result.get("answer");
            String method = (String) result.get("method");
            log.info("[DataQuery] 完成: method={}", method);
            return answer;
        }
        log.warn("[DataQuery] 失败: {}", result.get("error"));
        return "抱歉，暂时无法完成数据查询。请稍后重试。";
    }

    /**
     * 判断是否为数据查询请求
     */
    private boolean isDataQueryRequest(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        String[] dataQueryKeywords = {
                "多少", "统计", "总数", "数量", "平均", "最大", "最小",
                "增长", "趋势", "分布", "排名", "排行", "占比", "比例",
                "count", "sum", "avg", "max", "min", "group by", "order by",
                "select", "from", "where"
        };
        for (String keyword : dataQueryKeywords) {
            if (lowerMessage.contains(keyword)) {
                return true;
            }
        }
        String[] fuzzyKeywords = {"列表", "字段", "结构", "前", "详情", "显示", "查看", "所有"};
        int fuzzyMatchCount = 0;
        for (String keyword : fuzzyKeywords) {
            if (lowerMessage.contains(keyword)) {
                fuzzyMatchCount++;
            }
        }
        return fuzzyMatchCount >= 2;
    }

    /**
     * LLM 增强判断
     */
    private boolean isDataQueryByLLM(String message) {
        if (message == null || message.length() < 4) return false;
        String lower = message.toLowerCase();
        if (!lower.contains("多少") && !lower.contains("用户") && !lower.contains("系统")
                && !lower.contains("几个") && !lower.contains("人数") && !lower.contains("数据")
                && !lower.contains("总数") && !lower.contains("所有") && !lower.contains("注册")
                && !lower.contains("查询") && !lower.contains("列表") && !lower.contains("统计")) {
            return false;
        }
        try {
            String result = chatClient.prompt()
                    .user("判断以下用户问题是查询系统数据（如用户数量、消息数量等），还是日常聊天。只回答 data_query 或 chat：\n" + message)
                    .call()
                    .content();
            if (result != null && result.trim().toLowerCase().contains("data_query")) {
                log.info("[DataQuery/LLM] 识别为数据查询: {}", message);
                return true;
            }
        } catch (Exception e) {
            log.warn("[DataQuery/LLM] LLM 判断失败: {}", e.getMessage());
        }
        return false;
    }

    private boolean isAdmin(String role) {
        return "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    private Map<String, Object> buildForbiddenResponse() {
        String[] friendlyMessages = {
                "✨ 这个问题我暂时无法回答你，不过你可以试试问问我其他问题呢？",
                "🌸 哎呀，这个问题有点超出我的能力范围了，换一个试试吧～",
                "🌻 数据统计/查询功能仅限管理员使用哦！",
        };
        String friendly = friendlyMessages[new Random().nextInt(friendlyMessages.length)];
        List<String> suggestions = List.of(
                "查询用户总数", "统计本月订单数量", "显示所有注册用户"
        );
        Map<String, Object> response = new HashMap<>();
        response.put("reply", friendly);
        response.put("suggestions", suggestions);
        response.put("error", "FORBIDDEN");
        return response;
    }
}
