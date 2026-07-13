/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Image processing toolset — image analysis and text-to-image generation.
 * <p>Based on Alibaba Cloud DashScope API (Qwen-VL multimodal + Tongyi Wanxiang).</p>
 */
@Component
public class ImageTools {

    private static final Logger log = LoggerFactory.getLogger(ImageTools.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String dashscopeApiKey;

    private static final String VL_MODEL = "qwen-vl-max";
    private static final String WANX_MODEL = "wanx-image-generation-v1";

    private static final String DASHSCOPE_CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String WANX_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String DASHSCOPE_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    public ImageTools(@Value("${spring.ai.dashscope.api-key:}") String dashscopeApiKey) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.dashscopeApiKey = dashscopeApiKey;
    }

    @Tool(description = "分析图片内容，根据用户的问题回答图片中的信息。支持图片URL和base64数据URI。"
            + "当用户发送图片或询问图片内容时调用此工具")
    public String analyzeImage(
            @ToolParam(description = "图片的URL地址（支持 http/https 或 base64 data URI）", required = true) String imageUrl,
            @ToolParam(description = "关于图片的问题，如'这张图片里有什么？''图中是什么景点？'等。默认为'请详细描述这张图片'") String question) {
        log.info("[ImageTools] 图片解读: imageUrl={}, question={}",
                imageUrl != null ? imageUrl.substring(0, Math.min(50, imageUrl.length())) + "..." : null,
                question);

        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            return ToolResult.error(AgentErrorCode.TOOL_INVALID_ARGUMENT,
                    "图片处理功能不可用：DashScope API Key 未配置",
                    "请在 .env 文件中设置 DASHSCOPE_API_KEY 后重试");
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", VL_MODEL);

            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");

            ArrayNode content = userMsg.putArray("content");

            String text = (question != null && !question.isBlank()) ? question : "请详细描述这张图片";
            content.add(objectMapper.createObjectNode()
                    .put("type", "text")
                    .put("text", text));

            ObjectNode imageNode = objectMapper.createObjectNode();
            imageNode.put("type", "image_url");
            ObjectNode urlNode = imageNode.putObject("image_url");
            urlNode.put("url", imageUrl);
            content.add(imageNode);

            requestBody.putObject("parameters")
                    .put("temperature", 0.5)
                    .put("max_tokens", 1024);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DASHSCOPE_CHAT_URL))
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[ImageTools] API 调用失败: status={}, body={}", response.statusCode(), response.body());
                return ToolResult.error(AgentErrorCode.VALIDATION_IMAGE_ANALYSIS, "图片分析失败", "请稍后重试");
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                String result = choices.get(0).at("/message/content").asText("");
                if (!result.isBlank()) {
                    log.info("[ImageTools] 图片解读完成, 结果长度={}", result.length());
                    return result;
                }
            }

            log.warn("[ImageTools] 无法解析API响应: {}", response.body());
            return ToolResult.error(AgentErrorCode.VALIDATION_IMAGE_ANALYSIS, "图片分析失败：无法解析API响应");

        } catch (Exception e) {
            log.error("[ImageTools] 图片解读异常: {}", e.getMessage(), e);
            return ToolResult.error(AgentErrorCode.VALIDATION_IMAGE_ANALYSIS, "图片分析异常", "请检查图片URL是否有效");
        }
    }

    @Tool(description = "根据文字描述生成图片。"
            + "当用户说'画一张...''生成一张...的图片''帮我画...'等时调用此工具")
    public String generateImage(
            @ToolParam(description = "图片描述文字，如'夕阳下的西湖，水墨风格'，越详细效果越好", required = true) String prompt,
            @ToolParam(description = "图片尺寸：1024*1024(方形)、1024*576(横版)、576*1024(竖版)", required = true) ImageSize size,
            @ToolParam(description = "生成数量，默认为1，最大4", required = true) Integer n) {
        log.info("[ImageTools] 文生图: prompt={}, size={}, n={}", prompt, size, n);

        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            return ToolResult.error(AgentErrorCode.TOOL_INVALID_ARGUMENT,
                    "图片处理功能不可用：DashScope API Key 未配置",
                    "请在 .env 文件中设置 DASHSCOPE_API_KEY 后重试");
        }

        if (size == null) size = ImageSize.SQUARE;
        if (n == null || n < 1) n = 1;
        if (n > 4) n = 4;

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", WANX_MODEL);

            ObjectNode input = requestBody.putObject("input");
            input.put("prompt", prompt);

            ObjectNode parameters = requestBody.putObject("parameters");
            parameters.put("size", size.getValue());
            parameters.put("n", n);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest createRequest = HttpRequest.newBuilder()
                    .uri(URI.create(WANX_TASK_URL))
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> createResponse = httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString());

            if (createResponse.statusCode() != 200) {
                log.warn("[ImageTools] 创建任务失败: status={}, body={}",
                        createResponse.statusCode(), createResponse.body());
                return ToolResult.error(AgentErrorCode.TOOL_IMAGE_GENERATION_FAILED, "图片生成失败", "请稍后重试");
            }

            JsonNode createRoot = objectMapper.readTree(createResponse.body());
            JsonNode output = createRoot.get("output");
            if (output == null) {
                return ToolResult.error(AgentErrorCode.TOOL_IMAGE_GENERATION_FAILED, "图片生成失败：无法创建任务");
            }

            String taskId = output.get("task_id").asText();
            String taskStatus = output.get("task_status").asText();
            log.info("[ImageTools] 任务已创建: taskId={}, status={}", taskId, taskStatus);

            String resultUrl = pollTaskResult(taskId);
            if (resultUrl != null) {
                log.info("[ImageTools] 图片生成完成: url={}", resultUrl);
                return "图片已生成！\n![生成的图片](" + resultUrl + ")\n\n"
                        + "描述：" + prompt + "\n"
                        + "尺寸：" + size;
            }

            return ToolResult.error(AgentErrorCode.TOOL_IMAGE_GENERATION_TIMEOUT, "图片生成超时", "请稍后重试");

        } catch (Exception e) {
            log.error("[ImageTools] 文生图异常: {}", e.getMessage(), e);
            return ToolResult.error(AgentErrorCode.TOOL_IMAGE_GENERATION_FAILED, "图片生成异常", "请稍后重试");
        }
    }

    private String pollTaskResult(String taskId) throws Exception {
        long start = System.currentTimeMillis();
        long timeout = 60 * 1000L;
        int pollInterval = 2000;

        while (System.currentTimeMillis() - start < timeout) {
            Thread.sleep(pollInterval);

            HttpRequest pollRequest = HttpRequest.newBuilder()
                    .uri(URI.create(DASHSCOPE_TASK_URL + taskId))
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> pollResponse = httpClient.send(pollRequest, HttpResponse.BodyHandlers.ofString());

            if (pollResponse.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(pollResponse.body());
                JsonNode output = root.get("output");
                if (output != null) {
                    String status = output.get("task_status").asText();
                    log.info("[ImageTools] 任务状态: {} (已等待 {}ms)", status, System.currentTimeMillis() - start);

                    if ("SUCCEEDED".equals(status)) {
                        JsonNode results = output.get("results");
                        if (results != null && results.isArray() && !results.isEmpty()) {
                            return results.get(0).get("url").asText();
                        }
                    } else if ("FAILED".equals(status)) {
                        String message = output.has("message") ? output.get("message").asText() : "未知错误";
                        log.warn("[ImageTools] 任务失败: {}", message);
                        return null;
                    }
                }
            }
        }

        return null;
    }
}
