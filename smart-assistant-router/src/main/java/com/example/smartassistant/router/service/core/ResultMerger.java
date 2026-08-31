package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.router.model.ExecutionPlan;
import com.example.smartassistant.router.model.SubTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多 Agent 结果合并服务。
 *
 * <p>⭐ 借鉴文章⑤「三层上下文注入」设计：
 * <ul>
 *   <li><b>摘要优先</b>：上下文构建优先使用 {@link SubTaskResult#getSummary()}（前 200 字符），
 *       全量结果通过 Redis 存储供按需工具查询，避免全量拼接 Token 膨胀。</li>
 *   <li><b>请求隔离</b>：完整结果存入 Redis Key
 *       {@code a2a:task-result:{requestId}:{taskId}}（TTL=300s）。</li>
 * </ul>
 */
@Component
public class ResultMerger {

    private static final Logger log = LoggerFactory.getLogger(ResultMerger.class);

    private static final String TASK_RESULT_PREFIX = "a2a:task-result:";
    private static final Duration TASK_RESULT_TTL = Duration.ofMinutes(5);

    private final ChatClient chatClient;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public ResultMerger(@Qualifier("lightChatModel") ChatModel lightModel,
                        AiChatService aiChatService) {
        this.chatClient = aiChatService.buildChatClient(lightModel);
    }

    /**
     * 合并多个 Agent 的执行结果。
     *
     * <p>上下文构建优先使用摘要（节省 Token）；完整结果按 requestId 隔离存入 Redis，
     * 仅用于短期诊断和审计，不向模型暴露不存在的查询工具。</p>
     *
     * @param question 用户的原始问题
     * @param results  各子任务的执行结果
     * @return 合并后的最终回答
     */
    public String merge(String question, List<SubTaskResult> results) {
        return merge(question, results, null);
    }

    public String merge(String question, List<SubTaskResult> results, String requestId) {
        if (results == null || results.isEmpty()) return "";
        storeFullResults(results, requestId);

        List<SubTaskResult> requiredFailures = requiredFailures(results);
        if (!requiredFailures.isEmpty()) {
            return requiredFailureReply(requiredFailures);
        }

        List<SubTaskResult> mergeable = applyMergePolicies(results);
        String optionalWarning = optionalFailureWarning(results);
        if (mergeable.isEmpty()) return optionalWarning;
        if (mergeable.size() == 1) {
            return appendWarning(mergeable.get(0).getResult(), optionalWarning);
        }
        List<String> conflicts = structuredConflicts(mergeable);
        if (!conflicts.isEmpty()) {
            return appendWarning(conflictReply(conflicts), optionalWarning);
        }

        // A single domain already owns the execution order and the factual wording of each
        // result. Re-sending those deterministic order results to an LLM adds seconds of
        // latency and can accidentally rewrite lifecycle states (for example, 待发货 → 已支付).
        if (isDeterministicOrderWorkflow(mergeable)) {
            String merged = fallbackMerge(mergeable);
            log.info("[ResultMerger] 同域订单结果确定性合并: agents={}, resultLength={}",
                    mergeable.size(), merged.length());
            return appendWarning(merged, optionalWarning);
        }
        if (isStructuredWorkflow(mergeable)) {
            String merged = deterministicMerge(mergeable);
            log.info("[ResultMerger] 结构化结果确定性合并: nodes={}, resultLength={}",
                    mergeable.size(), merged.length());
            return appendWarning(merged, optionalWarning);
        }

        // ⭐ 上下文构建：摘要优先（替代原来的全量 result 拼接）
        StringBuilder context = new StringBuilder();
        for (var r : mergeable) {
            if (!r.isSuccess() || r.getResult() == null || r.getResult().isBlank()) continue;
            String source = r.getAgentName() != null ? r.getAgentName() : "unknown";
            String summary = r.getSummary() != null && !r.getSummary().isBlank()
                    ? r.getSummary() : r.getResult();

            context.append("【").append(source).append("】").append(r.getDescription()).append("\n");
            context.append("【摘要】").append(summary).append("\n");
            context.append("\n");
        }

        String prompt = String.format("""
                你是一个信息整合专家。请根据用户的问题和各专业助理的回答摘要，
                整合成一份连贯、完整的最终回答。

                要求：
                - 按逻辑顺序组织内容，不要简单罗列
                - 保留每个回答中的关键信息（商品名称、价格、库存、订单状态等）
                - 去掉重复内容
                - 语言自然流畅，像一个人在回答
                - 在引用特定信息时标注来源，如"根据景点攻略的推荐"、"美食助理提到"
                - 严禁编造引用来源。如果某个助理的回答中没有出现游记标题，不要在整合时自行添加或推断标题
                - 只需要输出整合后的回答，不要多余解释

                ⚠️ 下面列出的是各助理回答的摘要（非完整原文）。
                只能依据这些摘要整合；不要声称调用、发现或建议使用任何未明确提供的工具。
                如果摘要缺少细节，请明确说明信息不足，不得自行补造。

                用户问题：%s

                各助理的回答摘要：
                %s
                """, question, context.toString().trim());

        try {
            String merged = chatClient.prompt().user(prompt).call().content();
            log.info("[ResultMerger] 合并完成: question={}, agents={}, resultLength={}",
                    question, results.size(), merged != null ? merged.length() : 0);
            return appendWarning(merged != null ? merged.trim() : "", optionalWarning);
        } catch (Exception e) {
            log.warn("[ResultMerger] 合并失败: {}", e.getMessage());
            return appendWarning(fallbackMerge(mergeable), optionalWarning);
        }
    }

    /**
     * 将完整子任务结果按请求范围存入 Redis，供短期诊断和审计。
     * 无 Redis 时静默跳过。
     */
    private void storeFullResults(List<SubTaskResult> results, String requestId) {
        if (redisTemplate == null) return;
        String requestScope = requestId != null && !requestId.isBlank()
                ? requestId : "unscoped-" + java.util.UUID.randomUUID();
        for (var r : results) {
            if (r.getTaskId() == null || r.getResult() == null) continue;
            try {
                String key = TASK_RESULT_PREFIX + requestScope + ":" + r.getTaskId();
                redisTemplate.opsForValue().set(key, formatFullResult(r), TASK_RESULT_TTL);
            } catch (Exception e) {
                log.debug("[ResultMerger] 存储 task 完整结果到 Redis 失败: taskId={}", r.getTaskId());
            }
        }
    }

    /**
     * 格式化完整结果（含元数据）。
     */
    private String formatFullResult(SubTaskResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("taskId: ").append(r.getTaskId()).append("\n");
        sb.append("agent: ").append(r.getAgentName()).append("\n");
        sb.append("description: ").append(r.getDescription()).append("\n");
        sb.append("success: ").append(r.isSuccess()).append("\n");
        sb.append("required: ").append(r.isRequired()).append("\n");
        sb.append("mergePolicy: ").append(r.getMergePolicy()).append("\n");
        if (r.getOutputSchema() != null) {
            sb.append("outputSchema: ").append(r.getOutputSchema()).append("\n");
        }
        if (r.getErrorType() != null && r.getErrorType() != SubTaskResult.ErrorType.NONE) {
            sb.append("errorType: ").append(r.getErrorType()).append("\n");
        }
        sb.append("--- 完整结果 ---\n");
        sb.append(r.getResult());
        return sb.toString();
    }

    /**
     * 降级：简单拼接（LLM 不可用时）
     */
    private String fallbackMerge(List<SubTaskResult> results) {
        StringBuilder sb = new StringBuilder();
        for (var r : results) {
            if (r.getResult() != null && !r.getResult().isBlank()) {
                sb.append(r.getResult()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    static boolean isDeterministicOrderWorkflow(List<SubTaskResult> results) {
        return results != null && results.size() > 1
                && results.stream().allMatch(result -> result != null
                && result.isSuccess()
                && "order".equalsIgnoreCase(result.getAgentName())
                && result.getResult() != null
                && !result.getResult().isBlank()
                && result.getDomainQuality().isPass()
                && Boolean.TRUE.equals(result.getStructuredData().get("verified"))
                && Boolean.TRUE.equals(result.getStructuredData().get("criteriaSatisfied")));
    }

    static List<SubTaskResult> applyMergePolicies(List<SubTaskResult> results) {
        List<SubTaskResult> selected = new ArrayList<>();
        if (results == null) return selected;
        for (SubTaskResult result : results) {
            if (result == null || !result.isSuccess()
                    || result.getResult() == null || result.getResult().isBlank()) continue;
            if (result.getMergePolicy() == ExecutionPlan.MergePolicy.REPLACE) {
                selected.clear();
            }
            selected.add(result);
        }
        return List.copyOf(selected);
    }

    static List<SubTaskResult> requiredFailures(List<SubTaskResult> results) {
        if (results == null) return List.of();
        return results.stream().filter(result -> result != null
                && result.isRequired() && !result.isSuccess()).toList();
    }

    static boolean isStructuredWorkflow(List<SubTaskResult> results) {
        return results != null && !results.isEmpty()
                && results.stream().allMatch(result -> result != null
                && result.isSuccess()
                && result.getMergePolicy() != ExecutionPlan.MergePolicy.APPEND
                && result.getDomainQuality().isPass()
                && !result.getStructuredData().isEmpty());
    }

    /** Detects contradictory facts emitted for the same versioned schema and entity. */
    static List<String> structuredConflicts(List<SubTaskResult> results) {
        Map<String, FactValue> facts = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        if (results == null) return conflicts;
        for (SubTaskResult result : results) {
            if (result == null || !result.isSuccess()
                    || result.getOutputSchema() == null || result.getOutputSchema().isBlank()
                    || result.getStructuredData().isEmpty()) continue;
            String scope = result.getOutputSchema() + "#" + entityIdentity(result.getStructuredData());
            Map<String, Object> flattened = new LinkedHashMap<>();
            flattenFacts("", result.getStructuredData(), flattened);
            for (Map.Entry<String, Object> entry : flattened.entrySet()) {
                String key = scope + ":" + entry.getKey();
                FactValue previous = facts.putIfAbsent(
                        key, new FactValue(result.getTaskId(), entry.getValue()));
                if (previous != null && !Objects.deepEquals(previous.value(), entry.getValue())) {
                    conflicts.add(result.getOutputSchema() + "." + entry.getKey()
                            + " 在节点 " + previous.taskId() + " 与 " + result.getTaskId()
                            + " 之间不一致");
                }
            }
        }
        return List.copyOf(conflicts);
    }

    private static String entityIdentity(Map<String, Object> data) {
        for (String key : List.of("id", "orderId", "productId", "sku", "executionId")) {
            Object value = data.get(key);
            if (value != null && !value.toString().isBlank()) return key + "=" + value;
        }
        return "global";
    }

    private static void flattenFacts(String prefix, Map<String, Object> data,
                                     Map<String, Object> target) {
        data.forEach((key, value) -> {
            String path = prefix.isBlank() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> converted = new LinkedHashMap<>();
                nested.forEach((nestedKey, nestedValue) ->
                        converted.put(Objects.toString(nestedKey), nestedValue));
                flattenFacts(path, converted, target);
            } else {
                target.put(path, value);
            }
        });
    }

    private static String deterministicMerge(List<SubTaskResult> results) {
        StringBuilder answer = new StringBuilder();
        for (SubTaskResult result : results) {
            if (!answer.isEmpty()) answer.append("\n\n");
            answer.append(result.getResult().trim());
        }
        return answer.toString();
    }

    private static String conflictReply(List<String> conflicts) {
        StringBuilder answer = new StringBuilder(
                "并行节点返回了相互冲突的已核实事实，暂时不能生成唯一结论：");
        conflicts.forEach(conflict -> answer.append("\n- ").append(conflict));
        answer.append("\n请重新查询冲突数据后再合并。");
        return answer.toString();
    }

    private static String requiredFailureReply(List<SubTaskResult> failures) {
        StringBuilder answer = new StringBuilder("未能完成以下必要步骤，因此暂时无法给出完整结论：");
        for (SubTaskResult failure : failures) {
            answer.append("\n- ").append(failure.getDescription());
            if (failure.getErrorType() != null
                    && failure.getErrorType() != SubTaskResult.ErrorType.NONE) {
                answer.append("（").append(failure.getErrorType()).append("）");
            }
        }
        return answer.toString();
    }

    private static String optionalFailureWarning(List<SubTaskResult> results) {
        if (results == null) return "";
        List<String> missing = results.stream()
                .filter(result -> result != null && !result.isRequired() && !result.isSuccess())
                .map(SubTaskResult::getDescription).filter(value -> value != null && !value.isBlank())
                .toList();
        return missing.isEmpty() ? ""
                : "部分可选信息未获取成功：" + String.join("、", missing) + "。";
    }

    private static String appendWarning(String answer, String warning) {
        if (warning == null || warning.isBlank()) return answer != null ? answer : "";
        if (answer == null || answer.isBlank()) return warning;
        return answer.trim() + "\n\n> " + warning;
    }

    private record FactValue(String taskId, Object value) {}
}
