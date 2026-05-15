/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.travel.config;

import com.example.smartassistant.common.config.DeepSeekApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component("deepSeekChatModel")
public class CustomDeepSeekChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CustomDeepSeekChatModel.class);
    private final DeepSeekApiClient apiClient;
    private final double temperature;
    private final int maxTokens;
    private final com.example.smartassistant.service.rag.TravelNoteService travelNoteService;

    public CustomDeepSeekChatModel(
            @Value("${spring.ai.deepseek.api-key}") String apiKey,
            @Value("${spring.ai.deepseek.chat.options.temperature:0.5}") double temperature,
            @Value("${spring.ai.deepseek.chat.options.max-tokens:4096}") int maxTokens,
            com.example.smartassistant.service.rag.TravelNoteService travelNoteService) {
        this.apiClient = new DeepSeekApiClient(apiKey);
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.travelNoteService = travelNoteService;
        log.info("[CustomDeepSeek] initialized (thinking_mode=disabled, temperature={}, maxTokens={})", temperature, maxTokens);
    }

    @Override public ChatResponse call(Prompt prompt) {
        try {
            // ⭐ 从同机 REST API 获取真实游记标题
            java.util.Map<String, String> realTitles = fetchTitlesFromRest(prompt);

            String requestJson = apiClient.buildRequestJson("deepseek-v4-flash", temperature, maxTokens, buildMessages(prompt));
            String apiResponse = apiClient.sendRequest(requestJson);
            ChatResponse chatResponse = parseResponse(apiResponse);

            // ⭐ 后处理：注入真实引用 + 剥离编造引用
            chatResponse = injectCitations(chatResponse, realTitles);
            chatResponse = stripFakeCitations(chatResponse, realTitles);
            return chatResponse;
        } catch (Exception e) { throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e); }
    }
    @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }

    private ArrayNode buildMessages(Prompt prompt) {
        var om = apiClient.getObjectMapper();
        var msgs = om.createArrayNode();
        for (var inst : prompt.getInstructions()) {
            var msg = msgs.addObject();
            if (inst.getMessageType() == MessageType.USER) msg.put("role", "user").put("content", inst.getText());
            else if (inst.getMessageType() == MessageType.SYSTEM) msg.put("role", "system").put("content", inst.getText());
            else if (inst.getMessageType() == MessageType.ASSISTANT) {
                AssistantMessage am = (AssistantMessage) inst;
                msg.put("role", "assistant").put("content", am.getText() != null ? am.getText() : "");
                if (!am.getToolCalls().isEmpty()) {
                    var tools = msg.putArray("tool_calls");
                    for (var tc : am.getToolCalls()) {
                        var t = tools.addObject();
                        t.put("id", tc.id()).put("type", "function");
                        t.putObject("function").put("name", tc.name()).put("arguments", tc.arguments());
                    }
                }
            } else if (inst.getMessageType() == MessageType.TOOL) {
                for (var r : ((ToolResponseMessage) inst).getResponses())
                    msgs.addObject().put("role", "tool").put("tool_call_id", r.id()).put("content", r.responseData());
            }
        }
        return msgs;
    }

    private ChatResponse parseResponse(String body) throws Exception {
        JsonNode root = apiClient.parseResponse(body);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) return new ChatResponse(List.of());
        List<Generation> gens = new ArrayList<>();
        for (JsonNode c : choices) {
            JsonNode m = c.get("message");
            if (m == null) continue;
            String content = m.has("content") && !m.get("content").isNull() ? m.get("content").asText() : "";
            List<AssistantMessage.ToolCall> tcs = new ArrayList<>();
            JsonNode tcN = m.get("tool_calls");
            if (tcN != null && tcN.isArray()) for (JsonNode tc : tcN)
                tcs.add(new AssistantMessage.ToolCall(tc.get("id").asText(), tc.get("type").asText(),
                        tc.at("/function/name").asText(), tc.at("/function/arguments").asText()));
            var b = AssistantMessage.builder().content(content);
            if (!tcs.isEmpty()) b.toolCalls(tcs);
            gens.add(new Generation(b.build()));
        }
        return new ChatResponse(gens);
    }
    /**
     * 从同机 REST API 获取指定地点的真实游记标题。
     */
    private java.util.Map<String, String> fetchTitlesFromRest(Prompt prompt) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        try {
            String location = extractLocationFromPrompt(prompt);
            if (location == null || travelNoteService == null) return map;
            travelNoteService.searchByLocation(location)
                    .forEach(n -> { if (n.getTitle() != null) map.put(n.getTitle(), n.getTags() != null ? n.getTags() : ""); });
            if (!map.isEmpty()) log.info("[CustomDeepSeek] 获取到 {} 个真实游记标题: {}", map.size(), map.keySet());
        } catch (Exception e) {
            log.warn("[CustomDeepSeek] 获取标题失败: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 从 Prompt 的用户消息中提取地点关键词。
     */
    private String extractLocationFromPrompt(Prompt prompt) {
        String[] cities = {"北京","上海","杭州","成都","广州","深圳","苏州","西安","重庆","厦门","长沙","南京","武汉","哈尔滨","昆明","大理","丽江","三亚","青岛","大连","桂林","张家界"};
        for (var inst : prompt.getInstructions()) {
            if (inst.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
                String text = inst.getText();
                if (text != null) for (String c : cities) { if (text.contains(c)) return c; }
            }
        }
        return null;
    }

    /**
     * ⭐ 在 LLM 最终回复中注入 [根据真实标题] 引用。
     * 基于 tag 关键词匹配段落：在包含 tag 关键词且尚未有引用的段落末尾追加引用。
     */
    private ChatResponse injectCitations(ChatResponse response, java.util.Map<String, String> titles) {
        var gens = response.getResults();
        if (gens.isEmpty()) return response;

        var newGens = new ArrayList<Generation>();
        for (Generation gen : gens) {
            String text = gen.getOutput().getText();
            if (text == null || text.isBlank()) { newGens.add(gen); continue; }

            for (var entry : titles.entrySet()) {
                String title = entry.getKey();
                String tags = entry.getValue();
                String citation = "[根据" + title + "]";
                if (text.contains(citation)) continue;

                String[] keywords = tags != null ? tags.split("[,，]") : new String[0];
                if (keywords.length == 0) {
                    keywords = new String[]{title.length() > 8 ? title.substring(0, 8) : title};
                }

                StringBuilder sb = new StringBuilder();
                String[] paragraphs = text.split("\n");
                boolean injected = false;
                for (String para : paragraphs) {
                    sb.append(para).append("\n");
                    if (injected) continue;
                    String trimmed = para.trim();
                    if (trimmed.isEmpty() || trimmed.contains("根据[") || trimmed.contains("根据《")) continue;
                    for (String kw : keywords) {
                        kw = kw.trim();
                        if (kw.length() < 2) continue;
                        if (trimmed.contains(kw)) {
                            sb.append(citation).append("\n");
                            injected = true;
                            break;
                        }
                    }
                }
                text = sb.toString();
            }

            var b = AssistantMessage.builder().content(text);
            newGens.add(new Generation(b.build()));
        }

        log.info("[CustomDeepSeek] 完成引用注入，共处理 {} 个标题", titles.size());
        return new ChatResponse(newGens);
    }

    /**
     * ⭐ 剥离响应中不存在于真实标题列表的 [...]、《...》、【...】引用。
     */
    private ChatResponse stripFakeCitations(ChatResponse response, java.util.Map<String, String> realTitles) {
        if (realTitles == null || realTitles.isEmpty()) return response;
        var gens = response.getResults();
        if (gens.isEmpty()) return response;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[([^]]{2,80})]|《([^》]{2,80})》|【([^】]{2,80})】");
        java.util.Set<String> realSet = realTitles.keySet();

        var newGens = new java.util.ArrayList<Generation>();
        for (Generation gen : gens) {
            String text = gen.getOutput().getText();
            if (text == null || text.isBlank()) { newGens.add(gen); continue; }
            java.util.regex.Matcher m = p.matcher(text);
            StringBuilder sb = new StringBuilder();
            int removed = 0;
            while (m.find()) {
                String inner = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
                boolean valid = realSet.stream().anyMatch(rt -> rt.equals(inner) || rt.contains(inner) || inner.contains(rt));
                if (valid) {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
                } else {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(inner));
                    removed++;
                }
            }
            m.appendTail(sb);
            if (removed > 0) log.info("[CustomDeepSeek] 已剥离 {} 个编造引用", removed);
            var b = AssistantMessage.builder().content(sb.toString());
            newGens.add(new Generation(b.build()));
        }
        return new ChatResponse(newGens);
    }

    @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() { return null; }
}
