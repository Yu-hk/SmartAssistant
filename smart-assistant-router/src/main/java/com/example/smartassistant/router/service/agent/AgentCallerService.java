/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardWrapper;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.extraction.KeywordExtractionService;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent Caller Service - 调用 Provider Agent
 * 优化：使用关键词提取构建精简 instruction，减少 token 消耗
 */
@Service
public class AgentCallerService {
    
    private static final Logger log = LoggerFactory.getLogger(AgentCallerService.class);
    
    private final AgentDiscoveryService agentDiscoveryService;
    private final KeywordExtractionService keywordExtractionService;
    
    public AgentCallerService(AgentDiscoveryService agentDiscoveryService,
                             KeywordExtractionService keywordExtractionService) {
        this.agentDiscoveryService = agentDiscoveryService;
        this.keywordExtractionService = keywordExtractionService;
    }
    
    /**
     * 调用目标 Agent
     * 
     * @param agentName Agent 名称
     * @param question 原始问题（不包含任何标记）
     * @param userId 用户 ID
     * @return Agent 响应结果
     */
    public String callAgent(String agentName, String question, Long userId) {
        return callAgentWithContext(agentName, question, userId, null);
    }
    
    /**
     * 调用目标 Agent（带提取的上下文信息）
     * 优化：使用关键词提取构建精简 instruction，减少 token 消耗
     * 
     * @param agentName Agent 名称
     * @param question 原始问题
     * @param userId 用户 ID
     * @param context 提取的上下文信息（地点、意图等）
     * @return Agent 响应结果
     */
    public String callAgentWithContext(String agentName, String question, Long userId, 
                                      RouteDecision.ExtractedContext context) {
        log.info("[AgentCaller] 调用 Agent: {}, userId={}, questionLength={}", 
                agentName, userId, question != null ? question.length() : 0);
        
        if (context != null) {
            log.info("[AgentCaller] 提取的上下文: location={}, intent={}", 
                    context.getLocation(), context.getIntent());
        }
        
        try {
            // 根据 Agent 名称和上下文构建 Instruction（使用关键词提取优化）
            String instruction = buildOptimizedInstruction(question, context);
            
            log.debug("[AgentCaller] 优化后的 instruction: {}", instruction);
            
            // ⭐ 从 AgentDiscoveryService 获取目标 Agent 的实际 URL
            String agentUrl = findAgentUrl(agentName);
            if (agentUrl == null) {
                log.error("[AgentCaller] 未找到 Agent: {}", agentName);
                return "❌ 未找到目标 Agent: " + agentName;
            }
            
            log.info("[AgentCaller] 目标 Agent URL: {}", agentUrl);
            
            // ⭐ 先创建针对当前 agentName 的 AgentCardProvider
            AgentCardProvider specificProvider = new SpecificAgentCardProvider(agentDiscoveryService, agentName);
            
            // ⭐ 使用特定的 Provider 构建远程 Agent
            // 注意：传递原始问题作为 instruction，让远程 Agent 正确处理
            A2aRemoteAgent remoteAgent = A2aRemoteAgent.builder()
                    .name(agentName)
                    .description("远程 Agent: " + agentName)
                    .agentCardProvider(specificProvider)  // ⭐ 使用预先确定的 Provider
                    .instruction(question)  // ⭐ 传递原始问题，避免空 instruction 错误
                    .shareState(false)
                    .build();
            
            // 调用 Agent - 直接传递原始问题
            Optional<OverAllState> result = remoteAgent.invoke(question);
            
            if (result.isPresent()) {
                // ⭐ 调试：打印完整的返回数据结构
                log.info("[AgentCaller] Agent 返回的完整 data keys: {}", result.get().data().keySet());
                
                Object output = result.get().data().get("output");
                if (output != null) {
                    // ⭐ 优先从 AssistantMessage 提取 textContent，避免输出原始对象 toString()
                    String response;
                    if (output instanceof AssistantMessage assistantOutput) {
                        String text = assistantOutput.getText();
                        // ⭐ 诊断日志：看 getText() 到底返回了什么
                        log.info("[AgentCaller] output 是 AssistantMessage, class={}, getText()={}, toString().length={}",
                                output.getClass().getName(), text != null ? text.substring(0, Math.min(100, text.length())) : "null", output.toString().length());
                        response = (text != null && !text.isEmpty()) ? text : assistantOutput.toString();
                        log.info("[AgentCaller] output 是 AssistantMessage，提取 textContent 成功, length={}", response.length());
                    } else if (output instanceof String strOutput) {
                        // ⭐ output 是 String：检查是否包含 DeepSeekAssistantMessage 格式串（从 AssistantMessage.toString() 序列化而来）
                        log.info("[AgentCaller] output 是 String, 长度={}, 前100字符={}", strOutput.length(), strOutput.substring(0, Math.min(100, strOutput.length())));
                        
                        // 判断是否是 DeepSeekAssistantMessage 格式
                        if (strOutput.startsWith("DeepSeekAssistantMessage [") || strOutput.startsWith("AssistantMessage [")) {
                            // 从 toString() 格式中提取 textContent=xxx
                            if (strOutput.contains("textContent=")) {
                                int startIdx = strOutput.indexOf("textContent=") + "textContent=".length();
                                // ⚠️ textContent 中可能包含中文逗号，不能用 indexOf(",")
                                // 找 textContent 结束位置：下一个 ", fieldName=" 或 "]"
                                int endIdx = findEndOfTextContent(strOutput, startIdx);
                                if (endIdx > startIdx) {
                                    response = strOutput.substring(startIdx, endIdx);
                                    log.info("[AgentCaller] ✅ 从 AssistantMessage.toString() 中提取 textContent 成功, length={}", response.length());
                                } else {
                                    response = strOutput;
                                }
                            } else {
                                response = strOutput;
                            }
                        } else {
                            // 普通字符串，直接返回
                            response = strOutput;
                            log.info("[AgentCaller] output 是普通 String, length={}", response.length());
                        }
                    } else {
                        log.info("[AgentCaller] output 类型未知, class={}, toString()={}", output.getClass().getName(), output.toString().substring(0, Math.min(200, output.toString().length())));
                        response = output.toString();
                    }
                    
                    // ⭐ 清理思考过程内容（DeepSeek R1 等模型会在回复开头输出推理步骤如 "1. 好的，信息都齐了！"）
                    response = cleanThinkingContent(response);
                    
                    // ⭐ 检查是否是 A2A 框架的错误消息
                    if ("No output key in result.".equals(response)) {
                        log.warn("[AgentCaller] 检测到 A2A 框架错误消息，尝试从 messages 中提取结果");
                        
                        // 尝试从 messages 中提取 AssistantMessage
                        Object messages = result.get().data().get("messages");
                        if (messages instanceof List<?> messageList) {
                            log.info("[AgentCaller] messages 列表大小: {}, 所有消息类型: {}",
                                    messageList.size(), 
                                    messageList.stream().map(m -> m.getClass().getSimpleName()).toList());
                            
                            // 查找 AssistantMessage（而不是最后一条）
                            for (int i = messageList.size() - 1; i >= 0; i--) {
                                Object msg = messageList.get(i);
                                String msgType = msg.getClass().getName();
                                if (msgType.contains("Assistant") || msgType.contains("AIMessage")) {
                                    log.info("[AgentCaller] ✅ 找到 AssistantMessage at index {}, type={}", i, msgType);
                                    // 提取 textContent
                                    if (msg instanceof AssistantMessage assistantMsg) {
                                        String text = assistantMsg.getText();
                                        if (text != null && !text.isEmpty()) {
                                            log.info("[AgentCaller] 提取 textContent 成功, length={}", text.length());
                                            return text;
                                        }
                                    }
                                    // Fallback: 尝试从 toString() 中提取 textContent
                                    String msgStr = msg.toString();
                                    if (msgStr.contains("textContent=")) {
                                        int startIdx = msgStr.indexOf("textContent=") + "textContent=".length();
                                        int endIdx = msgStr.lastIndexOf(", metadata=");
                                        if (endIdx > startIdx) {
                                            return msgStr.substring(startIdx, endIdx);
                                        } else {
                                            return msgStr.substring(startIdx);
                                        }
                                    }
                                    return msgStr;
                                }
                            }
                            
                            log.warn("[AgentCaller] messages 中没有 AssistantMessage");
                        }
                        
                        // 尝试从其他字段获取
                        log.warn("[AgentCaller] 尝试从其他字段提取结果...");
                        result.get().data().forEach((key, value) -> {
                            if (!"output".equals(key) && !"input".equals(key) && !"messages".equals(key)) {
                                log.info("[AgentCaller]   Field '{}': type={}, value={}", 
                                        key, value != null ? value.getClass().getSimpleName() : "null",
                                        value != null && value.toString().length() < 200 ? value : "(too long)");
                            }
                        });
                        
                        return "⚠️ Agent 执行成功但未返回有效结果（WeatherTool 已执行，请查看 Travel Service 日志）";
                    }
                    
                    log.info("[AgentCaller] Agent 调用成功: {}, 响应长度={}", 
                            agentName, response.length());
                    return response;
                } else {
                    log.warn("[AgentCaller] Agent 返回数据中没有 'output' 键，可用键: {}", 
                            result.get().data().keySet());
                    // ⭐ 临时方案：如果没有 output 键，尝试返回 input 或其他字段
                    Object input = result.get().data().get("input");
                    if (input != null) {
                        log.warn("[AgentCaller] 降级：返回 input 字段");
                        return input.toString();
                    }
                    // 返回所有数据的字符串表示
                    log.warn("[AgentCaller] 降级：返回完整 data 的 toString()");
                    return result.get().data().toString();
                }
            } else {
                log.warn("[AgentCaller] Agent 返回结果为空 (Optional.empty)");
            }
            
            return "⚠️ Agent 返回空结果";
            
        } catch (Exception e) {
            log.error("[AgentCaller] Agent 调用失败: {}, 错误: {}", agentName, e.getMessage(), e);
            return "❌ 调用 Agent 失败: " + e.getMessage();
        }
    }
    
    /**
     * 构建优化的 Instruction（使用关键词提取）
     * 策略：
     * 1. 检测是否包含【用户画像】+【当前问题】结构
     * 2. 如果包含，完整保留用户画像，仅精简问题部分
     * 3. 如果不包含，直接对整个问题进行关键词提取
     * 4. 确保 instruction 尽可能简短，减少 token 消耗
     * 
     * @param questionOrPrompt 原始问题或完整 Prompt（可能包含用户画像）
     * @param context 提取的上下文信息（可选）
     * @return 精简的 Instruction 文本
     */
    private String buildOptimizedInstruction(String questionOrPrompt,
                                             RouteDecision.ExtractedContext context) {
        // Step 1: 检测是否为完整 Prompt（包含用户画像）
        if (questionOrPrompt != null && questionOrPrompt.contains("【当前问题】")) {
            log.debug("[AgentCaller] 检测到完整 Prompt 结构，使用分段优化");
            return keywordExtractionService.extractKeywordsFromFullPrompt(questionOrPrompt);
        }
        
        // Step 2: 普通问题，直接进行关键词提取
        String keywordInstruction = keywordExtractionService.extractKeywordsAsInstruction(questionOrPrompt);
        
        if (keywordInstruction != null && !keywordInstruction.isEmpty()) {
            log.debug("[AgentCaller] 使用关键词提取的 instruction: {}", keywordInstruction);
            return keywordInstruction;
        }
        
        // Step 3: 回退到基于 context 的构建方式
        if (context != null) {
            String contextInstruction = buildInstructionFromContext(context);
            if (!contextInstruction.isEmpty()) {
                log.debug("[AgentCaller] 使用 context 构建的 instruction: {}", contextInstruction);
                return contextInstruction;
            }
        }
        
        // Step 4: 都没有，返回空字符串（让 Agent 使用默认 System Prompt）
        log.debug("[AgentCaller] 未提取到关键词，使用空 instruction");
        return "";
    }
    
    /**
     * 从 context 构建 instruction（原有逻辑，作为降级方案）
     */
    private String buildInstructionFromContext(RouteDecision.ExtractedContext context) {
        if (context == null) {
            return "";
        }
        
        StringBuilder instruction = new StringBuilder();
        boolean hasContent = false;
        
        // 添加地点信息
        if (context.getLocation() != null && !context.getLocation().isEmpty()) {
            instruction.append("地点: ").append(context.getLocation());
            hasContent = true;
        }
        
        // 添加意图信息
        if (context.getIntent() != null && !context.getIntent().isEmpty()) {
            if (hasContent) instruction.append("; ");
            instruction.append("意图: ").append(context.getIntent());
            hasContent = true;
        }
        
        // 添加时间范围
        if (context.getTimeRange() != null && !context.getTimeRange().isEmpty()) {
            if (hasContent) instruction.append("; ");
            instruction.append("时间: ").append(context.getTimeRange());
            hasContent = true;
        }
        
        // 添加其他参数
        if (context.getAdditionalParams() != null && !context.getAdditionalParams().isEmpty()) {
            if (hasContent) instruction.append("; ");
            instruction.append("其他: ").append(context.getAdditionalParams());
        }
        
        return hasContent ? instruction.toString() : "";
    }
    
    /**
     * ⭐ 从 AgentDiscoveryService 查找目标 Agent 的 URL
     */
    private String findAgentUrl(String agentName) {
        try {
            List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
            
            for (DiscoveredAgent agent : agents) {
                // 匹配 agentName 或 serviceName
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

    /**
     * ⭐ 清理回复文本中的思考过程内容
     * <p>
     * DeepSeek R1 等推理模型会在回复开头输出结构化的推理步骤，
     * 形如："1. 好的，信息都齐了！..." 或 "思考内容：..."，
     * 这些内容对用户是噪音，需要清理后才返回。
     *
     * @param response 原始回复文本
     * @return 清理后的纯净回复
     */
    private String cleanThinkingContent(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        String original = response;
        
        // 策略1：检测并移除开头的推理步骤编号（如 "1. 好的，信息都齐了！下面..."）
        // 匹配模式：开头是 "1." + 简短句子 + "下面/现在/接下来" + 正式回复
        if (response.matches("(?s)^\\s*\\d+[．.、].{2,30}(?:下面|现在|接下来|先|然后|最后|所以|因此|好的|好的，信息|好的，信息都)\\.{0,5}.{10,}")) {
            // 找到第一个标题级句子结束的位置（通常在 "下面" / "现在" 之后）
            int cutoffIdx = -1;
            // 寻找 "1. xxx 下面" 之后的正文开始位置
            for (String sep : new String[]{"下面", "现在", "接下来", "好的，信息都齐了"}) {
                int idx = response.indexOf(sep);
                if (idx > 0 && idx < 100) {
                    cutoffIdx = idx + sep.length();
                    break;
                }
            }
            if (cutoffIdx > 0) {
                String cleaned = response.substring(cutoffIdx).trim();
                // 去掉可能的换行前缀
                if (cleaned.startsWith("：") || cleaned.startsWith(":")) {
                    cleaned = cleaned.substring(1).trim();
                }
                if (!cleaned.isEmpty()) {
                    log.info("[AgentCaller] 清理思考步骤（前缀），长度: {} -> {}", response.length(), cleaned.length());
                    return cleaned;
                }
            }
        }

        // 策略2：移除 [ModelThinking]...[/ModelThinking] 区块（如果还未被框架剥离）
        if (response.contains("[ModelThinking]")) {
            response = response.replaceAll("(?s)\\[ModelThinking].*?\\[/ModelThinking]", "");
            log.info("[AgentCaller] 清理 [ModelThinking] 区块，长度: {} -> {}", original.length(), response.length());
        }

        // 策略3：移除 [思考内容] 区块
        if (response.contains("[思考内容]")) {
            response = response.replaceAll("(?s)\\[思考内容].*?\\[/思考内容]", "");
            log.info("[AgentCaller] 清理 [思考内容] 区块，长度: {} -> {}", original.length(), response.length());
        }

        // 策略4：移除开头的引导语/回应语（DeepSeek R1 等推理模型常见的回复前缀）
        // 匹配：太好了！根据...我来为您整理...
        //      好的，我来为...整理...
        //      好的，信息都齐了！下面/现在/接下来...
        // ⚠️ [^，,]+ 限制最多15字，避免贪婪匹配吞掉整个主句
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
                // ⚠️ 清理后若少于30字说明误吞了正文，回退原文
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
     * ⭐ 从 DeepSeekAssistantMessage.toString() 中准确定位 textContent 结束位置
     * textContent 中可能包含中文逗号，不能用 indexOf(",") 查找结束
     * 正确做法：找下一个 ", fieldName="（ASCII字段名）或字符串结尾
     */
    private int findEndOfTextContent(String str, int startIdx) {
        int pos = startIdx;
        while (true) {
            int commaPos = str.indexOf(", ", pos);
            if (commaPos < 0) {
                // 没找到逗号，返回末尾
                String trimmed = str.trim();
                return trimmed.endsWith("]") ? trimmed.length() - 1 : str.length();
            }
            // 检查逗号后是否紧跟 ASCII 字段名（如 metadata=、reasoningContent=、prefix=）
            int eqPos = str.indexOf("=", commaPos + 2);
            if (eqPos > commaPos + 2) {
                String between = str.substring(commaPos + 2, eqPos);
                if (between.matches("[a-zA-Z]+")) {
                    return commaPos; // 找到了下一个字段，textContent 到此结束
                }
            }
            pos = commaPos + 2;
        }
    }

    /**
     * ⭐ 特定的 AgentCardProvider - 在构造时就确定 agentName
     */
    static class SpecificAgentCardProvider implements AgentCardProvider {
        
        private final AgentDiscoveryService agentDiscoveryService;
        private final String targetAgentName;
        
        public SpecificAgentCardProvider(AgentDiscoveryService agentDiscoveryService, String agentName) {
            this.agentDiscoveryService = agentDiscoveryService;
            this.targetAgentName = agentName;
            log.info("[SpecificAgentCardProvider] ✅ 初始化, targetAgent={}", agentName);
        }
        
        @Override
        public AgentCardWrapper getAgentCard() {
            log.info("[SpecificAgentCardProvider] ✅ 获取 AgentCard for: {}", targetAgentName);
            // ⭐ 根据预先确定的 agentName 查找对应的 Agent
            List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
            for (DiscoveredAgent agent : agents) {
                if (agent.getAgentName().equals(targetAgentName) || 
                    agent.getServiceName().equals(targetAgentName)) {
                    log.info("[SpecificAgentCardProvider] ✅ 找到 Agent: targetName={}, serviceName={}, agentType={}, URL: {}", 
                            targetAgentName, agent.getServiceName(), agent.getAgentName(), agent.getUrl());
                    return createAgentCardWrapper(agent);
                }
            }
            log.error("[SpecificAgentCardProvider] ❌ 未找到 Agent: {}", targetAgentName);
            throw new IllegalStateException("Agent not found: " + targetAgentName);
        }
        
        @Override
        public AgentCardWrapper getAgentCard(String agentName) {
            // 忽略传入的 agentName,使用构造时确定的
            return getAgentCard();
        }
        
        private AgentCardWrapper createAgentCardWrapper(DiscoveredAgent agent) {
            // 构建 AgentSkill 列表
            List<AgentSkill> skills = List.of(
                new AgentSkill(
                    agent.getAgentName(),  // ⭐ 使用 agentName 而不是 agentType
                    agent.getAgentName(),
                    "Agent skill",
                    List.of(),
                    List.of(),
                    List.of("text"),
                    List.of("text")
                )
            );
            
            // 构建 AgentCapabilities
            AgentCapabilities capabilities = new AgentCapabilities(
                false,  // streaming
                false,  // pushNotifications
                false,  // stateTransitionHistory
                List.of()
            );
            
            // 构建 AgentCard
            AgentCard agentCard = new AgentCard(
                agent.getAgentName(),
                "Remote Agent: " + agent.getAgentName(),
                agent.getUrl(),  // ⭐ 使用发现的实际 URL
                null,
                "1.0.0",
                null,
                capabilities,
                List.of("text"),
                List.of("text"),
                skills,
                false,
                Map.of(),
                List.of(),
                null,
                List.of(),
                null,
                "v1"
            );
            
            return new AgentCardWrapper(agentCard);
        }
    }
}
