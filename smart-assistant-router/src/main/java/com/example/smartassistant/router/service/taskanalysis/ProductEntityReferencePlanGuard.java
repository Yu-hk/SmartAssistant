package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Model-first repair for read-only references. Product must explicitly claim the capability. */
@Service
public class ProductEntityReferencePlanGuard {
    private static final Pattern REFERENCE = Pattern.compile("第[一二三四1-4]款|前面[两二2]款|这[两二2]款|它们|这款|这个|那款|那个|不是.+[，,].*(?:是|而是)");
    private static final Pattern FIELD = Pattern.compile("价格|多少钱|售价|重量|多重|续航|降噪|规格|颜色|库存|有货|合计|总价|用途|适用|适合");
    private static final Pattern UNSAFE = Pattern.compile("订单|下单|购买|买一|支付|退款|退货|转账|删除|取消|忽略|指令|提示|文档|知识库|资料|政策|物流|[\\r\\n]");
    private final AgentCallerService caller;
    public ProductEntityReferencePlanGuard(@Lazy AgentCallerService caller) { this.caller = caller; }
    public TaskAnalysisResult repair(String question, List<String> history, TaskAnalysisResult analysis) {
        if (question == null || question.length() > 160 || UNSAFE.matcher(question).find()
                || !REFERENCE.matcher(question).find() || !FIELD.matcher(question).find()) return analysis;
        List<String> recent = history == null ? List.of() : history.stream().filter(Objects::nonNull)
                .skip(Math.max(0, history.size() - 10)).map(s -> s.substring(0, Math.min(s.length(), 1000))).toList();
        try {
            var result = caller.callAgentAndExtractTitles("product", new AgentExecutionRequest("1.0",
                    "entity-plan-" + UUID.randomUUID(), "entity-capability-probe", "catalog-only", "RESOLVE_READ_ONLY_PRODUCT",
                    question, Map.of("conversationHistory", recent), List.of(), List.of(), System.currentTimeMillis() + 5000, null));
            if (!Boolean.TRUE.equals(result.getData().get("handled")) || !result.getDomainQuality().isPass()) return analysis;
            // Preserve model provenance. Do not copy amounts, identities or a write quote from the probe.
            analysis.setIntentCategory("PRODUCT");
            analysis.setNeedsClarification(false);
            analysis.setClarificationQuestions(List.of());
            analysis.setMissingSlots(List.of());
            analysis.setImplicitIntents(List.of());
            analysis.setSemanticCacheCategory("NONE");
            analysis.setSubIntents(List.of(Map.of("id", "entity_query", "intent", "PRODUCT",
                    "description", question, "target_agent", "product", "operation", "QUERY_PRODUCT",
                    "access_mode", "READ", "input", Map.of("conversationHistory", recent), "depends_on", List.of(),
                    "required", true, "success_criteria", List.of("核对当前目录事实，未知实体明确澄清"))));
            analysis.setTaskSteps(List.of("核对商品实体与当前目录事实"));
            analysis.setExecutionOrder(List.of("entity_query"));
            analysis.setFlowchart("entity_query[Product READ]");
        } catch (RuntimeException unavailable) {
            // A failed capability probe does not invent evidence or widen permissions.
        }
        return analysis;
    }
}
