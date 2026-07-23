/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 基于本地 Ollama 多模态视觉模型的图片语义说明策略（零额外依赖，使用 JDK {@link HttpClient}）。
 * <p>
 * 与 {@link OllamaVisionOcrStrategy} 共用底层视觉模型，但职责不同：本策略让模型「描述图片内容」
 * （图表含义、实体、布局、截图信息），而非仅抽取图内文字。默认端点 {@code http://localhost:11434}，
 * 默认模型 {@code llava}。
 * </p>
 *
 * <p>优雅降级：{@link #isAvailable()} 仅做轻量健康探测（GET {@code /api/tags}，2s 超时）；
 * 当 Ollama 不可用或返回空时 {@link #caption} 返回 null，绝不抛异常阻断解析。</p>
 */
public class OllamaVisionCaptionStrategy implements ImageCaptionStrategy {

    private static final Logger log = LoggerFactory.getLogger(OllamaVisionCaptionStrategy.class);

    private static final String DEFAULT_BASE = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "llava";
    private static final String DEFAULT_PROMPT =
            "请详细描述这张图片的内容：包括其中的图表、数据、实体、布局及其含义，"
                    + "如果是截图请说明其展示的功能或信息，用简洁的中文回答，不要解释你的推理过程。";

    private final String base;
    private final String model;
    private final String prompt;
    private final HttpClient http;

    public OllamaVisionCaptionStrategy() {
        this(DEFAULT_BASE, DEFAULT_MODEL, DEFAULT_PROMPT);
    }

    public OllamaVisionCaptionStrategy(String base, String model, String prompt) {
        this.base = (base != null && !base.isBlank()) ? base.strip().replaceAll("/+$", "") : DEFAULT_BASE;
        this.model = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
        this.prompt = (prompt != null && !prompt.isBlank()) ? prompt : DEFAULT_PROMPT;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public String engineName() {
        return "ollama";
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Override
    public String caption(byte[] imageData, String fileName) {
        if (imageData == null || imageData.length == 0) {
            return null;
        }
        try {
            String b64 = Base64.getEncoder().encodeToString(imageData);
            String body = "{\"model\":\"" + model + "\",\"prompt\":\""
                    + escapeJson(prompt) + "\",\"images\":[\"" + b64 + "\"],\"stream\":true}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/generate"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("[OllamaCaption] 说明生成失败: file={}, status={}", fileName, resp.statusCode());
                return null;
            }
            String text = extractResponse(resp.body());
            return text.isBlank() ? null : text.strip();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[OllamaCaption] 说明生成异常: file={}, error={}", fileName, e.getMessage());
            return null;
        }
    }

    /** Ollama 以 NDJSON 流式返回，每行一个 JSON 对象含 {@code response} 片段，拼接之 */
    private static String extractResponse(String ndjson) {
        if (ndjson == null || ndjson.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : ndjson.split("\n")) {
            if (line.isBlank()) continue;
            String piece = extractField(line, "response");
            if (piece != null) sb.append(piece);
        }
        return sb.toString();
    }

    /** 极简 NDJSON 字段提取（仅用于自有端点，非通用 JSON 解析） */
    private static String extractField(String line, String key) {
        String needle = "\"" + key + "\":";
        int idx = line.indexOf(needle);
        if (idx < 0) return null;
        int colon = idx + needle.length();
        // 跳过空白与可选引号
        int i = colon;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '"')) i++;
        if (i >= line.length()) return "";
        if (line.charAt(i) == '"') {
            int start = i + 1;
            int end = line.indexOf('"', start);
            // 处理转义：遇到 \" 时继续
            while (end > 0 && line.charAt(end - 1) == '\\') {
                end = line.indexOf('"', end + 1);
            }
            if (end < 0) end = line.length();
            return unescapeJson(line.substring(start, end));
        }
        // 非字符串值（如布尔/数字），取到行尾或逗号
        int end = line.indexOf(',', i);
        if (end < 0) end = line.length();
        return line.substring(i, end).strip();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
    }
}
