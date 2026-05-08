package com.example.smartassistant.consumer.service.session;

import com.example.smartassistant.consumer.entity.UserConversationDoc;
import com.example.smartassistant.consumer.mapper.UserConversationDocMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 对话文档沉淀服务
 * 将有价值的对话保存到 pgvector，供后续 RAG 检索
 */
@Service
public class ConversationDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDocumentService.class);

    private static final String DASHSCOPE_EMBEDDING_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    private final UserConversationDocMapper docMapper;
    private final RestTemplate restTemplate;

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    public ConversationDocumentService(UserConversationDocMapper docMapper) {
        this.docMapper = docMapper;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 异步保存有价值对话到 pgvector
     */
    @Async("taskExecutor")
    public void saveValuableConversation(ConversationValueService.ConversationValueContext ctx) {
        try {
            float[] embedding = generateEmbedding(ctx.content());
            if (embedding == null || embedding.length == 0) {
                log.warn("[ConvDoc] 向量生成失败: sessionId={}", ctx.sessionId());
                return;
            }

            UserConversationDoc doc = UserConversationDoc.builder()
                    .userId(ctx.userId())
                    .sessionId(ctx.sessionId())
                    .content(ctx.content())
                    .agentName(ctx.agentName())
                    .intentTag(ctx.intentTag())
                    .turnCount(ctx.turnCount())
                    .embedding(embedding)
                    .build();

            docMapper.insert(doc);
            log.info("[ConvDoc] 对话已沉淀为文档: sessionId={}, userId={}, agent={}, id={}",
                    ctx.sessionId(), ctx.userId(), ctx.agentName(), doc.getId());

        } catch (Exception e) {
            log.warn("[ConvDoc] 保存对话文档失败: sessionId={}, error={}", ctx.sessionId(), e.getMessage());
        }
    }

    /**
     * 调用 DashScope API 生成文本向量
     */
    @SuppressWarnings("unchecked")
    private float[] generateEmbedding(String text) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("[ConvDoc] DashScope API Key 未配置");
            return null;
        }

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "text-embedding-v4");
            requestBody.put("input", Map.of("texts", List.of(text)));
            requestBody.put("parameters", Map.of("text_type", "document"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    DASHSCOPE_EMBEDDING_URL, HttpMethod.POST, request, Map.class);

            if (response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Object outputObj = body.get("output");
                if (outputObj instanceof Map output) {
                    Object embeddingsObj = output.get("embeddings");
                    if (embeddingsObj instanceof List embeddings && !embeddings.isEmpty()) {
                        Map<String, Object> first = (Map<String, Object>) embeddings.get(0);
                        Object embeddingObj = first.get("embedding");
                        if (embeddingObj instanceof List embeddingList) {
                            float[] arr = new float[embeddingList.size()];
                            for (int i = 0; i < embeddingList.size(); i++) {
                                arr[i] = ((Number) embeddingList.get(i)).floatValue();
                            }
                            return arr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ConvDoc] Embedding 调用失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 检索用户的历史对话文档
     */
    public List<UserConversationDoc> searchUserDocuments(Long userId, String query, int topK) {
        try {
            float[] queryEmbedding = generateEmbedding(query);
            if (queryEmbedding == null) return List.of();
            return docMapper.searchSimilar(userId, queryEmbedding, topK);
        } catch (Exception e) {
            log.warn("[ConvDoc] 检索失败: {}", e.getMessage());
            return List.of();
        }
    }
}
