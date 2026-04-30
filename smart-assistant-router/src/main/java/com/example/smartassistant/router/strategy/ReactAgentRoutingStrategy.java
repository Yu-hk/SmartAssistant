package com.example.smartassistant.router.strategy;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.AgentDiscoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ReactAgent 路由策略
 * 使用 ReactAgent 进行智能路由决策（替代旧的 LlmRoutingStrategy）
 */
@Slf4j
@Component
public class ReactAgentRoutingStrategy implements RoutingStrategy {
    
    private final ReactAgent routerAgent;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentDiscoveryService agentDiscoveryService;
    
    public ReactAgentRoutingStrategy(ReactAgent routerAgent, AgentDiscoveryService agentDiscoveryService) {
        this.routerAgent = routerAgent;
        this.agentDiscoveryService = agentDiscoveryService;
        log.info("[ReactAgentRoutingStrategy] 初始化完成");
    }
    
    @Override
    public RouteDecision route(String userInput, Map<String, Object> context) {
        log.debug("[ReactAgentRouting] 执行 ReactAgent 路由决策: inputLength={}", userInput.length());
        
        try {
            // 调用 ReactAgent
            var response = routerAgent.call(userInput);
            
            if (response == null) {
                log.warn("[ReactAgentRouting] ReactAgent 返回 null");
                return null;
            }
            
            // 提取文本内容（去除 AssistantMessage 包装）
            String result = extractTextContent(response);
            
            if (result == null || result.isEmpty()) {
                log.warn("[ReactAgentRouting] 无法提取响应文本");
                return null;
            }
            
            log.debug("[ReactAgentRouting] ReactAgent 原始响应: {}", truncate(result));
            
            // 解析 JSON，提取路由决策信息
            RouteDecision decision = parseRoutingDecision(result);
            
            if (decision != null) {
                decision.setRoutingMethod("REACT_AGENT_ROUTING");
                
                log.info("[ReactAgentRouting] 路由决策成功: agentName={}, confidence={}, reason={}",
                        decision.getAgentName(), 
                        decision.getConfidence(), 
                        decision.getReason());
                
                return decision;
            }
            
            log.warn("[ReactAgentRouting] 解析路由决策失败");
            return null;
            
        } catch (Exception e) {
            log.error("[ReactAgentRouting] ReactAgent 路由异常: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public String getStrategyName() {
        return "REACT_AGENT_ROUTING";
    }
    
    @Override
    public int getPriority() {
        return 99; // 最低优先级，暂时禁用（StringTemplate 问题未解决）
    }
    
    /**
     * 从 AssistantMessage 中提取文本内容
     */
    private String extractTextContent(Object response) {
        if (response instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        } else if (response instanceof String str) {
            return str;
        } else {
            // 尝试 toString()
            String responseStr = response.toString();
            if (responseStr.contains("textContent=")) {
                int startIdx = responseStr.indexOf("textContent=") + "textContent=".length();
                int endIdx = responseStr.lastIndexOf(", metadata=");
                if (endIdx > startIdx) {
                    return responseStr.substring(startIdx, endIdx);
                } else {
                    return responseStr.substring(startIdx);
                }
            }
            return responseStr;
        }
    }
    
    /**
     * 清理响应文本，提取纯净的 JSON
     * 处理各种 markdown 代码块格式：
     * - ```json ... ``` （多行代码块）
     * - ``` ... ``` （无语言标识）
     * - 直接返回 {...} （无代码块）
     * - 前后有多余文本
     */
    private String cleanJsonResponse(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }

        // 策略1：查找 JSON 对象的起始和结束位置
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            String json = response.substring(start, end + 1).trim();
            // 清理残留的 ``` 标记
            json = json.replaceFirst("^```json\\s*", "")
                       .replaceFirst("^```\\s*", "")
                       .trim();
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3).trim();
            }
            return json;
        }

        // 策略2：fallback 到清理 markdown 标记
        return response
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```\\s*$", "")
                .trim();
    }

    /**
     * 从 ReactAgent 响应中解析路由决策
     * <p>只处理单意图，不再支持多意图场景</p>
     */
    private RouteDecision parseRoutingDecision(String jsonResponse) {
        // 提取纯净 JSON
        String cleaned = cleanJsonResponse(jsonResponse);

        try {
            JsonNode json = objectMapper.readTree(cleaned);

            RouteDecision decision = new RouteDecision();

            // ⭐ 兼容 agentName 和 serviceName 两种字段名（单意图场景）
            String agentName = null;
            if (json.has("serviceName") && !json.get("serviceName").isNull()) {
                agentName = json.get("serviceName").asText();
            } else if (json.has("agentName") && !json.get("agentName").isNull()) {
                agentName = json.get("agentName").asText();
            }

            // 降级兼容：如果顶层没有 agentName，尝试从 matchedAgents 取第一个（旧 Prompt 残留）
            if ((agentName == null || agentName.isEmpty()) && json.has("matchedAgents")) {
                JsonNode agents = json.get("matchedAgents");
                if (agents.isArray() && !agents.isEmpty()) {
                    JsonNode first = agents.get(0);
                    if (first.has("serviceName") && !first.get("serviceName").isNull()) {
                        agentName = first.get("serviceName").asText();
                    } else if (first.has("agentName") && !first.get("agentName").isNull()) {
                        agentName = first.get("agentName").asText();
                    }
                    if (agentName != null && !agentName.isEmpty()) {
                        log.info("[ReactAgentRouting] 从 matchedAgents 取首个 agent: {}", agentName);
                    }
                }
            }

            if (agentName == null || agentName.isEmpty()) {
                log.warn("[ReactAgentRouting] 缺少 serviceName/agentName 字段");
                return null;
            }
            // ⭐ 映射别名到实际 Agent 名称
            agentName = mapAgentName(agentName);
            decision.setAgentName(agentName);

            if (json.has("confidence") && !json.get("confidence").isNull()) {
                decision.setConfidence(json.get("confidence").asDouble());
            } else {
                decision.setConfidence(0.8); // 默认置信度
            }

            if (json.has("reason") && !json.get("reason").isNull()) {
                decision.setReason(json.get("reason").asText());
            } else {
                decision.setReason("未提供");
            }

            // ⭐ 多意图场景：从 matchedAgents 中提取 confidence 和 reason
            if (json.has("matchedAgents") && !json.get("matchedAgents").isNull()) {
                JsonNode agents = json.get("matchedAgents");
                if (agents.isArray() && !agents.isEmpty()) {
                    JsonNode first = agents.get(0);
                    if (first.has("confidence") && !first.get("confidence").isNull()) {
                        decision.setConfidence(first.get("confidence").asDouble());
                    }
                    if (first.has("reason") && !first.get("reason").isNull()) {
                        decision.setReason(first.get("reason").asText());
                    }
                }
            }

            // 解析 extractedInfo/extractedContext → extractedContext
            JsonNode extractedNode = null;
            if (json.has("extractedContext") && !json.get("extractedContext").isNull()) {
                extractedNode = json.get("extractedContext");
            } else if (json.has("extractedInfo") && !json.get("extractedInfo").isNull()) {
                extractedNode = json.get("extractedInfo");
            }

            if (extractedNode != null) {
                RouteDecision.ExtractedContext extractedContext = RouteDecision.ExtractedContext.builder()
                        .location(extractedNode.has("location") ? extractedNode.get("location").asText() : null)
                        .intent(extractedNode.has("intent") ? extractedNode.get("intent").asText() : null)
                        .timeRange(extractedNode.has("timeRange") ? extractedNode.get("timeRange").asText() : null)
                        .build();

                decision.setExtractedContext(extractedContext);
            }

            return decision;

        } catch (Exception e) {
            log.error("[ReactAgentRouting] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }
    
    private String mapAgentName(String name) {
        if (name == null) return null;
        // 通过 Nacos 发现验证 agent 是否存在
        try {
            var agents = agentDiscoveryService.discoverAllAgents();
            for (var agent : agents) {
                if (name.equals(agent.getAgentName())) return name;
            }
            // 如果名称不匹配已发现的 agent，尝试从 serviceName 匹配
            for (var agent : agents) {
                if (name.contains(agent.getAgentName().replace("_", "")) ||
                    agent.getAgentName().contains(name.replace("_", ""))) {
                    return agent.getAgentName();
                }
            }
        } catch (Exception e) {
            log.warn("[ReactAgentRouting] 动态映射 agent 名称失败, 使用原始名称: {}", name);
        }
        // 降级返回原名称
        return name;
    }

    /**
     * 截断字符串（用于日志）
     */
    private String truncate(String str) {
        if (str == null) return "";
        return str.length() > 200 ? str.substring(0, 200) + "..." : str;
    }
}
