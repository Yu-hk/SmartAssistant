/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成后合规校验单元测试（REQ-3）——ComplianceGrader + ComplianceGuard + 审计日志 + 误杀白名单。
 * <p>
 * 使用 classpath 规则集（rag/compliance-rules.json，C001–C030 共 30 条）验证：
 * <ul>
 *   <li>规则集 ≥ 10 条可命中；</li>
 *   <li>超承诺/绝对化表述命中后按默认 rewrite 策略改写（不再含违例表述）；</li>
 *   <li>投资欺诈类（稳赚不赔/包赚/保本高收益）强制 block → 安全拒答模板；</li>
 *   <li>命中写入 compliance_audit_log（内存/可观测）；</li>
 *   <li>误杀白名单：合规/良性文本不被拦截、不被改写、不进审计。</li>
 * </ul>
 * </p>
 */
class ComplianceGuardGraderTest {

    private final ComplianceRuleSet rules = ComplianceRuleSet.fromClasspath();
    private final ComplianceAuditRecorder recorder = new ComplianceAuditRecorder();
    private final ComplianceGuard guard =
            new ComplianceGuard(new ComplianceGrader(rules), recorder, "rewrite", true);

    @Test
    void ruleSetLoadsAtLeastTenRules() {
        assertTrue(rules.size() >= 10, "合规规则集应至少 10 条（实际 " + rules.size() + "）");
    }

    @Test
    void overPromiseRewrittenByDefaultStrategy() {
        ComplianceResult r = guard.check("您可以保证一定能订到房间，且价格永久有效");
        assertTrue(r.isHit());
        assertEquals("REWRITE", r.getStrategyApplied());
        assertFalse(r.getOutput().contains("一定能"), "改写后不应仍含超承诺表述『一定能』");
        assertFalse(r.getOutput().contains("永久有效"), "改写后不应仍含模糊政策表述『永久有效』");
    }

    @Test
    void multipleAbsoluteTermsRewritten() {
        ComplianceResult r = guard.check("本产品价格百分百有效，100%保证到账");
        assertTrue(r.isHit());
        assertEquals("REWRITE", r.getStrategyApplied());
        assertFalse(r.getOutput().contains("百分百"), "应改写『百分百』");
        assertFalse(r.getOutput().contains("100%"), "应改写『100%』");
    }

    @Test
    void medicalOverPromiseRewritten() {
        ComplianceResult r = guard.check("这款药包治百病，效果绝对没问题");
        assertTrue(r.isHit());
        assertEquals("REWRITE", r.getStrategyApplied());
        assertFalse(r.getOutput().contains("包治"), "医疗超承诺应改写『包治』");
    }

    @Test
    void fraudPatternsBlockedWithSafeTemplate() {
        for (String text : new String[]{"稳赚不赔", "包赚项目推荐", "保本高收益理财"}) {
            ComplianceResult r = guard.check(text);
            assertEquals("BLOCK", r.getStrategyApplied(), "投资欺诈类应 block: " + text);
            assertNotNull(r.getOutput());
            assertTrue(r.getOutput().contains("无法对相关内容作出承诺"),
                    "block 应输出安全拒答模板: " + text);
        }
    }

    @Test
    void auditLoggedOnHit() {
        assertEquals(0, recorder.memorySize(), "命中前应无审计");
        guard.check("您可以保证一定能订到房间");
        assertTrue(recorder.memorySize() >= 1, "命中应写入合规审计日志（compliance_audit_log）");
    }

    @Test
    void benignTextPassesThroughWithoutFalsePositive() {
        // 误杀白名单路径：完全合规/良性文本不被拦截、不被改写、不进审计
        String benign = "您可以拨打人工客服电话咨询具体的退款政策与办理流程。";
        ComplianceResult r = guard.check(benign);
        assertFalse(r.isHit(), "良性文本不应命中任何规则");
        assertEquals(benign, r.getOutput(), "良性文本应原样放行");
        assertEquals(0, recorder.memorySize(), "良性文本不应写审计（无误杀）");
    }

    @Test
    void warnOnlyTermsNotBlocked() {
        // 含 warn 类规则（如『免费』C014 /『绝对』C007）但无 block 规则：
        // 默认 rewrite 策略下因 rewrite 为空，原文放行（不拒答），仅写审计。
        String text = "本活动完全免费参与，绝对没问题";
        ComplianceResult r = guard.check(text);
        assertTrue(r.isHit(), "应命中 warn 类规则");
        assertEquals(text, r.getOutput(), "warn 类且无改写内容时原文放行，不被拒答（避免误杀）");
        assertTrue(recorder.memorySize() >= 1, "命中应写审计");
    }
}
