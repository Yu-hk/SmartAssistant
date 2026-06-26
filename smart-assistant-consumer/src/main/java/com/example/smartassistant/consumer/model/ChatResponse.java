package com.example.smartassistant.consumer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智能对话响应（Chat API 业务数据层）。
 * <p>
 * 对应 OpenAI / DeepSeek 兼容的 choices[].message 结构。
 * 外层由 {@link com.example.smartassistant.common.response.ApiResponse} 包裹。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    /** AI 回复内容 */
    private String reply;

    /** 角色：assistant */
    @Builder.Default
    private String role = "assistant";

    /** 智能建议列表 */
    private List<String> suggestions;

    /** 会话 ID（有 session 时） */
    private String sessionId;

    /** 处理的 Agent 名称 */
    private String agentName;

    /** 意图标签 */
    private String intentTag;

    /** 是否命中缓存 */
    private Boolean fromCache;

    /** 是否调用了工具 */
    private Boolean toolInvoked;

    /** 处理耗时（毫秒） */
    private Long durationMs;
}
