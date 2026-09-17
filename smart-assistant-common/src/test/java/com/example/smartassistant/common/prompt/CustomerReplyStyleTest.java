package com.example.smartassistant.common.prompt;

import com.example.smartassistant.common.error.*;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerReplyStyleTest {
    @Test void allDomainPromptsInheritStyleWithoutAnotherModelCall() {
        for (String domain : new String[]{"商品", "订单", "兜底"}) {
            String prompt = PromptBuilder.build().withServicePrompt(domain).assemble();
            assertThat(prompt).contains(CustomerReplyStyle.RULES, "只询问", "一次优先问一个", "不机械重复",
                    "不虚构", "不能改变事实", "不因用户着急绕过确认");
            assertThat(prompt.indexOf(CustomerReplyStyle.RULES)).isGreaterThan(prompt.indexOf(domain));
        }
    }

    @Test void unavailableAndNotStartedMustNotConflateSafety() {
        assertThat(CustomerMessages.UNCONFIRMED).contains("还没有确认", "原请求", "不要重复提交")
                .doesNotContain("重新发送", "还没有开始", "已取消", "已完成。", "路由", "Redis");
        assertThat(CustomerMessages.NOT_SENT).contains("还没有开始处理", "稍后再试");
        assertThat(CustomerMessages.QUEUE_EXPIRED).contains("还没有开始处理");
        assertThat(ErrorRecoveryService.DEFAULT.resolveUserMessage(AgentErrorCode.MODEL_CALL_FAILED, "secret"))
                .doesNotContain("secret", "模型", "Agent").contains("避免重复提交");
    }

    @Test void retrievalRejectionsKeepMetadataButNotInternalDiagnostics() {
        var result = RetrievalQualityResult.insufficientEvidence("evidence", .27, "SQLException: secret /internal");
        assertThat(result.getRejectionMessage()).contains("资料还不足以确认答案")
                .doesNotContain("置信度", "27%", "SQLException", "secret", "请确认问题");
        assertThat(result.getNormalizedScore()).isEqualTo(.27);
        assertThat(result.getRejectionCode()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.getContent()).isEqualTo("evidence");
        assertThat(RetrievalQualityResult.noData("private request").getRejectionMessage())
                .doesNotContain("private request", "数据库", "不存在").contains("还没有查到");
    }
}
