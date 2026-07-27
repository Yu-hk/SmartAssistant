/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ G6: 上下文重要性钉选（Lost-in-the-Middle 防护）。
 *
 * <p>长上下文中，位于中间的关键指令/用户约束易被 LLM 注意力忽视。本工具确保：</p>
 * <ul>
 *   <li>系统指令（SystemMessage）始终位于消息列表首位，不被压缩/截断埋没；</li>
 *   <li>首条用户请求（UserMessage）在裁剪时予以保留，避免"原始诉求"丢失。</li>
 * </ul>
 */
public final class ContextPinning {

    private ContextPinning() {
    }

    /**
     * 将系统消息（若有）移至首位；已在首位或无系统消息时原样返回。
     */
    public static List<Message> pinSystemToFront(List<Message> src) {
        if (src == null || src.size() < 2) {
            return src;
        }
        int sysIdx = -1;
        for (int i = 0; i < src.size(); i++) {
            if (src.get(i) instanceof SystemMessage) {
                sysIdx = i;
                break;
            }
        }
        if (sysIdx <= 0) {
            return src;
        }
        List<Message> out = new ArrayList<>(src.size());
        out.add(src.get(sysIdx));
        for (int i = 0; i < src.size(); i++) {
            if (i != sysIdx) {
                out.add(src.get(i));
            }
        }
        return out;
    }

    /**
     * 从原列表中提取首条用户消息（用于裁剪后回钉，防止原始诉求丢失）。
     *
     * @return 首条 UserMessage，无则返回 null
     */
    public static Message firstUserMessage(List<Message> src) {
        if (src == null) return null;
        for (Message m : src) {
            if (m instanceof UserMessage) {
                return m;
            }
        }
        return null;
    }
}
