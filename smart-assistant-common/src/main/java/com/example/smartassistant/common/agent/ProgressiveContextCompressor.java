/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ G2: 多级渐进压缩器。
 *
 * <p>压缩不再"每次都调 LLM 全量摘要"，而是按窗口压力逐级升级，降低压缩的 LLM 成本：</p>
 * <ul>
 *   <li><b>Tier 1 — SNAP（廉价）</b>：结构性裁剪，丢弃最旧的非系统消息、保留系统与最近
 *       {@code keepRounds*2} 条，无 LLM 开销；若裁剪后已低于预算则直接返回。</li>
 *   <li><b>Tier 2 — LLM（昂贵）</b>：仅当 SNAP 后仍超预算，才委托 {@link ContextCompressor}
 *       做基于 LLM 的全量摘要（保留最近轮次、结构化压缩历史）。</li>
 * </ul>
 *
 * <p>对应文章核心诉求：压缩应按成本分层，避免对每一轮历史都付出一次 LLM 摘要的代价。</p>
 */
public class ProgressiveContextCompressor {

    private final ContextCompressor delegate;
    private final int keepRounds;

    public ProgressiveContextCompressor(ContextCompressor delegate, int keepRounds) {
        this.delegate = delegate;
        this.keepRounds = keepRounds;
    }

    /**
     * 渐进压缩。
     *
     * @param messages     当前消息列表
     * @param budgetTokens 压缩后目标 token 上限（低于此值即可停手，避免无谓 LLM 调用）
     * @return 压缩后的消息列表
     */
    public List<Message> compress(List<Message> messages, long budgetTokens) {
        // Tier 1: SNAP（廉价结构性裁剪，无 LLM）
        List<Message> snapped = snapOldTurns(messages);
        if (snapped != messages && TokenEstimator.estimateMessages(snapped) <= budgetTokens) {
            return snapped;
        }
        // Tier 2: LLM 全量摘要（仅在 SNAP 不足以释放空间时升级）
        return delegate.compress(messages);
    }

    /**
     * 廉价裁剪：保留系统消息 + 首条用户请求 + 最近 {@code keepRounds*2} 条，丢弃更早的非系统消息。
     * 与 G5 的 cheapTruncate 同源思路，但此处作为"压缩前的第一档"，不触发断路器；
     * ⭐ G6: 同时钉选首条用户请求，防止原始诉求在裁剪中丢失（Lost-in-the-Middle 防护）。
     */
    private List<Message> snapOldTurns(List<Message> src) {
        if (src.size() <= keepRounds + 1) {
            return src;
        }
        Message firstUser = ContextPinning.firstUserMessage(src);
        List<Message> out = new ArrayList<>();
        int systemIdx = -1;
        for (int i = 0; i < src.size(); i++) {
            if (src.get(i) instanceof SystemMessage) {
                systemIdx = i;
                break;
            }
        }
        if (systemIdx >= 0) {
            out.add(src.get(systemIdx));
        }
        int keep = Math.max(keepRounds * 2, 4);
        int start = Math.max(systemIdx >= 0 ? 1 : 0, src.size() - keep);
        boolean addedFirstUser = false;
        for (int i = start; i < src.size(); i++) {
            Message m = src.get(i);
            out.add(m);
            if (m == firstUser) {
                addedFirstUser = true;
            }
        }
        if (firstUser != null && !addedFirstUser) {
            if (systemIdx >= 0 && out.size() > 1) {
                out.add(1, firstUser);
            } else {
                out.add(0, firstUser);
            }
        }
        return out;
    }
}
