/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * Token 估算工具——用于上下文窗口占用评估（G1 压缩触发 / G3 工具结果预算）。
 *
 * <p>估算口径与 {@code RecursiveChunkStrategy#estimateTokens} 一致：
 * 中文 ≈ 1 token/字，英文 ≈ 0.4 token/char。仅用于容量决策，非精确计费。</p>
 *
 * @author Yu-hk
 * @since 2026-07-27
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /** 估算单段文本的 token 数。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chinese = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) chinese++;
            else other++;
        }
        return chinese + (int) Math.ceil(other * 0.4);
    }

    /** 估算整段对话消息的 token 总占用（含工具调用参数、工具返回）。 */
    public static long estimateMessages(List<Message> messages) {
        if (messages == null) return 0;
        long total = 0;
        for (Message m : messages) total += estimateMessage(m);
        return total;
    }

    /** 估算单条消息的 token 占用。 */
    public static long estimateMessage(Message msg) {
        if (msg == null) return 0;
        if (msg instanceof UserMessage u) {
            return estimate(u.getText());
        } else if (msg instanceof AssistantMessage a) {
            long t = estimate(a.getText());
            if (a.getToolCalls() != null) {
                for (var tc : a.getToolCalls()) {
                    t += estimate(tc.name()) + estimate(tc.arguments());
                }
            }
            return t;
        } else if (msg instanceof ToolResponseMessage tm) {
            long t = 0;
            var rs = tm.getResponses();
            if (rs != null) {
                for (var r : rs) {
                    t += estimate(r.name()) + estimate(r.responseData());
                }
            }
            return t;
        } else if (msg instanceof SystemMessage s) {
            return estimate(s.getText());
        }
        return 0;
    }
}
