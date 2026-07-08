/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Trial 执行器 — 对单个 {@link AgentTestSpec} 多次独立运行 Agent，收集多轮结果。
 *
 * <p>非确定性 LLM Agent 的「能力」必须用多次独立运行来估计（文章：单次运行会高估能力）。
 * 运行器通过 {@link TrialExecutor} 注入实际的 Agent 调用逻辑，与评测框架解耦，
 * 便于在单元测试中用 mock 执行器验证聚合逻辑。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class TrialRunner {

    private static final Logger log = LoggerFactory.getLogger(TrialRunner.class);

    /** 实际运行一次 Agent 的函数（由运行时注入）。 */
    @FunctionalInterface
    public interface TrialExecutor {
        AgentEvaluationResult execute(AgentTestSpec spec);
    }

    private final long interTrialDelayMs;

    public TrialRunner() {
        this(0);
    }

    public TrialRunner(long interTrialDelayMs) {
        this.interTrialDelayMs = Math.max(0, interTrialDelayMs);
    }

    /**
     * 运行 n 次 Trial。
     *
     * @param spec     测试用例
     * @param n        运行次数（k 候选，通常 5~10）
     * @param executor 单次执行器
     * @return 聚合后的 {@link TrialResult}
     */
    public TrialResult run(AgentTestSpec spec, int n, TrialExecutor executor) {
        List<AgentEvaluationResult> trials = new ArrayList<>(n);
        int passCount = 0;
        for (int i = 0; i < n; i++) {
            try {
                AgentEvaluationResult r = executor.execute(spec);
                if (r != null) {
                    trials.add(r);
                    if (r.passed()) passCount++;
                }
            } catch (Exception e) {
                log.warn("[TrialRunner] 第 {} 次运行异常: {}", i + 1, e.getMessage());
            }
            if (i < n - 1 && interTrialDelayMs > 0) {
                try {
                    Thread.sleep(interTrialDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new TrialResult(spec.id, trials, passCount, n);
    }

    /** 对一组用例批量运行 Trial。 */
    public List<TrialResult> runAll(List<AgentTestSpec> specs, int n, TrialExecutor executor) {
        List<TrialResult> out = new ArrayList<>(specs.size());
        for (AgentTestSpec s : specs) {
            out.add(run(s, n, executor));
        }
        return out;
    }
}
