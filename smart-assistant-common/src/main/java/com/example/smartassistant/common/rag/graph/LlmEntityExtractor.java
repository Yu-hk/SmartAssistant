/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的实体关系抽取器 — {@link EntityExtractor} 的真实实现。
 * <p>
 * 复用统一 {@link AiChatService#entity(ChatModel, String, String, Class)} 结构化输出能力，
 * 从文档文本中抽取实体与关系，并映射为 {@link EntityNode}/{@link EntityRelation}。
 * 实体 ID 由名称归一化哈希稳定生成，使跨文档的同名实体可在图谱中合并关联。
 * </p>
 *
 * <p>激活条件：构造时传入非 null 的 {@link ChatModel} 即 {@link #isAvailable() 可用}；
 * 否则应降级为 {@link NoopEntityExtractor}。抽取过程对异常完全隔离，
 * 失败时返回空结果，保证摄取链路不被中断。</p>
 */
public class LlmEntityExtractor implements EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmEntityExtractor.class);

    /** 送入 LLM 的最大文本长度，避免超长上下文与高昂 token 消耗 */
    private static final int MAX_INPUT_CHARS = 6000;

    /** 触发抽取的段落最小长度（过短无意义） */
    static final int MIN_EXTRACT_CHARS = 20;

    private final ChatModel chatModel;
    private final AiChatService aiChatService;

    public LlmEntityExtractor(ChatModel chatModel) {
        this(chatModel, null);
    }

    public LlmEntityExtractor(ChatModel chatModel, AiChatService aiChatService) {
        this.chatModel = chatModel;
        this.aiChatService = aiChatService;
    }

    @Override
    public boolean isAvailable() {
        return chatModel != null;
    }

    @Override
    public ExtractionResult extract(String content, String docId, String kbName) {
        if (!isAvailable() || content == null || content.isBlank()) {
            return ExtractionResult.EMPTY;
        }
        String text = content.length() > MAX_INPUT_CHARS
                ? content.substring(0, MAX_INPUT_CHARS) : content;
        try {
            ExtractionSchema schema = invokeLlm(text);
            if (schema == null) return ExtractionResult.EMPTY;
            List<EntityNode> nodes = toNodes(schema.entities(), docId, kbName);
            List<EntityRelation> relations = toRelations(schema.relations());
            return new ExtractionResult(nodes, relations);
        } catch (Exception e) {
            log.warn("[LlmEntityExtractor] 抽取失败: docId={}, error={}", docId, e.getMessage());
            return ExtractionResult.EMPTY;
        }
    }

    private ExtractionSchema invokeLlm(String text) {
        String system = """
                你是一个知识图谱抽取引擎。请从用户给出的文档片段中抽取实体与关系。
                严格仅输出一个 JSON 对象，不要输出任何解释或 Markdown 代码块标记。
                JSON 结构约定：
                {
                  "entities": [
                    { "name": "实体名称(必填)", "type": "类型如 product/order/user/policy/location/org", "desc": "简短描述" }
                  ],
                  "relations": [
                    { "source": "源实体名称(必填)", "target": "目标实体名称(必填)", "type": "关系类型如 下单/属于/引用/冲突/位于", "desc": "关系描述", "confidence": 0.0到1.0之间的数值 }
                  ]
                }
                若没有可抽取的内容，返回 {"entities":[],"relations":[]}。""";
        if (aiChatService != null) {
            return aiChatService.entity(chatModel, system, text, ExtractionSchema.class);
        }
        // 退化路径：无 advisor 链时直接用裸 ChatClient 结构化输出
        return ChatClient.create(chatModel)
                .prompt().system(system).user(text).call().entity(ExtractionSchema.class);
    }

    private List<EntityNode> toNodes(List<EntityDto> dtos, String docId, String kbName) {
        if (dtos == null) return List.of();
        List<EntityNode> nodes = new ArrayList<>();
        for (EntityDto d : dtos) {
            if (d == null || d.name() == null || d.name().isBlank()) continue;
            String id = "ent:" + Integer.toHexString(d.name().trim().toLowerCase().hashCode());
            nodes.add(new EntityNode(id, d.name().trim(),
                    (d.type() != null && !d.type().isBlank()) ? d.type().trim() : "unknown",
                    d.desc() != null ? d.desc() : "",
                    docId, kbName));
        }
        return nodes;
    }

    private List<EntityRelation> toRelations(List<RelationDto> dtos) {
        if (dtos == null) return List.of();
        List<EntityRelation> rels = new ArrayList<>();
        for (RelationDto d : dtos) {
            if (d == null || d.source() == null || d.target() == null) continue;
            String id = "rel:" + Integer.toHexString(
                    (d.source().trim() + "->" + d.target().trim() + ":" + (d.type() != null ? d.type() : "")).hashCode());
            rels.add(new EntityRelation(id, d.source().trim(), d.target().trim(),
                    (d.type() != null && !d.type().isBlank()) ? d.type().trim() : "关联",
                    d.desc() != null ? d.desc() : "",
                    d.confidence() != null ? d.confidence() : 0.8,
                    null));
        }
        return rels;
    }

    // ==================== 结构化输出 DTO（Spring AI entity() 映射目标） ====================

    /** 实体 DTO */
    public record EntityDto(String name, String type, String desc) {}

    /** 关系 DTO */
    public record RelationDto(String source, String target, String type, String desc, Double confidence) {}

    /** 抽取结果根 DTO */
    public record ExtractionSchema(List<EntityDto> entities, List<RelationDto> relations) {}
}
