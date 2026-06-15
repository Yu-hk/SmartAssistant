/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.agent.dto.AgentChatRequest;
import com.example.smartassistant.common.agent.dto.AgentChatResponse;
import com.example.smartassistant.common.api.AgentApiResponse;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.extraction.KeywordExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Agent Caller Service - 通过 REST 调用 Provider Agent
 * <p>替代原有的 A2A 协议，使用统一 {@link AgentChatRequest}/{@link AgentChatResponse} 格式
 * 通过 HTTP POST 调用下游 Agent 的 {@code /api/agent/chat} 端点。</p>
 */
@Service
public class AgentCallerService {

    private static final Logger log = LoggerFactory.getLogger(AgentCallerService.class);

    private final AgentDiscoveryService agentDiscoveryService;
    private final KeywordExtractionService keywordExtractionService;
    private final RestClient restClient;

    public AgentCallerService(AgentDiscoveryService agentDiscoveryService,
                              KeywordExtractionService keywordExtractionService,
                              RestClient.Builder restClientBuilder) {
        this.agentDiscoveryService = agentDiscoveryService;
        this.keywordExtractionService = keywordExtractionService;
        this.restClient = restClientBuilder.build();
    }

    // ==================== 公开方法 ====================

    /**
     * 调用目标 Agent（仅返回文本）
     *
     * @param agentName Agent 名称
     * @param question  原始问题
     * @param userId    用户 ID
     * @return Agent 响应文本
     */
    public String callAgent(String agentName, String question, Long userId) {
        return callAgentWithContext(agentName, question, userId, null, null);
    }

    /**
     * 调用目标 Agent（带 requestId 透传）
     */
    public String callAgent(String agentName, String question, Long userId, String requestId) {
        return callAgentWithContext(agentName, question, userId, null, requestId);
    }

    /**
     * 调用目标 Agent 并提取工具输出中的真实游记标题（用于引用校验）。
     */
    public AgentCallResult callAgentAndExtractTitles(String agentName, String question, Long userId) {
        return callAgentAndExtractTitles(agentName, question, userId, null);
    }

    /**
     * 调用目标 Agent 并提取工具输出中的真实游记标题（带 requestId 透传）。
     */
    public AgentCallResult callAgentAndExtractTitles(String agentName, String question, Long userId, String requestId) {
        log.info("[AgentCaller] callAgentAndExtractTitles: agent={}, userId={}, questionLength={}, requestId={}",
                agentName, userId, question != null ? question.length() : 0, requestId);

        try {
            String agentUrl = findAgentUrl(agentName);
            if (agentUrl == null) {
                return new AgentCallResult("❌ 未找到目标 Agent: " + agentName);
            }

            String instruction = buildOptimizedInstruction(question, null);
            String enrichedInstruction = enrichWithRequestId(instruction, requestId);

            AgentChatRequest chatRequest = new AgentChatRequest(enrichedInstruction, userId, requestId);
            AgentChatResponse agentResponse = doRestCall(agentUrl, chatRequest);

            if (agentResponse == null) {
                return new AgentCallResult("⚠️ Agent 返回空结果");
            }

            String response = agentResponse.response();
            if (response == null) {
                return new AgentCallResult("⚠️ Agent 返回空响应");
            }

            // 从响应文本中解析真实游记标题和标签
            List<String> realTitles = parseTitlesFromText(response);
            Map<String, String> tagsByTitle = parseTagsFromText(response);

            // 清理思考过程内容
            response = cleanThinkingContent(response);

            // ⭐ 后处理：去除响应中不存在于真实标题列表的引用
            if (!realTitles.isEmpty()) {
                response = stripFakeCitations(response, realTitles);
            }

            log.info("[AgentCaller] Agent 调用成功: {}, 响应长度={}, titles={}",
                    agentName, response.length(), realTitles.size());

            return new AgentCallResult(response, realTitles, tagsByTitle);

        } catch (Exception e) {
            log.error("[AgentCaller] Agent 调用失败: {}, 错误: {}", agentName, e.getMessage(), e);
            return new AgentCallResult("❌ 调用 Agent 失败: " + e.getMessage());
        }
    }

    /**
     * 调用目标 Agent（带提取的上下文信息 + requestId 透传）
     */
    public String callAgentWithContext(String agentName, String question, Long userId,
                                       RouteDecision.ExtractedContext context, String requestId) {
        log.info("[AgentCaller] 调用 Agent: {}, userId={}, questionLength={}, requestId={}",
                agentName, userId, question != null ? question.length() : 0, requestId);

        if (context != null) {
            log.info("[AgentCaller] 提取的上下文: location={}, intent={}",
                    context.getLocation(), context.getIntent());
        }

        try {
            // 构建 instruction（使用关键词提取优化）
            String instruction = buildOptimizedInstruction(question, context);
            log.debug("[AgentCaller] 优化后的 instruction: {}", instruction);

            // 查找 Agent URL
            String agentUrl = findAgentUrl(agentName);
            if (agentUrl == null) {
                log.error("[AgentCaller] 未找到 Agent: {}", agentName);
                return "❌ 未找到目标 Agent: " + agentName;
            }
            log.info("[AgentCaller] 目标 Agent URL: {}", agentUrl);

            // 注入 requestId
            String enrichedInstruction = enrichWithRequestId(instruction, requestId);

            // REST 调用
            AgentChatRequest chatRequest = new AgentChatRequest(enrichedInstruction, userId, requestId);
            AgentChatResponse agentResponse = doRestCall(agentUrl, chatRequest);

            if (agentResponse == null) {
                return "⚠️ Agent 返回空结果";
            }

            String response = agentResponse.response();
            if (response == null || response.isBlank()) {
                return "⚠️ Agent 执行成功但未返回有效结果";
            }

            // 清理思考过程内容
            response = cleanThinkingContent(response);

            log.info("[AgentCaller] Agent 调用成功: {}, 响应长度={}", agentName, response.length());
            return response;

        } catch (Exception e) {
            log.error("[AgentCaller] Agent 调用失败: {}, 错误: {}", agentName, e.getMessage(), e);
            return "❌ 调用 Agent 失败: " + e.getMessage();
        }
    }

    /**
     * 调用目标 Agent（不带 requestId）
     */
    public String callAgentWithContext(String agentName, String question, Long userId,
                                       RouteDecision.ExtractedContext context) {
        return callAgentWithContext(agentName, question, userId, context, null);
    }

    // ==================== REST 调用核心 ====================

    /**
     * 执行 REST POST 调用下游 Agent 的 {@code /api/agent/chat} 端点。
     *
     * @param agentUrl     Agent 基础 URL（如 {@code http://192.168.1.100:8085}）
     * @param chatRequest  请求体
     * @return Agent 响应，如果调用失败返回 {@code null}
     */
    private AgentChatResponse doRestCall(String agentUrl, AgentChatRequest chatRequest) {
        String chatEndpoint = agentUrl + "/api/agent/chat";
        log.debug("[AgentCaller] POST {}", chatEndpoint);

        try {
            AgentApiResponse<AgentChatResponse> apiResponse = restClient.post()
                    .uri(chatEndpoint)
                    .body(chatRequest)
                    .retrieve()
                    .body(new ParameterizedTypeReference<AgentApiResponse<AgentChatResponse>>() {});

            if (apiResponse == null) {
                log.warn("[AgentCaller] REST 调用返回 null, url={}", chatEndpoint);
                return null;
            }

            if (!apiResponse.isSuccess()) {
                log.warn("[AgentCaller] Agent 返回失败: code={}, message={}",
                        apiResponse.getError() != null ? apiResponse.getError().getCode() : "unknown",
                        apiResponse.getMessage());
                return null;
            }

            return apiResponse.getData();

        } catch (Exception e) {
            log.error("[AgentCaller] REST 调用异常, url={}, error={}", chatEndpoint, e.getMessage(), e);
            return null;
        }
    }

    // ==================== Instruction 构建 ====================

    /**
     * 构建优化的 Instruction（使用关键词提取）。
     */
    private String buildOptimizedInstruction(String questionOrPrompt,
                                             RouteDecision.ExtractedContext context) {
        if (questionOrPrompt != null && questionOrPrompt.contains("【当前问题】")) {
            log.debug("[AgentCaller] 检测到完整 Prompt 结构，使用分段优化");
            return keywordExtractionService.extractKeywordsFromFullPrompt(questionOrPrompt);
        }

        String keywordInstruction = keywordExtractionService.extractKeywordsAsInstruction(questionOrPrompt);
        if (keywordInstruction != null && !keywordInstruction.isEmpty()) {
            log.debug("[AgentCaller] 使用关键词提取的 instruction: {}", keywordInstruction);
            return keywordInstruction;
        }

        if (context != null) {
            String contextInstruction = buildInstructionFromContext(context);
            if (!contextInstruction.isEmpty()) {
                log.debug("[AgentCaller] 使用 context 构建的 instruction: {}", contextInstruction);
                return contextInstruction;
            }
        }

        log.debug("[AgentCaller] 未提取到关键词，使用空 instruction");
        return "";
    }

    /**
     * 从 context 构建 instruction（降级方案）。
     */
    private String buildInstructionFromContext(RouteDecision.ExtractedContext context) {
        if (context == null) return "";
        StringBuilder instruction = new StringBuilder();
        boolean hasContent = false;

        if (context.getLocation() != null && !context.getLocation().isEmpty()) {
            instruction.append("地点: ").append(context.getLocation());
            hasContent = true;
        }
        if (context.getIntent() != null && !context.getIntent().isEmpty()) {
            if (hasContent) instruction.append("; ");
            instruction.append("意图: ").append(context.getIntent());
            hasContent = true;
        }
        if (context.getTimeRange() != null && !context.getTimeRange().isEmpty()) {
            if (hasContent) instruction.append("; ");
            instruction.append("时间: ").append(context.getTimeRange());
            hasContent = true;
        }
        if (context.getAdditionalParams() != null && !context.getAdditionalParams().isEmpty()) {
            if (hasContent) instruction.append("; ");
            instruction.append("其他: ").append(context.getAdditionalParams());
        }
        return hasContent ? instruction.toString() : "";
    }

    // ==================== 服务发现 ====================

    /**
     * 从 AgentDiscoveryService 查找目标 Agent 的 URL。
     */
    private String findAgentUrl(String agentName) {
        try {
            List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
            for (DiscoveredAgent agent : agents) {
                if (agent.getAgentName().equals(agentName) ||
                    agent.getServiceName().equals(agentName)) {
                    log.info("[AgentCaller] ✅ 找到 Agent: {}, URL: {}", agentName, agent.getUrl());
                    return agent.getUrl();
                }
            }
            log.warn("[AgentCaller] ❌ 未找到 Agent: {}, 可用 Agents: {}",
                    agentName, agents.stream().map(DiscoveredAgent::getAgentName).toList());
            return null;
        } catch (Exception e) {
            log.error("[AgentCaller] 查找 Agent URL 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 标题/标签解析 ====================

    /**
     * 从文本中解析真实游记标题。
     * 先尝试 {@code ==REAL_TITLES==} 标记，再回退到旧格式。
     */
    private List<String> parseTitlesFromText(String text) {
        List<String> titles = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return titles;

        if (text.contains("==REAL_TITLES==")) {
            var tagsMap = parseTagsFromText(text);
            titles.addAll(tagsMap.keySet());
            if (!titles.isEmpty()) {
                log.info("[AgentCaller] 从 REAL_TITLES 提取到 {} 个标题", titles.size());
                return titles;
            }
        }

        // 回退：旧格式 title list
        try {
            if (!text.contains("【可引用的真实游记标题】")) return titles;
            int blockStart = text.indexOf("【可引用的真实游记标题】");
            String afterBlock = text.substring(blockStart);
            int blockEnd = afterBlock.indexOf("⚠️");
            String block = (blockEnd > 0) ? afterBlock.substring(0, blockEnd) : afterBlock;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[•●]\\s*([^\\n\\r]+)").matcher(block);
            while (m.find()) {
                String t = m.group(1).trim();
                if (!t.startsWith("⚠️") && !t.isEmpty() && !t.contains("严禁")) titles.add(t);
            }
        } catch (Exception e) {
            log.warn("[AgentCaller] 旧格式标题解析失败: {}", e.getMessage());
        }
        return titles;
    }

    /**
     * 从 {@code ==REAL_TITLES==} 标记行中解析 title→tags 映射。
     */
    private Map<String, String> parseTagsFromText(String text) {
        Map<String, String> map = new java.util.HashMap<>();
        if (text == null || !text.contains("==REAL_TITLES==")) return map;
        try {
            int pos = text.indexOf("==REAL_TITLES==");
            String after = text.substring(pos);
            for (String line : after.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.equals("==REAL_TITLES==")) continue;
                if (line.startsWith("==")) {
                    String content = line.substring(2);
                    int sep = content.indexOf('|');
                    String title = sep > 0 ? content.substring(0, sep).trim() : content.trim();
                    String tags = sep > 0 ? content.substring(sep + 1).trim() : "";
                    if (!title.isEmpty()) {
                        map.put(title, tags);
                        log.info("[AgentCaller] 从 REAL_TITLES 标记提取到标题: {}", title);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AgentCaller] REAL_TITLES 解析失败: {}", e.getMessage());
        }
        return map;
    }

    // ==================== 后处理 ====================

    /**
     * 去除响应中不存在于真实标题列表的引用标记。
     */
    private String stripFakeCitations(String response, List<String> realTitles) {
        if (response == null || response.isBlank()) return response;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[([^\\]]{2,80})]|《([^》]{2,80})》|【([^】]{2,80})】");
        java.util.regex.Matcher m = p.matcher(response);
        StringBuffer sb = new StringBuffer();
        int removed = 0;
        while (m.find()) {
            String inner = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
            boolean valid = realTitles.stream().anyMatch(rt -> rt.equals(inner) || rt.contains(inner) || inner.contains(rt));
            if (valid) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(inner));
                removed++;
            }
        }
        m.appendTail(sb);
        if (removed > 0) {
            log.info("[AgentCaller] 已移除 {} 个编造的标题引用（Agent 后处理）", removed);
        }
        return sb.toString();
    }

    /**
     * 清理回复文本中的思考过程内容。
     * <p>DeepSeek R1 等推理模型会在回复开头输出结构化的推理步骤，
     * 这些内容对用户是噪音，需要清理后才返回。</p>
     */
    private String cleanThinkingContent(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        String original = response;

        // 策略1：检测并移除开头的推理步骤编号
        if (response.matches("(?s)^\\s*\\d+[．.、].{2,30}(?:下面|现在|接下来|先|然后|最后|所以|因此|好的|好的，信息|好的，信息都)\\.{0,5}.{10,}")) {
            int cutoffIdx = -1;
            for (String sep : new String[]{"下面", "现在", "接下来", "好的，信息都齐了"}) {
                int idx = response.indexOf(sep);
                if (idx > 0 && idx < 100) {
                    cutoffIdx = idx + sep.length();
                    break;
                }
            }
            if (cutoffIdx > 0) {
                String cleaned = response.substring(cutoffIdx).trim();
                if (cleaned.startsWith("：") || cleaned.startsWith(":")) {
                    cleaned = cleaned.substring(1).trim();
                }
                if (!cleaned.isEmpty()) {
                    log.info("[AgentCaller] 清理思考步骤（前缀），长度: {} -> {}", response.length(), cleaned.length());
                    return cleaned;
                }
            }
        }

        // 策略2-5：移除各种思考区块标记
        String[][] thinkingBlocks = {
                {"[ModelThinking]", "[/ModelThinking]"},
                {"[思考内容]", "[/思考内容]"},
                {"[思考]", "[/思考]"},
                {"[reasoning]", "[/reasoning]"},
        };
        for (String[] block : thinkingBlocks) {
            if (response.contains(block[0])) {
                response = response.replaceAll("(?s)" + java.util.regex.Pattern.quote(block[0]) + ".*?"
                        + java.util.regex.Pattern.quote(block[1]), "");
                log.info("[AgentCaller] 清理 {} 区块，长度: {} -> {}", block[0], original.length(), response.length());
            }
        }

        // 策略6：移除开头的引导语/回应语
        String[] leadPatterns = {
                "^太好了！根据[^，,]{1,15}[，,]我来为您整理[^,，]{1,15}[,，]",
                "^好的，我来为您整理[^,，]{1,15}[,，]",
                "^好的，信息都齐了！",
                "^根据[^，,]{1,15}，?(我|下面|现在|接下来)[^，,]{1,15}[,，]",
                "^好的，?(我先?|下面|现在|接下来|首先)[^，,]{1,15}[,，]",
        };
        for (String pattern : leadPatterns) {
            if (response.matches("(?s)" + pattern + ".*")) {
                String cleaned = response.replaceFirst("(?s)" + pattern, "");
                if (cleaned.length() >= 30) {
                    log.info("[AgentCaller] 清理引导语前缀，长度: {} -> {}", response.length(), cleaned.length());
                    return cleaned;
                }
                log.debug("[AgentCaller] 引导语清理后过短({}字)，跳过", cleaned.length());
            }
        }

        return response;
    }

    /**
     * 将 requestId 注入到 instruction 前缀中，供下游 Agent 提取并设入 MDC。
     */
    private String enrichWithRequestId(String instruction, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return instruction;
        }
        return "[requestId:" + requestId + "]\n" + instruction;
    }
}
