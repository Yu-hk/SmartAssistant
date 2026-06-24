package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.SubTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多 Agent 结果合并服务。
 * 将多个子任务的执行结果合并为一份连贯的最终回答。
 */
@Component
public class ResultMerger {

    private static final Logger log = LoggerFactory.getLogger(ResultMerger.class);
    private final ChatClient chatClient;

    public ResultMerger(@Qualifier("lightChatModel") ChatModel lightModel) {
        this.chatClient = ChatClient.create(lightModel);
    }

    /**
     * 合并多个 Agent 的执行结果。
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

        StringBuilder context = new StringBuilder();
        for (var r : results) {
            if (r.getResult() == null || r.getResult().isBlank()) continue;
            String source = r.getAgentName() != null ? r.getAgentName() : "unknown";
            context.append("【").append(source).append("】").append(r.getDescription()).append("\n");
            context.append(r.getResult()).append("\n\n");
        }

        String prompt = String.format("""
                你是一个信息整合专家。请根据用户的问题和各专业助理的回答，
                整合成一份连贯、完整的最终回答。

                要求：
                - 按逻辑顺序组织内容，不要简单罗列
                - 保留每个回答中的关键信息（景点名称、餐厅推荐、价格、天气等）
                - 去掉重复内容
                - 语言自然流畅，像一个人在回答
                - 在引用特定信息时标注来源，如"根据景点攻略的推荐"、"美食助理提到"
                - 严禁编造引用来源。如果某个助理的回答中没有出现游记标题，不要在整合时自行添加或推断标题
                - 只需要输出整合后的回答，不要多余解释

                用户问题：%s

                各助理的回答：
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
