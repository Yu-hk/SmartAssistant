package com.example.smartassistant.embedding;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 向量嵌入控制器单测（Mockito 纯单元，不加载 ONNX 模型）。
 * <p>覆盖输入校验、正常嵌入、模型不可用与批量边界、健康检查等分支。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("[Embedding] 向量嵌入控制器单测")
class EmbeddingControllerTest {

    @Mock
    private BgeEmbeddingModel embeddingModel;

    @InjectMocks
    private EmbeddingController controller;

    @Test
    @DisplayName("单条嵌入：合法文本返回向量列表与维度")
    void embedValidText() {
        // 模拟模型返回 1024 维向量（与 BGE 模型真实维度一致），仅前 3 维有值
        float[] vec = new float[1024];
        vec[0] = 0.1f;
        vec[1] = 0.2f;
        vec[2] = 0.3f;
        when(embeddingModel.embedding("你好")).thenReturn(vec);

        Map<String, Object> resp = controller.embed(Map.of("text", "你好"));

        assertNotNull(resp);
        assertFalse(resp.containsKey("error"), "正常请求不应含 error 字段");
        assertEquals(1024, resp.get("dimensions"));
        @SuppressWarnings("unchecked")
        List<Float> emb = (List<Float>) resp.get("embedding");
        assertEquals(1024, emb.size(), "应返回 1024 维向量");
        assertEquals(0.1f, emb.get(0), 1e-6);
    }

    @Test
    @DisplayName("单条嵌入：空白文本返回 error")
    void embedBlankTextReturnsError() {
        Map<String, Object> resp = controller.embed(Map.of("text", "   "));
        assertTrue(resp.containsKey("error"));
    }

    @Test
    @DisplayName("单条嵌入：模型不可用（返回 null）返回 error")
    void embedModelUnavailableReturnsError() {
        when(embeddingModel.embedding("你好")).thenReturn(null);
        Map<String, Object> resp = controller.embed(Map.of("text", "你好"));
        assertTrue(resp.containsKey("error"));
    }

    @Test
    @DisplayName("批量嵌入：正常返回各条向量（模型不可用时对应位置为空列表）")
    void embedBatchValid() {
        when(embeddingModel.embedding("a")).thenReturn(new float[]{1.0f});
        when(embeddingModel.embedding("b")).thenReturn(null); // 模拟 b 失败

        Map<String, Object> resp = controller.embedBatch(Map.of("texts", List.of("a", "b")));
        assertFalse(resp.containsKey("error"));
        @SuppressWarnings("unchecked")
        List<List<Float>> embs = (List<List<Float>>) resp.get("embeddings");
        assertEquals(2, embs.size());
        assertEquals(1, embs.get(0).size());
        assertTrue(embs.get(1).isEmpty(), "模型不可用的条目应返回空列表而非抛异常");
    }

    @Test
    @DisplayName("批量嵌入：空列表返回 error")
    void embedBatchEmptyReturnsError() {
        Map<String, Object> resp = controller.embedBatch(Map.of("texts", List.of()));
        assertTrue(resp.containsKey("error"));
    }

    @Test
    @DisplayName("健康检查：模型可用返回 UP")
    void healthUp() {
        when(embeddingModel.isAvailable()).thenReturn(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) controller.health();
        assertEquals("UP", resp.get("status"));
        assertTrue((Boolean) resp.get("available"));
    }

    @Test
    @DisplayName("健康检查：模型不可用返回 DOWN")
    void healthDown() {
        when(embeddingModel.isAvailable()).thenReturn(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) controller.health();
        assertEquals("DOWN", resp.get("status"));
    }

    @Test
    @DisplayName("维度接口：返回模型维度")
    void dimensions() {
        when(embeddingModel.dimensions()).thenReturn(1024);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) controller.dimensions();
        assertEquals(1024, resp.get("dimensions"));
    }
}
