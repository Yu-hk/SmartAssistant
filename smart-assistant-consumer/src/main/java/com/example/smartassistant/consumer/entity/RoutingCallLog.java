package com.example.smartassistant.consumer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 路由调用日志实体 (MyBatis Plus)
 * 记录每一次路由决策，用于问题排查和数据分析
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("routing_call_log")
public class RoutingCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话 ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 用户原始输入
     */
    @TableField("user_input")
    private String userInput;

    /**
     * 路由到的 Agent 名称
     */
    @TableField("routed_agent")
    private String routedAgent;

    /**
     * 路由方式: keyword_match / semantic / llm_fallback
     */
    @TableField("route_method")
    private String routeMethod;

    /**
     * 语义匹配分数
     */
    @TableField("match_score")
    private BigDecimal matchScore;

    /**
     * 命中的规则 ID
     */
    @TableField("matched_rule_id")
    private Long matchedRuleId;

    /**
     * LLM 实际接收到的 question（用于 debug）
     */
    @TableField("llm_received_question")
    private String llmReceivedQuestion;

    /**
     * 响应摘要（前500字符）
     */
    @TableField("response_summary")
    private String responseSummary;

    /**
     * 耗时（毫秒）
     */
    @TableField("latency_ms")
    private Long latencyMs;

    /**
     * 状态: SUCCESS / FAILED / TIMEOUT
     */
    @TableField("status")
    @Builder.Default
    private String status = "SUCCESS";

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
