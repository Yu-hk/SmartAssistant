/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

/**
 * Handoff 显式交接命令。
 *
 * <p>当一个 Agent 确定自己无法完全处理当前请求，需要显式移交给另一个
 * 专业 Agent 时，通过此命令传递控制权和上下文。</p>
 *
 * <p>参考文章④：与 Router 模式的"分类→并行"不同，
 * Handoff 模式用于串行场景——Agent A 执行完毕 → 显式指定"下一个 Agent B"→
 * 传递累积上下文 → Agent B 继续执行。</p>
 *
 * @param handoffType   交接类型
 * @param targetAgent   目标 Agent 名称（如 {@code order_agent}）
 * @param question      向目标 Agent 提出的问题/指令
 * @param contextPayload 累积上下文负载（前置 Agent 已获取的信息）
 */
public record HandoffCommand(
        HandoffType handoffType,
        String targetAgent,
        String question,
        String contextPayload
) {
    public enum HandoffType {
        /** 正常交接：当前 Agent 完成，请下一个 Agent 处理后续 */
        HANDOFF,
        /** 完成：无进一步交接，链式执行结束 */
        COMPLETE,
        /** 失败：当前 Agent 无法处理，尝试其他 Agent 或终止 */
        FAILED
    }
}
