package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.SubTask;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 任务分解服务。
 * 通过 AgentDiscoveryService 动态发现可用 Agent，无需硬编码。
 * 所有提问均经过规划，单意图问题返回单个子任务。
 */
@Component
public class TaskPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlannerService.class);
    private final ChatClient chatClient;
    private final AgentDiscoveryService agentDiscovery;

    public TaskPlannerService(ChatClient.Builder builder, AgentDiscoveryService agentDiscovery) {
        this.chatClient = builder.build();
        this.agentDiscovery = agentDiscovery;
    }

    /**
     * 将问题分解为子任务。单意图返回单个子任务，多意图返回多个。
     */
    public List<SubTask> plan(String question) {
        String agentList = buildAgentList();
        if (agentList.isEmpty()) {
            log.warn("[TaskPlanner] 无可用 Agent，使用整句");
            return List.of(new SubTask("t1", question, findFallbackAgent()));
        }

        String fallback = findFallbackAgent();
        String prompt = String.format("""
                你是一个任务规划专家。请将用户的问题拆解为多个独立的子任务。
                每个子任务交给最合适的专业助理处理。

                可用专业助理（从服务发现动态获取，含关键词和能力描述）：
                %s

                要求：
                - 每个子任务一行，格式：子任务ID|描述|目标助理
                - 子任务之间不应有依赖关系（可并行执行）
                - 描述应包含具体的查询内容，便于助理理解
                - 如果用户的问题只涉及一个领域，只输出一个子任务
                - 不要合并不同领域的查询到同一个子任务中
                - 只输出任务列表，不要多余解释
                - 优先根据助理的关键词和能力进行匹配。当用户意图与任何助理均不匹配时，使用兜底助理：%s

                用户：%s
                """, agentList, fallback, question);

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            List<SubTask> tasks = parseTasks(response);
            if (tasks.isEmpty()) {
                log.warn("[TaskPlanner] LLM 返回格式异常，使用整句。响应: {}", response);
                return List.of(new SubTask("t1", question, findFallbackAgent()));
            }
            return tasks;
        } catch (Exception e) {
            log.warn("[TaskPlanner] LLM 分解失败: {}", e.getMessage());
            return List.of(new SubTask("t1", question, findFallbackAgent()));
        }
    }

    /**
     * 从 AgentDiscoveryService 动态构建 Agent 列表文本。
     * 包含 agentName + keywords + capabilities，使 LLM 能精准分配。
     */
    private String buildAgentList() {
        Collection<DiscoveredAgent> agents = agentDiscovery.getCachedAgents();
        if (agents == null || agents.isEmpty()) return "";

        return agents.stream()
                .filter(a -> a.getAgentName() != null && a.getHealthy())
                .map(a -> {
                    String name = a.getAgentName();
                    String keywords = a.getMetadata() != null ? a.getMetadata().getKeywords() : "";
                    String caps = a.getMetadata() != null ? a.getMetadata().getCapabilities() : "";
                    return String.format("  - %s：关键词=[%s] 能力=[%s]", name,
                            keywords != null ? keywords : "",
                            caps != null ? caps : "");
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 动态查找兜底 Agent（metadata.priority 最高即数值最大的）。
     * 无兜底时返回 null，由调用方处理。
     */
    private String findFallbackAgent() {
        DiscoveredAgent fallback = agentDiscovery.findFallbackAgent();
        return fallback != null ? fallback.getAgentName() : null;
    }

    private List<SubTask> parseTasks(String response) {
        List<SubTask> tasks = new ArrayList<>();
        if (response == null || response.isBlank()) return tasks;

        Pattern pattern = Pattern.compile("(\\w+)\\|([^|]+)\\|([^|\\n]+)");
        Matcher matcher = pattern.matcher(response);
        while (matcher.find()) {
            String id = matcher.group(1).trim();
            String desc = matcher.group(2).trim();
            String agent = matcher.group(3).trim();
            tasks.add(new SubTask(id, desc, agent));
            log.debug("[TaskPlanner] 子任务: id={}, agent={}, desc={}", id, agent, desc);
        }
        return tasks;
    }
}
