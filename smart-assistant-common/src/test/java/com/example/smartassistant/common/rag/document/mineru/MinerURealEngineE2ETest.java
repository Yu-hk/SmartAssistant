/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParser;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P5-C 真实 magic-pdf 引擎端到端验证（默认关闭，显式开启才执行）。
 *
 * <p><b>设计原则（绝不阻塞沙箱/CI）</b>：</p>
 * <ul>
 *   <li>默认 {@code -Dp5.e2e.enabled=false} → 直接 skip；</li>
 *   <li>开启后仍探测 magic-pdf 是否安装，未安装 → skip；</li>
 *   <li>因此仅在「显式开启 + 已部署 magic-pdf」的用户本机执行真实链路，
 *       实现真正的「端到端实测验证」。</li>
 * </ul>
 *
 * <p>链路：PDFBox 生成含内嵌图片的 PDF → {@link MinerUSidecarClient}（真实 magic-pdf） →
 * {@link MinerUDocumentParser}（含图片向量化）→ {@link ParsedDocument} 列表。
 * 断言解析不抛异常、至少产出 1 个文档；若产出图片类块且开启向量化，应至少存在 1 个已向量化文档。</p>
 */
class MinerURealEngineE2ETest {

    private MinerUSidecarClient client;

    /** 是否显式开启 P5-C 端到端验证 */
    private static boolean e2eEnabled() {
        return Boolean.parseBoolean(System.getProperty("p5.e2e.enabled", "false"));
    }

    /** 探测可 import magic_pdf 的 python 解释器（其 venv 的 magic-pdf CLI 应同目录） */
    private static String detectPython() {
        for (String py : new String[]{"python", "python3"}) {
            try {
                Process p = new ProcessBuilder(py, "-c", "import magic_pdf").start();
                if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return py;
                }
            } catch (Exception ignore) {
                // 尝试下一个候选
            }
        }
        return null;
    }

    /** 用 PDFBox 生成含内嵌图片 + 文本行的 PDF（供 magic-pdf 抽取图片块与正文） */
    private Path generatePdfWithImage() throws Exception {
        Path pdf = Paths.get(System.getProperty("java.io.tmpdir"),
                "mineru_e2e_" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            BufferedImage bi = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            g.setColor(Color.RED);
            g.fillRect(0, 0, 80, 80);
            g.dispose();
            PDImageXObject img = LosslessFactory.createFromImage(doc, bi);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 60, 60, 80, 80);
                cs.beginText();
                cs.newLineAtOffset(60, 720);
                cs.showText("端到端验证样例文本（P5）");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void realMagicPdfEndToEnd() throws Exception {
        assumeTrue(e2eEnabled(),
                "P5-C 端到端验证默认关闭；在已部署 magic-pdf 的环境加 -Dp5.e2e.enabled=true 运行 mvn test");
        String python = detectPython();
        assumeTrue(python != null,
                "magic-pdf 未安装（python 无法 import magic_pdf），跳过真实引擎端到端测试");

        Path sidecar = Paths.get("src/main/resources/mineru/mineru_sidecar.py").toAbsolutePath();
        assumeTrue(new File(sidecar.toString()).exists(), "mineru_sidecar.py 不存在，跳过");

        Path pdf = generatePdfWithImage();

        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setRouteOnImages(true);
        props.setEnabledImageVectorization(true);
        props.setImagesTempDir(System.getProperty("java.io.tmpdir") + "/mineru_e2e");
        props.setSidecarCommand(python + " " + sidecar);
        props.setWarmInstances(1);
        props.setTimeoutMs(600000L); // 首次运行可能需下载模型，放宽超时

        client = new MinerUSidecarClient(props);
        ImageEmbeddingModel fakeModel = new FakeAvailableImageEmbeddingModel(8);

        DocumentParser parser = new MinerUDocumentParser(client, props, fakeModel);
        List<ParsedDocument> docs = parser.parse(pdf.toString());

        assertNotNull(docs, "解析结果不应为 null");
        assertFalse(docs.isEmpty(), "真实引擎应至少产出 1 个文档");

        // 若产出图片类块（pdf-image-caption / pdf-image-ocr / pdf-ocr）且开启向量化，
        // 应至少存在 1 个已向量化文档（pdf.imageVector=1）；其余情况视为无图可向量化，放行。
        boolean anyImageBlock = docs.stream().anyMatch(d -> {
            String ct = d.getContentType();
            return ct != null && (ct.startsWith("pdf-image") || "pdf-ocr".equals(ct));
        });
        if (anyImageBlock) {
            boolean anyVectorized = docs.stream()
                    .anyMatch(d -> "1".equals(d.getMetadata().get("pdf.imageVector")));
            assertTrue(anyVectorized,
                    "开启向量化且产出图片块时，应至少存在一个已向量化文档（pdf.imageVector=1）");
        }
    }

    /** 可用的假视觉模型：固定维度向量，供端到端验证向量化链路贯通。 */
    private static class FakeAvailableImageEmbeddingModel implements ImageEmbeddingModel {
        private final int dim;

        FakeAvailableImageEmbeddingModel(int dim) {
            this.dim = dim;
        }

        @Override
        public float[] embed(byte[] imageBytes) {
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
