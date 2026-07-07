/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.Objects;

/**
 * 基于 Spring AI 多模态 {@link ChatModel} 的图片描述器（视觉理解）。
 * <p>
 * 将图片字节包装为 {@link Media} 通过 {@link ChatClient} 多模态接口发送给视觉模型，
 * 提取结构化描述文本。模型不可用时 {@link #isAvailable()} 返回 false，{@link #caption}
 * 直接返回空串，避免中断摄取链路。
 * </p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>异常隔离：任何视觉调用异常均捕获并返回空串，不向上抛；</li>
 *   <li>可测试：实际模型调用抽至 {@link #doCaption(String, Media)} 受保护方法，
 *       单测可用 Mockito spy 桩接，无需真实模型；</li>
 *   <li>依赖：仅需 Spring AI 2.0 的 {@code ChatModel}（视觉能力由具体模型提供，
 *       如 Ollama 的 llava / qwen2-vl），无新增 Maven 依赖。</li>
 * </ul>
 */
public class OllamaVisionImageCaptioner implements ImageCaptioner {

    private static final Logger log = LoggerFactory.getLogger(OllamaVisionImageCaptioner.class);

    private static final String DEFAULT_PROMPT = "请仔细阅读这张图片，提取其中可用于知识库的结构化信息："
            + "关键事实、数据、操作步骤、实体名称及其关系。"
            + "若图片包含表格或图表，请转述其核心内容。"
            + "只输出提取到的信息，不要添加解释性话语。";

    private final ChatModel chatModel;

    private final String prompt;

    private final ChatClient client;

    public OllamaVisionImageCaptioner(ChatModel chatModel) {
        this(chatModel, DEFAULT_PROMPT);
    }

    public OllamaVisionImageCaptioner(ChatModel chatModel, String prompt) {
        this.chatModel = chatModel;
        this.prompt = (prompt != null && !prompt.isBlank()) ? prompt : DEFAULT_PROMPT;
        this.client = chatModel != null ? ChatClient.builder(chatModel).build() : null;
    }

    @Override
    public boolean isAvailable() {
        return chatModel != null && client != null;
    }

    @Override
    public String caption(ImageReference image) {
        if (image == null || image.getBytes() == null || image.getBytes().length == 0) {
            return "";
        }
        if (!isAvailable()) {
            return "";
        }
        try {
            MimeType mimeType = toMimeType(image.getMimeType());
            Media media = Media.builder()
                    .mimeType(mimeType)
                    .data(new ByteArrayResource(image.getBytes(), image.getSourceName()))
                    .build();
            return doCaption(prompt, media);
        } catch (Exception e) {
            log.warn("[VisionCaptioner] 视觉描述失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 实际发起视觉模型调用——抽为受保护方法便于单测桩接。
     */
    protected String doCaption(String prompt, Media media) {
        String text = client.prompt()
                .user(u -> u.text(prompt).media(media))
                .call()
                .content();
        return text != null ? text.trim() : "";
    }

    /**
     * 将 MIME 类型字符串或扩展名解析为 {@link MimeType}；缺省回落 image/png。
     */
    private static MimeType toMimeType(String s) {
        if (s == null || s.isBlank()) {
            return new MimeType("image", "png");
        }
        String normalized = s.trim().toLowerCase();
        if (normalized.contains("/")) {
            try {
                return MimeTypeUtils.parseMimeType(normalized);
            } catch (Exception ignored) {
                // 解析失败回落扩展名分支
            }
        }
        String ext = normalized.startsWith(".") ? normalized.substring(1) : normalized;
        return new MimeType("image", ext);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OllamaVisionImageCaptioner)) return false;
        OllamaVisionImageCaptioner that = (OllamaVisionImageCaptioner) o;
        return Objects.equals(chatModel, that.chatModel);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(chatModel);
    }
}
