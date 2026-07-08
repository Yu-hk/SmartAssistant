package com.example.smartassistant.router.model;

import java.util.List;
import java.util.Map;

/**
 * 子任务执行结果。
 * <p>借鉴文章⑤「三层上下文注入」：{@link #summary} 用于上下文构建（节省 Token），
 * {@link #result} 保留完整结果，供按需工具查询。</p>
 */
public class SubTaskResult {
    private String taskId;
    private String description;
    private String agentName;
    private String result;
    /**
     * 结果摘要（自动生成：取 result 前 200 字符）。
     * 供 ResultMerger 构建上下文时优先使用，避免全量注入 Token 膨胀。
     */
    private String summary;
    private boolean success;
    private List<String> realTitles;
    private Map<String, String> tagsByTitle;
    /** ⭐ Handoff 交接命令：执行完毕后如果需显式移交其他 Agent，非空表示有交接请求 */
    private HandoffCommand handoffCommand;

    /**
     * 错误分类枚举——替代原来仅 boolean success 的粗粒度结果。
     * <p>
     * 设计目的：让执行引擎能根据错误类型采取差异化恢复策略，
     * 而非将所有失败一视同仁。
     * </p>
     */
    public enum ErrorType {
        /** 无错误（success=true 时自动为此值） */
        NONE,
        /** 可重试失败：网络超时、临时不可用、并发冲突等瞬时错误 */
        RETRYABLE_FAILED,
        /** 致命失败：数据不存在、权限不足、参数非法等不可恢复错误 */
        FATAL_FAILED,
        /** 需重规划：目标偏离、产物不合格、关键输入缺失 */
        NEED_REPLAN
    }

    /** 错误类型（success=true 时自动为 NONE） */
    private ErrorType errorType = ErrorType.NONE;

    public SubTaskResult() {}

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success) {
        this(taskId, description, agentName, result, success, ErrorType.NONE, List.of(), Map.of());
    }

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success,
                         ErrorType errorType) {
        this(taskId, description, agentName, result, success, errorType, List.of(), Map.of());
    }

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success, List<String> realTitles) {
        this(taskId, description, agentName, result, success, ErrorType.NONE, realTitles, Map.of());
    }

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success, List<String> realTitles, Map<String, String> tagsByTitle) {
        this(taskId, description, agentName, result, success, ErrorType.NONE, realTitles, tagsByTitle);
    }

    /**
     * ⭐ 全参数构造器（含错误类型）。
     */
    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success,
                         ErrorType errorType, List<String> realTitles, Map<String, String> tagsByTitle) {
        this.taskId = taskId;
        this.description = description;
        this.agentName = agentName;
        this.result = result;
        this.summary = autoSummary(result);
        this.success = success;
        this.errorType = (success && errorType == ErrorType.NONE) ? ErrorType.NONE : errorType;
        this.realTitles = realTitles != null ? realTitles : List.of();
        this.tagsByTitle = tagsByTitle != null ? tagsByTitle : Map.of();
    }

    /** 自动生成摘要：取 result 前 200 字符（保留完整句子边界）。 */
    private static String autoSummary(String text) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= 200) return text;
        int cut = text.lastIndexOf('。', 200);
        if (cut < 0) cut = text.lastIndexOf('\n', 200);
        if (cut < 0) cut = 197;
        return text.substring(0, cut) + "...";
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getResult() { return result; }
    public void setResult(String result) {
        this.result = result;
        this.summary = autoSummary(result);
    }
    /** 获取摘要（自动生成：取 result 前 200 字符）。 */
    public String getSummary() { return summary; }
    /** 显式设置摘要（覆盖自动生成值）。 */
    public void setSummary(String summary) { this.summary = summary; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) {
        this.success = success;
        if (success) this.errorType = ErrorType.NONE;
    }

    public ErrorType getErrorType() { return errorType; }
    public void setErrorType(ErrorType errorType) { this.errorType = errorType; }

    /** 判断是否为可重试失败 */
    public boolean isRetryable() { return errorType == ErrorType.RETRYABLE_FAILED; }

    /** 判断是否为致命失败 */
    public boolean isFatal() { return errorType == ErrorType.FATAL_FAILED; }

    /** 判断是否需要重规划 */
    public boolean needsReplan() { return errorType == ErrorType.NEED_REPLAN; }
    public List<String> getRealTitles() { return realTitles; }
    public void setRealTitles(List<String> realTitles) { this.realTitles = realTitles != null ? realTitles : List.of(); }
    public Map<String, String> getTagsByTitle() { return tagsByTitle; }
    public void setTagsByTitle(Map<String, String> tagsByTitle) { this.tagsByTitle = tagsByTitle != null ? tagsByTitle : Map.of(); }

    public HandoffCommand getHandoffCommand() { return handoffCommand; }
    public void setHandoffCommand(HandoffCommand handoffCommand) { this.handoffCommand = handoffCommand; }
    public boolean hasHandoff() { return handoffCommand != null; }
}
