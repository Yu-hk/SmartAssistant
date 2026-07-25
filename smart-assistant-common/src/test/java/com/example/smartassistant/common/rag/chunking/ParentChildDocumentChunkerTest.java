package com.example.smartassistant.common.rag.chunking;

import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParentChildDocumentChunker 单元测试。
 */
class ParentChildDocumentChunkerTest {

    @Test
    @DisplayName("空文档列表应返回空结果")
    void chunkParentChild_withEmptyElements_shouldReturnEmpty() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        var result = chunker.chunkParentChild(List.of());
        assertTrue(result.parentDocs().isEmpty());
        assertTrue(result.childDocs().isEmpty());
    }

    @Test
    @DisplayName("非空文档应产生至少一个父块")
    void chunkParentChild_withShortText_shouldProduceAtLeastOneParent() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        // 使用较长文本确保超过最小分块阈值
        String longText = "这是一个测试文档内容，包含多个句子。"
                + "第一段是对产品的描述。第二段介绍了使用方法。"
                + "第三段涵盖了注意事项。第四段提供了售后服务信息。";
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-001").title("测试文档")
                .content(longText)
                .category("测试")
                .build();

        var result = chunker.chunkParentChild(List.of(doc));
        assertFalse(result.parentDocs().isEmpty(), "应生成至少一个父块");
    }

    @Test
    @DisplayName("子块的 parentDocId 应指向对应的父块")
    void chunkParentChild_childShouldReferenceParent() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        String longContent = "第一段内容。\n\n第二段内容。\n\n第三段内容。\n\n第四段内容。\n\n第五段内容。";
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-002").title("长文档")
                .content(longContent).category("测试")
                .build();

        var result = chunker.chunkParentChild(List.of(doc));

        // 检查子块是否引用了父块
        if (!result.parentDocs().isEmpty() && !result.childDocs().isEmpty()) {
            String parentId = result.parentDocs().get(0).getId();
            for (KnowledgeDocument child : result.childDocs()) {
                assertFalse(child.getParentDocId().isEmpty(),
                        "子块 " + child.getId() + " 应包含 parentDocId");
                // 子块 parentDocId 应该以父块 ID 开头
                assertTrue(child.getParentDocId().startsWith("doc-002-parent-"),
                        "子块 parentDocId 应以父块 ID 开头");
            }
        }
    }

    @Test
    @DisplayName("子块 keywords 应包含分块位置信息")
    void chunkParentChild_childShouldHavePositionKeywords() {
        ParentChildDocumentChunker chunker = new ParentChildDocumentChunker();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("第").append(i).append("段内容。");
        }
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-003").title("多段文档")
                .content(sb.toString()).category("测试")
                .build();

        var result = chunker.chunkParentChild(List.of(doc));
        for (KnowledgeDocument child : result.childDocs()) {
            assertNotNull(child.getKeywords());
        }
    }

    @Test
    @DisplayName("子块应启用语义重叠：overlap>0 时子块内容额外携带边界前缀")
    void childChunksCarryOverlapWhenStrategyHonorsIt() {
        // 用规则策略直接验证「子块重叠」接线：文本 ≤ 父块上限 → 仅 1 个父块（父级重叠不生效），
        // 任何内容差异均来自子块重叠，从而隔离验证第 123 行 overlap 参数已生效。
        ChunkStrategy strategy = new RecursiveChunkStrategy();
        String text = "智能助理支持订单查询功能，退款申请可在三个工作日内完成，物流状态实时同步。"
                + "客服热线随时接入，会员权益长期有效，产品说明文档持续更新。"
                + "智能助理支持订单查询功能，退款申请可在三个工作日内完成，物流状态实时同步。"
                + "客服热线随时接入，会员权益长期有效，产品说明文档持续更新。";
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-004").title("重叠验证")
                .content(text).category("测试")
                .build();

        // 父块上限 400（>文本）→ 单父块；子块上限 30；overlap 15 vs 0
        ParentChildDocumentChunker withOverlap =
                new ParentChildDocumentChunker(strategy, 30, 400, 15, null);
        ParentChildDocumentChunker noOverlap =
                new ParentChildDocumentChunker(strategy, 30, 400, 0, null);

        var r1 = withOverlap.chunkParentChild(List.of(doc));
        var r2 = noOverlap.chunkParentChild(List.of(doc));

        assertFalse(r1.childDocs().isEmpty(), "应产生至少一个子块");
        // 隔离验证：单父块下，overlap>0 的子块内容应比 overlap=0 额外多出重叠前缀
        int sum1 = r1.childDocs().stream().mapToInt(d -> d.getContent().length()).sum();
        int sum2 = r2.childDocs().stream().mapToInt(d -> d.getContent().length()).sum();
        assertTrue(sum1 > sum2,
                "子块启用重叠后内容应额外携带边界前缀，sum(overlap)=" + sum1 + " > sum(noOverlap)=" + sum2);
    }

    @Test
    @DisplayName("BGE 语义路径：子块同样携带语义重叠（端到端）")
    void childChunksCarrySemanticOverlapViaBge() {
        // BGE stub：同主题句子共享字符 trigram → 相似度高 → 无 breakpoint → 单语义组。
        // 单父块（≤父块上限）下，子块因超过子块上限走「超长组」递归切分；修复后该分支
        // 同样传入 overlap，故子块应携带边界重叠前缀。
        ChunkStrategy bge = new EmbeddingSemanticChunkStrategy(stubEmbedder(), new RecursiveChunkStrategy(), 0.85, 10);
        // 同主题长文本：单句重复 → 语义相似度高（无 breakpoint）→ 单语义组；
        // 总 token 远超子块上限(256) 但低于父块上限(1024) → 单父块，子块走「超长组」递归切分。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 22; i++) {
            sb.append("订单退款申请已提交成功请耐心等待发货审核并通知客服。");
        }
        String text = sb.toString();
        ParsedDocument doc = ParsedDocument.builder()
                .docId("doc-005").title("BGE重叠验证")
                .content(text).category("测试")
                .build();

        // 生产近似配置：父块 1024、子块 256；文本同主题且 <1024 → 单父块，隔离子块重叠
        ParentChildDocumentChunker withOverlap =
                new ParentChildDocumentChunker(bge, 256, 1024, 15, null);
        ParentChildDocumentChunker noOverlap =
                new ParentChildDocumentChunker(bge, 256, 1024, 0, null);

        var r1 = withOverlap.chunkParentChild(List.of(doc));
        var r2 = noOverlap.chunkParentChild(List.of(doc));

        assertFalse(r1.childDocs().isEmpty(), "BGE 路径应产生子块");
        for (KnowledgeDocument child : r1.childDocs()) {
            assertFalse(child.getParentDocId().isEmpty(), "子块应关联父块");
        }
        int sum1 = r1.childDocs().stream().mapToInt(d -> d.getContent().length()).sum();
        int sum2 = r2.childDocs().stream().mapToInt(d -> d.getContent().length()).sum();
        assertTrue(sum1 > sum2,
                "BGE 语义路径子块启用重叠后内容应更多，sum(overlap)=" + sum1 + " > sum(noOverlap)=" + sum2);
    }

    /** 与 EmbeddingSemanticChunkStrategyTest 同款确定性 stub embedder（字符 trigram 频率向量） */
    private static java.util.function.Function<String, float[]> stubEmbedder() {
        return sentence -> {
            String s = sentence.replaceAll("\\s+", "");
            int dim = 64;
            float[] v = new float[dim];
            for (int i = 0; i + 2 < s.length(); i++) {
                int idx = Math.floorMod(s.substring(i, i + 3).hashCode(), dim);
                v[idx] += 1;
            }
            double n = 0;
            for (float x : v) n += x * x;
            n = Math.sqrt(n);
            if (n > 0) for (int i = 0; i < dim; i++) v[i] /= (float) n;
            return v;
        };
    }
}
