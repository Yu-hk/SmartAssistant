package com.example.smartassistant.common.gateway.llm;

/**
 * LLM 调用结果（不可变）。
 * <p>
 * 统一封装调用结果，包含耗时、Token 消耗等元数据。
 * </p>
 *
 * @param content       模型返回的文本内容
 * @param elapsedMs     调用耗时（毫秒）
 * @param success       是否成功
 * @param errorMessage  失败时的错误信息（success=false 时有值）
 * @param tokenCount    估算的 Token 数（由输出文本大致估算）
 */
public record LLMCallResult(
        String content,
        long elapsedMs,
        boolean success,
        String errorMessage,
        int tokenCount
) {
    public static LLMCallResult success(String content, long elapsedMs) {
        return new LLMCallResult(content, elapsedMs, true, null, estimateTokens(content));
    }

    public static LLMCallResult failure(String errorMessage, long elapsedMs) {
        return new LLMCallResult(null, elapsedMs, false, errorMessage, 0);
    }

    /** 按 1 token ≈ 1.5 中文字符 粗略估算 */
    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Math.ceil(text.length() / 1.5);
    }
}
