/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

/**
 * Pre-AL Gate（Pre-Agent-Loop Gate）— 每轮迭代前注入的结构化执行契约。
 *
 * <p>对标文章⑥《确定性的 Loop Engineering》Pre-AL Gate 设计：
 * 纯代码生成、完全确定、不受 LLM 温度参数影响。</p>
 *
 * <p>生成的文本在每轮 LLM 调用前注入到 SystemMessage 末尾，告知 Agent：
 * <ul>
 *   <li>当前迭代轮次与预算上限</li>
 *   <li>当前所处阶段（如有）</li>
 *   <li>未通过的验收检查清单</li>
 *   <li>硬规则（不得跳步、不得空口宣称完成、不得跳过检查）</li>
 * </ul>
 * </p>
 */
public record PreALGate(
        int iteration,
        int maxIterations,
        String phase,
        java.util.List<String> pendingChecks) {

    /**
     * 生成注入 LLM 输入的结构化执行契约文本。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【执行契约】\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("● 当前迭代：").append(iteration).append("/").append(maxIterations).append("\n");
        if (phase != null && !phase.isBlank()) {
            sb.append("● 当前阶段：").append(phase).append("\n");
        }
        if (pendingChecks != null && !pendingChecks.isEmpty()) {
            sb.append("● 待验收检查：\n");
            for (String check : pendingChecks) {
                sb.append("  - ").append(check).append("\n");
            }
        }
        sb.append("\n【硬规则】\n");
        sb.append("1. 未完成当前阶段所有任务前，不得宣称整体完成。\n");
        sb.append("2. 未通过验收检查前，不得跳过或忽略。\n");
        sb.append("3. 必须返回具体证据（数据/文件/工具调用结果），空口宣称完成无效。\n");
        sb.append("4. 如果被阻塞或需要用户确认，请明确请求暂停。\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
        return sb.toString();
    }
}
