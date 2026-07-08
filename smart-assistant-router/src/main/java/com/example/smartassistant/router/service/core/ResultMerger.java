package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.rag.advisor.AiChatService;
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
import java.util.List;

/**
 * 多 Agent 结果合并服务。
 *
 * <p>⭐ 借鉴文章⑤「三层上下文注入」设计：
 * <ul>
 *   <li><b>摘要优先</b>：上下文构建优先使用 {@link SubTaskResult#getSummary()}（前 200 字符），
 *       全量结果通过 Redis 存储供按需工具查询，避免全量拼接 Token 膨胀。</li>
 *   <li><b>按需查询</b>：完整结果存入 Redis Key {@code a2a:task-result:{taskId}}（TTL=300s），
 *       供 {@code getAsyncTaskResult} 工具获取。</li>
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
     * <p>上下文构建优先使用摘要（节省 Token）；完整结果存入 Redis 供后续工具按需查询。
     * LLM 整合时基础信息来自摘要，需确认细节时可通过 {@code getAsyncTaskResult(taskId)} 工具获取全量原文。</p>
     *
     * @param question 用户的原始问题
     * @param results  各子任务的执行结果
     * @return 合并后的最终回答
     */
    public String merge(String question, List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return "";
        if (results.size() == 1) {
            return results.get(0).getResult();
        }

        // ⭐ 存储完整结果到 Redis（供 getAsyncTaskResult 工具按需查询）
        storeFullResults(results);

        // ⭐ 上下文构建：摘要优先（替代原来的全量 result 拼接）
        StringBuilder context = new StringBuilder();
        for (var r : results) {
            if (r.getResult() == null || r.getResult().isBlank()) continue;
            String source = r.getAgentName() != null ? r.getAgentName() : "unknown";
            String summary = r.getSummary() != null && !r.getSummary().isBlank()
                    ? r.getSummary() : r.getResult();

            context.append("【").append(source).append("】").append(r.getDescription()).append("\n");
            context.append("【摘要】").append(summary).append("\n");
            context.append("（如需查看完整原文，可使用 getAsyncTaskResult 工具查询 taskId=")
                    .append(r.getTaskId()).append("）\n\n");
        }

        String prompt = String.format("""
                你是一个信息整合专家。请根据用户的问题和各专业助理的回答摘要，
                整合成一份连贯、完整的最终回答。

                要求：
                - 按逻辑顺序组织内容，不要简单罗列
                - 保留每个回答中的关键信息（景点名称、餐厅推荐、价格、天气等）
                - 去掉重复内容
                - 语言自然流畅，像一个人在回答
                - 在引用特定信息时标注来源，如"根据景点攻略的推荐"、"美食助理提到"
                - 严禁编造引用来源。如果某个助理的回答中没有出现游记标题，不要在整合时自行添加或推断标题
                - 只需要输出整合后的回答，不要多余解释

                ⚠️ 下面列出的是各助理回答的摘要（非完整原文）。
                如果需要确认某个助理的完整回答，可调用 getAsyncTaskResult 工具查询。
                请先基于摘要做整合，确保涵盖所有关键信息点。

                用户问题：%s

                各助理的回答摘要：
                %s
                """, question, context.toString().trim());

        try {
            String merged = chatClient.prompt().user(prompt).call().content();
            log.info("[ResultMerger] 合并完成: question={}, agents={}, resultLength={}",
                    question, results.size(), merged != null ? merged.length() : 0);
            return merged != null ? merged.trim() : "";
        } catch (Exception e) {
            log.warn("[ResultMerger] 合并失败: {}", e.getMessage());
            return fallbackMerge(results);
        }
    }

    /**
     * 将完整子任务结果存入 Redis（供 {@code getAsyncTaskResult} 工具按需查询）。
     * 无 Redis 时静默跳过。
     */
    private void storeFullResults(List<SubTaskResult> results) {
        if (redisTemplate == null) return;
        for (var r : results) {
            if (r.getTaskId() == null || r.getResult() == null) continue;
            try {
                String key = TASK_RESULT_PREFIX + r.getTaskId();
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
}
