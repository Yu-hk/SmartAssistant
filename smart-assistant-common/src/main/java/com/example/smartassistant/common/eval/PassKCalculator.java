/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import java.util.List;

/**
 * pass@k / pass^k 聚合计算器 — 对齐文章《Agent 评测体系》§7。
 *
 * <p>文章定义：</p>
 * <ul>
 *   <li><b>pass@k</b>：k 次独立样本中至少一次通过的概率（宽松，考察「能否做成」）；</li>
 *   <li><b>pass^k</b>：k 次独立样本全部通过的概率（严格，考察「稳定做成」，生产客服/支付最关心）。</li>
 * </ul>
 *
 * <p>给定 n 次观测的通过率 p，采用二项分布解析估计：</p>
 * <pre>
 *   pass@k = 1 - (1 - p)^k
 *   pass^k = p^k
 * </pre>
 * <p>另提供基于观测结果的经验估计（枚举大小为 k 的子集），用于小样本交叉验证。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public final class PassKCalculator {

    private PassKCalculator() {
    }

    /** pass@k 解析估计。 */
    public static double passAtK(double observedPassRate, int k) {
        if (k <= 0) return 0.0;
        double p = clamp01(observedPassRate);
        return 1.0 - Math.pow(1.0 - p, k);
    }

    /** pass^k 解析估计。 */
    public static double passPowerK(double observedPassRate, int k) {
        if (k <= 0) return 0.0;
        double p = clamp01(observedPassRate);
        return Math.pow(p, k);
    }

    /** 从 TrialResult 聚合（解析估计）。 */
    public static PassKResult from(TrialResult trial, int k) {
        double p = trial.observedPassRate();
        return new PassKResult(trial.trialCount(), trial.passCount(), p, k,
                passAtK(p, k), passPowerK(p, k));
    }

    /**
     * 经验估计 pass@k：枚举所有 C(n,k) 个子集，统计「至少一次通过」的比例。
     * 仅建议在 n 较小（&lt;= 30）时使用。
     */
    public static double passAtKEmpirical(List<Boolean> outcomes, int k) {
        int n = outcomes.size();
        if (n < k || k <= 0) return 0.0;
        long total = 0, atLeastOne = 0;
        long limit = 1L << n;
        for (long mask = 0; mask < limit; mask++) {
            if (Long.bitCount(mask) != k) continue;
            total++;
            boolean ok = false;
            for (int i = 0; i < n; i++) {
                if ((mask & (1L << i)) != 0 && outcomes.get(i)) {
                    ok = true;
                    break;
                }
            }
            if (ok) atLeastOne++;
        }
        return total == 0 ? 0.0 : (double) atLeastOne / total;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** pass@k / pass^k 聚合结果。 */
    public record PassKResult(int trialCount, int passCount, double observedPassRate,
                              int k, double passAtK, double passPowerK) {
        @Override
        public String toString() {
            return String.format("Trial n=%d pass=%d p=%.3f | pass@%d=%.3f pass^%d=%.3f",
                    trialCount, passCount, observedPassRate, k, passAtK, k, passPowerK);
        }
    }
}
