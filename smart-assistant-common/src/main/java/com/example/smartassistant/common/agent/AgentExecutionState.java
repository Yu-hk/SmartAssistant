package com.example.smartassistant.common.agent;

import java.time.LocalDateTime;

/**
 * Agent 执行状态枚举与事件。
 * <p>
 * P1 改造：将 Agent 执行从 ReAct 黑盒升级为显式状态机，
 * 每个状态转换生成不可变事件，支持审计、回放和恢复。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
public final class AgentExecutionState {

    // ==================== 状态枚举 ====================

    /** Agent 执行状态 */
    public enum State {
        /** 已创建，待执行 */
        CREATED,
        /** 就绪 */
        READY,
        /** 运行中（模型推理） */
        RUNNING,
        /** 等待工具返回 */
        WAITING_TOOL,
        /** 等待人工审批 */
        WAITING_APPROVAL,
        /** 等待外部事件 */
        WAITING_EVENT,
        /** 已挂起 */
        SUSPENDED,
        /** 已完成 */
        COMPLETED,
        /** 已失败 */
        FAILED,
        /** 死信 */
        DEAD_LETTER;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == DEAD_LETTER;
        }

        public boolean isActive() {
            return !isTerminal() && this != SUSPENDED && this != CREATED;
        }
    }

    // ==================== 事件类型 ====================

    /** 触发状态转换的事件类型 */
    public enum EventType {
        /** 开始执行 */
        EXECUTION_STARTED,
        /** 模型响应完成 */
        MODEL_RESPONDED,
        /** 工具调用成功 */
        TOOL_CALL_SUCCEEDED,
        /** 工具调用失败 */
        TOOL_CALL_FAILED,
        /** 审批已批准 */
        APPROVAL_GRANTED,
        /** 审批已拒绝 */
        APPROVAL_DENIED,
        /** 外部事件到达 */
        EVENT_RECEIVED,
        /** 超时 */
        TIMEOUT_REACHED,
        /** 达到最大迭代次数 */
        MAX_ITERATIONS_REACHED,
        /** 人工干预 */
        MANUAL_INTERVENTION,
        /** 执行完成 */
        EXECUTION_COMPLETED
    }

    // ==================== 状态转换 ====================

    /** 状态转换记录（不可变事件） */
    public record StateTransition(
            String agentId,         // Agent ID
            String requestId,       // 请求 ID
            State from,             // 转换前状态
            State to,               // 转换后状态
            EventType event,        // 触发事件
            String summary,         // 事件摘要
            long elapsedMs,         // 此步骤耗时
            int iteration,          // 当前迭代次数
            LocalDateTime timestamp // 事件时间
    ) {}
}
