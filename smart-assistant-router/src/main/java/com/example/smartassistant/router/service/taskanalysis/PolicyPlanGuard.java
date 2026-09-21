package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import java.util.Objects;

/** A narrow scope check, not a replacement intent router or a source of policy answers. */
final class PolicyPlanGuard {
    private PolicyPlanGuard() { }

    static boolean requiresRepair(String question, TaskAnalysisResult plan) {
        if (question == null || !question.matches("(?s).*(退货|退款|退换|售后).*")) return false;
        if (!question.matches("(?s).*(条件|政策|规则|流程|规定|无理由).*")) return false;
        // Leave mixed intents and actual account operations to the model and workflow validator.
        if (question.matches("(?is).*(ORD-|订单号|帮我退|给我退|申请退款|申请退货|办理|进度|到账|推荐|下单|多少钱|有货).*")) return false;
        if (plan == null || !plan.hasSubIntents()) return true;
        boolean addressesPolicy = false;
        for (var node : plan.getSubIntents()) {
            if (!"QUERY_PRODUCT".equalsIgnoreCase(Objects.toString(node.get("operation"), ""))
                    || "WRITE".equalsIgnoreCase(Objects.toString(node.get("access_mode"), ""))) return true;
            addressesPolicy |= Objects.toString(node.get("description"), "")
                    .matches("(?s).*(退货|退款|退换|售后|无理由|政策).*");
        }
        return !addressesPolicy;
    }
}
