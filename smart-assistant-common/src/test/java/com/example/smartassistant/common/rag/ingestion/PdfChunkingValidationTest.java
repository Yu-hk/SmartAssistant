package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.ParentChildDocumentChunker;
import com.example.smartassistant.common.rag.chunking.RecursiveChunkStrategy;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.document.PdfDocumentParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证：用当前分支真实的 PDF 解析器 + Parent-Child 分块器，
 * 对生成的中文知识库 PDF（下单/退单/支付）进行切分，并分析实际效果。
 *
 * 管线对齐生产：PdfDocumentParser.parse(...) → ParentChildDocumentChunker.chunkParentChild(...)
 * 默认参数：childMaxTokens=256 / parentMaxTokens=1024 / overlap=50（SemanticChunkStrategy）
 */
class PdfChunkingValidationTest {

    private static final String[] PDFS = {
            "下单操作指南.pdf",
            "订单取消与退款政策.pdf",
            "支付安全与发票说明.pdf"
    };

    @Test
    @DisplayName("PDF-VAL: 真实解析+Parent-Child 切分全量验证")
    void validatePdfChunking() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser();
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker(); // 默认 256/1024/50

        for (String fileName : PDFS) {
            Path pdfPath = resolveResource("/knowledge-pdfs/" + fileName);
            System.out.printf("%n================================================================%n");
            System.out.printf("📄 PDF: %s%n", fileName);
            System.out.printf("================================================================%n");

            // —— Stage 1: 解析（与生产一致）——
            List<ParsedDocument> parsed = parser.parse(pdfPath.toString());
            assertFalse(parsed.isEmpty(), fileName + " 不应解析为空");
            System.out.printf("🔍 解析产出 ParsedDocument 元素: %d 个%n", parsed.size());

            // 解析元素类型分布（正文 pdf / 表格 pdf-table / OCR）
            Map<String, Long> typeDist = parsed.stream()
                    .collect(Collectors.groupingBy(
                            d -> d.getContentType() == null ? "null" : d.getContentType(),
                            Collectors.counting()));
            System.out.printf("   类型分布: %s%n", typeDist);

            // 逐元素明细：确认表格检测器是否误报
            System.out.printf("   逐元素明细:%n");
            int ei = 0;
            for (ParsedDocument d : parsed) {
                String snippet = d.getContent() == null ? "" : d.getContent().replace("\n", " ");
                if (snippet.length() > 38) snippet = snippet.substring(0, 38) + "…";
                System.out.printf("     [%d] %-9s %s%n", ei++, d.getContentType(), snippet);
            }

            // 正文元素（不含表格）的总字符数
            int proseChars = parsed.stream()
                    .filter(d -> !"pdf-table".equals(d.getContentType()))
                    .mapToInt(d -> d.getContent() == null ? 0 : d.getContent().length())
                    .sum();
            System.out.printf("   正文总字符数: %d（按中文 1字≈1token）%n", proseChars);

            // —— Stage 2: Parent-Child 切分（与生产一致）——
            ParentChildDocumentChunker.ParentChildResult result =
                    chunker.chunkParentChild(parsed);
            List<KnowledgeDocument> parents = result.parentDocs();
            List<KnowledgeDocument> children = result.childDocs();

            System.out.printf("%n✂️  Parent-Child 切分结果:%n");
            System.out.printf("   父块(parent, 阅读用, ≤1024tok): %d 个%n", parents.size());
            System.out.printf("   子块(child, 检索用, ≤256tok): %d 个%n", children.size());

            // 父块 token 分布
            printTokenStats("父块", parents);
            // 子块 token 分布
            printTokenStats("子块", children);

            // 子块是否都关联了父块
            long orphan = children.stream()
                    .filter(c -> c.getParentDocId() == null || c.getParentDocId().isBlank())
                    .count();
            System.out.printf("   子块未关联父块数: %d（应为 0）%n", orphan);
            assertTrue(orphan == 0, fileName + " 所有子块必须关联父块");

            // 父子粒度差异：平均子块应明显小于平均父块
            double avgParent = avgTokens(parents);
            double avgChild = avgTokens(children);
            System.out.printf("   平均 token: 父块=%.0f / 子块=%.0f （子块应更小）%n",
                    avgParent, avgChild);
            assertTrue(avgChild <= avgParent + 1e-6,
                    "子块平均粒度应 ≤ 父块 " + fileName);

            // ⭐ 回归门禁：父块粒度必须是"章节级"而非"段落级"（修复 PdfDocumentParser 合并前会退化到 ~150tok）
            assertTrue(avgParent > avgChild * 1.5,
                    fileName + " 父块应显著大于子块（Parent-Child 双粒度不能退化为 1:1），"
                            + " avgParent=" + avgParent + " avgChild=" + avgChild);

            // 标题/章节覆盖：父块内容是否包含章节标记（一、二、/ 1. 2.）
            long headingParents = parents.stream()
                    .filter(p -> p.getContent().matches("(?s).*(一、|二、|三、|四、|五、|六、|1\\s|2\\s|3\\s).*"))
                    .count();
            System.out.printf("   含章节标记的父块: %d/%d%n", headingParents, parents.size());

            // 子块大小合规（≤256 + 容差）
            long oversized = children.stream()
                    .filter(c -> RecursiveChunkStrategy.estimateTokens(c.getContent()) > 256 * 1.2)
                    .count();
            System.out.printf("   超阈值(>307tok)子块: %d 个%n", oversized);
            assertTrue(oversized == 0, fileName + " 不应有严重超阈值的子块");

            // ⭐ 回归门禁：表格检测器收紧后，pdf-table 数量应与真表数一致（不应再误报编号列表/相邻文本）
            long tableCount = parsed.stream()
                    .filter(d -> "pdf-table".equals(d.getContentType()))
                    .count();
            System.out.printf("   pdf-table 元素数: %d（收紧后应≈真表数，不应泛滥）%n", tableCount);
            assertTrue(tableCount <= 3,
                    fileName + " 表格误报应被消除（pdf-table 数量应 ≤ 3，实际=" + tableCount + "）");

            // —— 关键观察：解析阶段已按标题/大小合并为"章节级"长元素，Parent-Child 双粒度生效 ——
            System.out.printf("%n🔎 观察: 解析阶段产出 %d 个合并元素 → 父块粒度=%.0f tok（章节级），%n",
                    parsed.size(), avgParent);
            System.out.printf("   Parent-Child 双粒度已生效（父≈%.0f / 子≈%.0f），不再退化为段落级 1:1。%n",
                    avgParent, avgChild);
        }

        System.out.printf("%n================================================================%n");
        System.out.printf("✅ 全部 %d 份 PDF 通过真实管线解析与 Parent-Child 切分验证%n", PDFS.length);
        System.out.printf("================================================================%n");
    }

    private void printTokenStats(String label, List<KnowledgeDocument> docs) {
        if (docs.isEmpty()) {
            System.out.printf("   %s: 空%n", label);
            return;
        }
        List<Integer> toks = docs.stream()
                .map(d -> RecursiveChunkStrategy.estimateTokens(d.getContent()))
                .sorted()
                .collect(Collectors.toList());
        int min = toks.get(0), max = toks.get(toks.size() - 1);
        double avg = toks.stream().mapToInt(Integer::intValue).average().orElse(0);
        System.out.printf("   %s token: min=%d, avg=%.0f, max=%d, 中位数=%d%n",
                label, min, avg, max, toks.get(toks.size() / 2));
        // 展示前 3 个块预览
        int show = Math.min(3, docs.size());
        for (int i = 0; i < show; i++) {
            String preview = docs.get(i).getContent().replace("\n", " ");
            if (preview.length() > 70) preview = preview.substring(0, 70) + "…";
            System.out.printf("     [%d] %dtok: %s%n", i, toks.get(i), preview);
        }
    }

    private double avgTokens(List<KnowledgeDocument> docs) {
        return docs.stream()
                .mapToInt(d -> RecursiveChunkStrategy.estimateTokens(d.getContent()))
                .average().orElse(0);
    }

    /** 从 classpath 资源解析为文件系统路径（Maven 会把 resources 复制到 target/test-classes） */
    private Path resolveResource(String resourcePath) throws Exception {
        URL url = getClass().getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException("测试资源未找到: " + resourcePath
                    + "（请先执行 mvn 资源拷贝，或确认 PDF 已放入 src/test/resources/knowledge-pdfs/）");
        }
        return Paths.get(url.toURI());
    }
}
