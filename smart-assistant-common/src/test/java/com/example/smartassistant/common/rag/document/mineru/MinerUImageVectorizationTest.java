/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.example.smartassistant.common.rag.document.DocumentParser;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-B 图片向量化映射与降级验证（引擎无关，注入合成响应）。
 *
 * <p>核心安全契约：</p>
 * <ul>
 *   <li>未开启 {@code enabledImageVectorization} → 不向量化、不携带字节、不标记 flag。</li>
 *   <li>开启但视觉模型不可用（Noop）→ 安全降级：不携带字节、不标记 flag、不抛异常、仍索引 caption/OCR 文本。</li>
 *   <li>开启且模型可用且图片文件存在 → 标记 {@code pdf.imageVector=1} / {@code pdf.imageVectorDim}，
 *       并经由 {@link ParsedDocument#getImageBytes()} 透传图片字节载体至下游视觉嵌入步骤。</li>
 * </ul>
 */
class MinerUImageVectorizationTest {

    /** 构造含一张「带 caption 图片（同页无正文 → pdf-image-caption）」页的合成响应。 */
    private static MinerUParseResponse captionResponse(String imagePath) {
        MinerUBlock image = new MinerUBlock();
        image.setType("image");
        image.setText(null);
        image.setImagePath(imagePath);
        image.setImageCaption("图1 架构示意");
        MinerUPage page = new MinerUPage(1, List.of(image));
        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setPages(List.of(page));
        return resp;
    }

    @Test
    void disabledByDefault_noVectorization() throws Exception {
        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setEnabledImageVectorization(false);

        DocumentParser parser = new MinerUDocumentParser(
                new FakeMinerUClient(captionResponse("i/p1.jpg")),
                props, new NoopImageEmbeddingModel());
        List<ParsedDocument> docs = parser.parse("doc.pdf");

        assertEquals(1, docs.size(), "应产出 1 个文档");
        ParsedDocument d = docs.get(0);
        assertNull(d.getImageBytes(), "未开启向量化时不应携带图片字节");
        assertFalse(d.getMetadata().containsKey("pdf.imageVector"), "不应标记 pdf.imageVector");
        assertEquals("pdf-image-caption", d.getContentType(), "caption 仍应进索引");
    }

    @Test
    void enabledButModelUnavailable_safeDegrade(@TempDir Path tmp) throws Exception {
        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setEnabledImageVectorization(true);
        props.setImagesTempDir(tmp.toString());

        DocumentParser parser = new MinerUDocumentParser(
                new FakeMinerUClient(captionResponse("i/p1.jpg")),
                props, new NoopImageEmbeddingModel());
        List<ParsedDocument> docs = parser.parse("doc.pdf");

        assertEquals(1, docs.size(), "应产出 1 个文档");
        ParsedDocument d = docs.get(0);
        assertNull(d.getImageBytes(), "模型不可用时安全降级，不携带字节");
        assertFalse(d.getMetadata().containsKey("pdf.imageVector"), "模型不可用不应标记向量化");
        assertEquals("pdf-image-caption", d.getContentType(), "仍应索引 caption 文本");
    }

    @Test
    void enabledAndModelAvailable_vectorsImage(@TempDir Path tmp) throws Exception {
        // 真实图片字节文件：直接放在临时根目录，用相对 "../" 逃逸内部随机 requestId 子目录，
        // 使 Paths.get(imagesDir, rel) 命中本文件。
        Path imgFile = tmp.resolve("p1_img1.jpg");
        Files.write(imgFile, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}); // 伪 JPEG 头
        FakeImageEmbeddingModel model = new FakeImageEmbeddingModel(8);

        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setEnabledImageVectorization(true);
        props.setImagesTempDir(tmp.toString());

        DocumentParser parser = new MinerUDocumentParser(
                new FakeMinerUClient(captionResponse("../p1_img1.jpg")),
                props, model);
        List<ParsedDocument> docs = parser.parse("doc.pdf");

        assertEquals(1, docs.size(), "应产出 1 个文档");
        ParsedDocument d = docs.get(0);
        assertNotNull(d.getImageBytes(), "向量化成功应携带图片字节载体");
        assertEquals("1", d.getMetadata().get("pdf.imageVector"), "应标记 pdf.imageVector=1");
        assertEquals("8", d.getMetadata().get("pdf.imageVectorDim"), "应写入向量维度");
        assertTrue(d.getContent().contains("图1 架构示意"), "caption 文本仍应进索引");
    }

    @Test
    void routerThreeArgConstructor_wiresModelWithoutNpe() {
        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setEnabledImageVectorization(true);

        DocumentParseRouter router = new DocumentParseRouter(
                props, new FakeMinerUClient(captionResponse("i/p1.jpg")), new NoopImageEmbeddingModel());
        assertTrue(router.supports("doc.pdf"), "三参构造不应破坏 pdf 支持判定");
    }

    // ==================== fakes ====================

    private static class FakeMinerUClient implements MinerUClient {
        private final MinerUParseResponse fixed;

        FakeMinerUClient(MinerUParseResponse fixed) {
            this.fixed = fixed;
        }

        @Override
        public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
            return fixed;
        }
    }

    private static class FakeImageEmbeddingModel implements ImageEmbeddingModel {
        private final int dim;

        FakeImageEmbeddingModel(int dim) {
            this.dim = dim;
        }

        @Override
        public float[] embed(byte[] imageBytes) {
            assertNotNull(imageBytes, "模型应收到非空图片字节");
            float[] v = new float[dim];
            for (int i = 0; i < dim; i++) v[i] = i;
            return v;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int dimension() {
            return dim;
        }
    }
}
