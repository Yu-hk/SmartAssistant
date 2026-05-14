package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.SubTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务分解服务。
 * 将用户复杂提问拆解为多个子任务，每个子任务分配给对应 Agent。
 */
@Component
public class TaskPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlannerService.class);
    private final ChatClient chatClient;

    public TaskPlannerService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 判断是否为需要分解的复杂提问（涉及多个领域）。
     */
    public boolean isComplexQuestion(String question) {
        if (question == null || question.isBlank()) return false;
        String q = question.toLowerCase();
        int domainCount = 0;
        if (containsAny(q, "景点", "游玩", "好玩", "景区", "门票", "开放", "公园", "博物馆", "长城", "故宫")) domainCount++;
        if (containsAny(q, "美食", "餐厅", "吃饭", "好吃", "菜系", "川菜", "火锅", "烧烤", "小吃", "推荐菜")) domainCount++;
        if (containsAny(q, "天气", "温度", "气温", "下雨", "台风", "晴", "雨", "雪", "风")) domainCount++;
        if (containsAny(q, "酒店", "住宿", "民宿", "宾馆", "住")) domainCount++;
        if (containsAny(q, "交通", "地铁", "公交", "打车", "开车", "高铁", "火车")) domainCount++;
        // 涉及 ≥ 2 个领域即视为复杂提问
        return domainCount >= 2;
    }

    private boolean containsAny(String q, String... keywords) {
        for (String kw : keywords) {
            if (q.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 将复杂问题分解为子任务列表。
     * 使用 LLM 确保领域覆盖准确，避免关键词误判。
     */
    public List<SubTask> plan(String question, Long userId) {
        String prompt = String.format("""
                你是一个任务规划专家。请将用户的复杂问题拆解为多个独立的子任务。
                每个子任务交给最合适的专业助理处理。
                
                可用的专业助理：
                - location_weather：景点推荐、天气查询、出行规划、活动推荐
                - food_recommendation：美食推荐、餐厅查询、菜系推荐
                - general_chat：闲聊、问答、翻译、计算（兜底）
                
                要求：
                - 每个子任务一行，格式：子任务ID|描述|目标助理
                - 子任务之间不应有依赖关系（可并行执行）
                - 描述应包含具体的查询内容，便于助理理解
                - 不要合并不同领域的查询到同一个子任务中
                - 只输出任务列表，不要多余解释
                
                示例：
                用户：周末去北京玩，推荐景点和川菜馆，天气如何
                输出：
                t1|北京热门景点和三日游攻略|location_weather
                t2|北京正宗川菜餐厅推荐|food_recommendation
                t3|北京这个周末的天气预报|location_weather
                
                用户：%s
                """, question);

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            return parseTasks(response);
        } catch (Exception e) {
            log.warn("[TaskPlanner] LLM 分解失败: {}", e.getMessage());
            return List.of(new SubTask("t1", question, guessAgent(question)));
        }
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

        if (tasks.isEmpty()) {
            log.warn("[TaskPlanner] LLM 返回格式异常，使用整句。响应: {}", response);
        }
        return tasks;
    }

    /**
     * 降级：关键词猜测目标 Agent
     */
    private String guessAgent(String question) {
        String q = question.toLowerCase();
        if (containsAny(q, "美食", "餐厅", "好吃", "菜", "火锅", "烧烤", "小吃", "吃饭")) return "food_recommendation";
        if (containsAny(q, "天气", "温度", "景点", "游玩", "好玩", "景区")) return "location_weather";
        return "general_chat";
    }
}
