/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChunkQualityScorer 单元测试——验证入库前质量门禁。
 */
class ChunkQualityScorerTest {

    private final ChunkQualityScorer scorer = new ChunkQualityScorer();

    private KnowledgeDocument doc(String content, AuthorityLevel level) {
        return new KnowledgeDocument("d1", "t", content, "c", "k",
                -1, -1, "", "v1", "", 0, "", level, DocumentStatus.ACTIVE);
    }

    @Test
    void shortFragmentRejected() {
        // 页眉页脚碎片（< 50 字且低密度）应被拒
        assertFalse(scorer.isQualified(doc("页眉页脚", AuthorityLevel.L2_INTERNAL)));
    }

    @Test
    void normalChunkQualified() {
        String content = "退款申请提交后商家需在48小时内审核通过，退款金额将在3至7个工作日原路返回用户账户余额。"
                + "若超时未处理用户可联系客服发起催办，平台将介入协调并确保资金安全及时到账。";
        assertTrue(scorer.isQualified(doc(content, AuthorityLevel.L2_INTERNAL)));
    }

    @Test
    void authorityWeightAffectsScore() {
        String content = "本手册规定了系统操作规范与权限分配细则，适用于全部内部员工。"
                + "所有操作均需遵循最小权限原则，敏感操作须双人复核并记录审计日志以备追溯与合规检查。";
        double official = scorer.score(doc(content, AuthorityLevel.L1_OFFICIAL));
        double external = scorer.score(doc(content, AuthorityLevel.L4_EXTERNAL));
        assertTrue(official > external, "官方来源质量分应高于外部来源");
    }
}
