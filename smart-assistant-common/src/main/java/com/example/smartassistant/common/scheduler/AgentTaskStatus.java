package com.example.smartassistant.common.scheduler;

/**
 * Agent 任务状态枚举。
 */
public enum AgentTaskStatus {
    /** 已创建，等待调度 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 执行完成 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 被取消 */
    CANCELLED,
    /** 超时 */
    TIMEOUT
}
