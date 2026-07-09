/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * P5-B 用户纠正信号检测器（确定性，零 LLM 开销）。
 *
 * <p>用于实现 {@link BadCaseMinerService} 挖掘策略中的第 ③ 类信号：
 * 「同一 session 内用户推翻 / 纠正了前次回答」。本检测器只做单轮文本判断——
 * 命中中文「纠正类」表达即视为用户在对系统做纠正，不依赖跨轮会话历史，
 * 因此可在路由入口零成本接入，且不改变主链路行为。</p>
 *
 * <p>设计取舍：与 P4-A 情绪检测一致，采用关键词 + 排除规则而非 LLM 调用，
 * 避免每次请求额外推理开销；宁可保守（少召回）也不要高误报。</p>
 */
public final class CorrectionSignalDetector {

    /** 纠正类关键词（命中其一即视为纠正信号） */
    private static final Set<String> CORRECTION_MARKERS = Set.of(
            "纠正一下", "纠正", "更正一下", "更正", "订正",
            "你错了", "你说错了", "说错了", "你搞错了", "搞错了",
            "理解错了", "你理解错了", "误会了", "你误会了", "误会",
            "记错了", "记错", "记反了", "说反了", "我说反了",
            "说得不对", "答得不对", "不对吧", "不对劲",
            "应该是", "其实应该", "其实应该是",
            "我指的是", "我说的不是", "不是这个", "不是的", "不是那样", "不是那个",
            "我刚刚说错了", "刚才说错了", "我表达错了", "我表达得不对",
            "我想说的是", "我的意思是", "准确地说", "准确点说",
            "之前说错了", "前面说错了", "上一条不对"
    );

    /** 纯赞同表达（仅含这些且不含纠正词时，不算纠正） */
    private static final Set<String> AGREEMENT_MARKERS = Set.of(
            "你说得对", "你说得没错", "你说得对啊", "同意你", "确实如此", "对的没错"
    );

    private CorrectionSignalDetector() {}

    /**
     * 检测用户消息是否为「纠正信号」。
     *
     * @param message 用户本轮消息
     * @return 检测结果（含命中的标记，便于审计与测试）
     */
    public static CorrectionSignal detect(String message) {
        if (message == null || message.isBlank()) {
            return CorrectionSignal.none();
        }
        // 纯赞同不作为纠正
        for (String agree : AGREEMENT_MARKERS) {
            if (message.contains(agree)) {
                return CorrectionSignal.none();
            }
        }
        Set<String> hit = new TreeSet<>();
        for (String marker : CORRECTION_MARKERS) {
            if (message.contains(marker)) {
                hit.add(marker);
            }
        }
        if (hit.isEmpty()) {
            return CorrectionSignal.none();
        }
        return new CorrectionSignal(true, new ArrayList<>(hit));
    }

    /** 便捷方法：仅返回布尔判定 */
    public static boolean isCorrection(String message) {
        return detect(message).isCorrection();
    }

    /**
     * 纠正信号检测结果。
     *
     * @param correction 是否命中纠正信号
     * @param markers    命中的纠正关键词（按字典序）
     */
    public static final class CorrectionSignal {
        private final boolean correction;
        private final List<String> markers;

        private CorrectionSignal(boolean correction, List<String> markers) {
            this.correction = correction;
            this.markers = markers;
        }

        public static CorrectionSignal none() {
            return new CorrectionSignal(false, List.of());
        }

        public boolean isCorrection() {
            return correction;
        }

        public List<String> getMarkers() {
            return markers;
        }

        @Override
        public String toString() {
            return "CorrectionSignal{correction=" + correction + ", markers=" + markers + '}';
        }
    }
}
