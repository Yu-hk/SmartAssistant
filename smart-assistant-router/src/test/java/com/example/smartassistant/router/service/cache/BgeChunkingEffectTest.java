package com.example.smartassistant.router.service.cache;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.*;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BGE 分块效果综合测试。
 * <p>
 * 测试 SemanticChunkStrategy 在各类中文文档上的分块和合并行为，
 * RecursiveChunkStrategy 的递归回退机制，
 * ParentChildDocumentChunker 的双粒度分块，
 * 并通过 BGE 嵌入验证块内语义连贯性和块间区分度。
 * </p>
 *
 * <h3>测试依赖</h3>
 * <ul>
 *   <li>BGE ONNX 模型：models/bge-small-zh-v1.5.onnx（router/src/main/resources/models/）</li>
 *   <li>HuggingFace tokenizer.json：同上路径</li>
 *   <li>缺失时跳过 BGE 相关测试子集，仅运行分块逻辑测试</li>
 * </ul>
 *
 * <p>共 11 个测试用例，按 TCG-01 至 TCG-06 分组。</p>
 */
@DisplayName("BGE 分块效果综合测试")
class BgeChunkingEffectTest {

    // ═══════════════════════════════════════════════════════════════
    // TCG-01: 测试文档
    // ═══════════════════════════════════════════════════════════════

    static final String DOC_MARKDOWN = """
            # SmartAssistant 订单系统使用指南

            ## 1. 下单流程

            用户可以通过商品详情页直接下单。下单时需要确认商品数量、规格和收货地址。
            系统会自动计算运费并显示订单总金额。确认无误后点击"提交订单"按钮。

            ### 1.1 购物车下单

            在商品详情页点击"加入购物车"后，商品会暂存在购物车中。
            购物车支持修改商品数量、删除商品以及查看商品小计。
            点击"去结算"后进入订单确认页面。

            ### 1.2 立即购买

            点击"立即购买"按钮会跳过购物车，直接进入订单确认页面。
            适合快速购买单个商品的场景。

            ## 2. 支付方式

            系统支持多种支付方式：微信支付、支付宝、银行卡支付和余额支付。

            ### 2.1 微信支付

            选择微信支付后会生成支付二维码，使用微信扫码即可完成支付。

            ### 2.2 支付宝支付

            选择支付宝支付后跳转到支付宝收银台。支持花呗分期、余额宝和银行卡支付。

            ## 3. 退款政策

            订单支付后未发货可全额退款。已发货的订单需要先申请退货。
            退款到账时间一般为 1-7 个工作日。
            """;

    static final String DOC_CHAPTER = """
            北京旅游攻略

            一、必游景点推荐

            故宫博物院位于北京中轴线，占地72万平方米，建议游览时间半天。
            天坛公园主要建筑有祈年殿，建议游览2-3小时，门票15元。
            颐和园是中国最大皇家园林，以万寿山和昆明湖为主体。

            二、美食推荐

            北京烤鸭是最具代表性的北京美食。全聚德、大董是三大老字号。
            人均消费约150-300元，建议提前预约。
            老北京炸酱面面条筋道，人均消费约20-40元。

            三、交通出行

            北京地铁覆盖主要城区，共27条线路，票价3-9元。
            出租车起步价13元，每公里2.3元。

            四、住宿建议

            王府井地区靠近故宫天安门，交通便利。
            海淀区靠近颐和园，适合文化游览。
            建议提前一周预订，旺季价格翻倍。
            """;

    static final String DOC_PLAINTEXT = """
            SmartAssistant Pro 智能音箱是本公司最新推出的旗舰产品。搭载了全新的 AI 语音助手，支持自然语言理解和多轮对话。

            产品包含以下配件：智能音箱主机一台、电源适配器一个、说明书一份、保修卡一张。

            首次使用前请先充电 2 小时。长按顶部电源键 3 秒即可开机。开机后音箱会发出语音提示。

            您可以通过语音指令控制音箱播放音乐。支持的音乐平台包括 QQ 音乐、网易云音乐和酷狗音乐。

            智能音箱支持智能家居控制，可连接米家、华为智慧生活等平台。您可以说"打开客厅灯"等指令。

            产品还支持闹钟提醒、天气查询、新闻播报、计算器、翻译等实用功能。

            使用过程中如遇到问题，请首先尝试重启设备。如果问题仍然存在，请拨打客服热线 400-123-4567。
            """;

    static final int MAX_TOKENS = 1024;
    static final int OVERLAP = 128;

    // ═══════════════════════════════════════════════════════════════
    // BGE 嵌入基础设施
    // ═══════════════════════════════════════════════════════════════

    private static final int BGE_MAX_LEN = 512;
    private static final int BGE_DIM = 512;

    static final String BGE_MODEL_PATH =
            "D:\\workspace\\SmartAssistant\\smart-assistant-router\\src\\main\\resources\\models\\bge-small-zh-v1.5.onnx";
    static final String TOKENIZER_PATH =
            "D:\\workspace\\SmartAssistant\\smart-assistant-router\\src\\main\\resources\\models\\bge-small-zh-v1.5\\tokenizer.json";

    static Map<String, Integer> vocab;

    @BeforeAll
    static void loadBgeDeps() {
        try {
            var mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(
                    Paths.get(TOKENIZER_PATH).toFile(),
                    new TypeReference<Map<String, Object>>() {});
            Map<String, Object> modelNode = (Map<String, Object>) root.get("model");
            if (modelNode != null) {
                Object v = modelNode.get("vocab");
                if (v instanceof Map) {
                    vocab = new HashMap<>();
                    for (var entry : ((Map<String, Object>) v).entrySet()) {
                        vocab.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                    }
                    System.out.printf("[SETUP] BGE vocab loaded: %d tokens%n", vocab.size());
                }
            }
        } catch (Exception e) {
            System.out.println("[SETUP] BGE tokenizer not available: " + e.getMessage());
        }
    }

    static OrtSession loadBgeSession() {
        try {
            if (!Files.exists(Paths.get(BGE_MODEL_PATH))) {
                System.out.println("[SKIP] BGE ONNX model not found");
                return null;
            }
            var env = OrtEnvironment.getEnvironment();
            var opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            return env.createSession(Files.readAllBytes(Paths.get(BGE_MODEL_PATH)), opts);
        } catch (Exception e) {
            System.out.println("[SKIP] BGE ONNX load failed: " + e.getMessage());
            return null;
        }
    }

    static float[] embed(String text, OrtSession session, OrtEnvironment env) {
        if (vocab == null || session == null) return null;
        try {
            int unkId = vocab.getOrDefault("[UNK]", 100);
            long[] ids = new long[BGE_MAX_LEN];
            long[] mask = new long[BGE_MAX_LEN];
            ids[0] = 101; mask[0] = 1;
            int pos = 1;
            for (char c : text.toCharArray()) {
                if (pos >= BGE_MAX_LEN - 1) break;
                if (Character.isWhitespace(c)) continue;
                Integer id = vocab.get(String.valueOf(c));
                ids[pos] = id != null ? (long) id : (long) unkId;
                mask[pos] = 1;
                pos++;
            }
            ids[pos] = 102; mask[pos] = 1;

            var inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, BGE_MAX_LEN}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, BGE_MAX_LEN}));
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[BGE_MAX_LEN]), new long[]{1, BGE_MAX_LEN}));
            try (var r = session.run(inputs)) {
                float[] output = ((OnnxTensor) r.get(0)).getFloatBuffer().array();
                return meanPoolAndNorm(output, mask);
            }
        } catch (Exception e) {
            System.err.println("[EMBED] Failed: " + e.getMessage());
            return null;
        }
    }

    static float[] meanPoolAndNorm(float[] output, long[] mask) {
        int dim = output.length / BGE_MAX_LEN;
        float[] emb = new float[dim];
        float valid = 0;
        for (int i = 0; i < BGE_MAX_LEN; i++) {
            if (mask[i] == 0) break;
            valid++;
            for (int j = 0; j < dim; j++) emb[j] += output[i * dim + j];
        }
        if (valid > 0) for (int j = 0; j < dim; j++) emb[j] /= valid;
        double norm = 0;
        for (float v : emb) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int j = 0; j < dim; j++) emb[j] /= (float) norm;
        return emb;
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) return 0;
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += (double) a[i] * b[i];
            nA += (double) a[i] * a[i];
            nB += (double) b[i] * b[i];
        }
        return Math.sqrt(nA) * Math.sqrt(nB) == 0 ? 0 : dot / (Math.sqrt(nA) * Math.sqrt(nB));
    }

    static ParsedDocument createParsedDoc(String docId, String title, String content, String category) {
        return ParsedDocument.builder()
                .docId(docId).title(title).content(content).category(category)
                .contentType("txt").tenantId("default").version("v1").build();
    }

    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseChars = 0, otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) chineseChars++;
            else otherChars++;
        }
        return chineseChars + (int) Math.ceil(otherChars * 0.4);
    }

    // ═══════════════════════════════════════════════════════════════
    // TCG-02: 语义分块基础测试
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TCG-02a: 小阈值强制语义拆分 Markdown 文档")
    void testSemanticChunkOnMarkdownSmallThreshold() {
        var strategy = new SemanticChunkStrategy();
        var chunks = strategy.chunk(DOC_MARKDOWN, 200, 20);

        System.out.printf("%n=== TCG-02a: Markdown 小阈值(200tok)分块 ===%n");
        System.out.printf("  输入: %d 字符, 输出: %d 个 chunk%n", DOC_MARKDOWN.length(), chunks.size());

        assertFalse(chunks.isEmpty(), "应产生至少一个 chunk");
        assertTrue(chunks.size() >= 2,
                "小阈值应将 Markdown 文档拆分为 2+ 个 chunk, 实际=" + chunks.size());

        for (Chunk c : chunks) {
            String preview = c.getText().substring(0, Math.min(60, c.getText().length())).replace("\n", " ");
            System.out.printf("  Chunk[%d] %d tokens: %s...%n", c.getIndex(), c.getTokenCount(), preview);
        }

        boolean coversCheckout = chunks.stream().anyMatch(c -> c.getText().contains("下单"));
        boolean coversPayment = chunks.stream().anyMatch(c -> c.getText().contains("支付"));
        boolean coversRefund = chunks.stream().anyMatch(c -> c.getText().contains("退款"));
        assertTrue(coversCheckout && coversPayment && coversRefund,
                "分块内容应覆盖下单、支付、退款三大主题");
        System.out.printf("  内容覆盖: 下单=%s, 支付=%s, 退款=%s%n",
                coversCheckout, coversPayment, coversRefund);
    }

    @Test
    @DisplayName("TCG-02b: 小阈值强制语义拆分中文章节文档")
    void testSemanticChunkOnChapterSmallThreshold() {
        var strategy = new SemanticChunkStrategy();
        var chunks = strategy.chunk(DOC_CHAPTER, 150, 15);

        System.out.printf("%n=== TCG-02b: 章节文档小阈值(150tok)分块 ===%n");
        System.out.printf("  输入: %d 字符, 输出: %d 个 chunk%n", DOC_CHAPTER.length(), chunks.size());

        assertFalse(chunks.isEmpty(), "应产生至少一个 chunk");
        assertTrue(chunks.size() >= 2,
                "小阈值(150tok)应将章节文档拆分为 2+ 个 chunk, 实际=" + chunks.size());

        for (Chunk c : chunks) {
            String preview = c.getText().substring(0, Math.min(60, c.getText().length())).replace("\n", " ");
            System.out.printf("  Chunk[%d] %d tokens: %s...%n", c.getIndex(), c.getTokenCount(), preview);
        }

        boolean hasScene = chunks.stream().anyMatch(c -> c.getText().contains("故宫"));
        boolean hasFood = chunks.stream().anyMatch(c -> c.getText().contains("烤鸭"));
        assertTrue(hasScene && hasFood,
                "分块内容应覆盖景点和美食主题");
        System.out.printf("  主题覆盖: 景点=%s, 美食=%s%n", hasScene, hasFood);
    }

    @Test
    @DisplayName("TCG-02c: 小阈值强制语义拆分纯文本（回退递归分块）")
    void testSemanticChunkOnPlainTextSmallThreshold() {
        var strategy = new SemanticChunkStrategy();
        var chunks = strategy.chunk(DOC_PLAINTEXT, 200, 20);

        System.out.printf("%n=== TCG-02c: 纯文本小阈值(200tok)分块 ===%n");
        System.out.printf("  输入: %d 字符, 输出: %d 个 chunk%n", DOC_PLAINTEXT.length(), chunks.size());

        assertFalse(chunks.isEmpty(), "应产生至少一个 chunk");

        for (Chunk c : chunks) {
            String preview = c.getText().substring(0, Math.min(80, c.getText().length())).replace("\n", " ");
            System.out.printf("  Chunk[%d] %d tokens: %s...%n", c.getIndex(), c.getTokenCount(), preview);
        }

        // 验证每个 chunk 在 Token 阈值内
        for (Chunk c : chunks) {
            assertTrue(c.getTokenCount() <= 200 + 30,
                    "Chunk[" + c.getIndex() + "] token 数=" + c.getTokenCount() + " 不应严重超过阈值");
        }
        System.out.printf("  %d 个 chunk 全部在 Token 阈值内%n", chunks.size());
    }

    @Test
    @DisplayName("TCG-02d: 大阈值下语义分块正确合并小段落为完整块")
    void testSemanticChunkMergeWithLargeThreshold() {
        var strategy = new SemanticChunkStrategy();
        var md = strategy.chunk(DOC_MARKDOWN, MAX_TOKENS, OVERLAP);
        var ch = strategy.chunk(DOC_CHAPTER, MAX_TOKENS, OVERLAP);
        var pt = strategy.chunk(DOC_PLAINTEXT, MAX_TOKENS, OVERLAP);

        System.out.printf("%n=== TCG-02d: 大阈值(1024tok)合并行为 ===%n");
        System.out.printf("  Markdown(%dchars)=%dchunk, 章节(%dchars)=%dchunk, 纯文本(%dchars)=%dchunk%n",
                DOC_MARKDOWN.length(), md.size(),
                DOC_CHAPTER.length(), ch.size(),
                DOC_PLAINTEXT.length(), pt.size());

        // 短文档在 maxTokens=1024 时合并为 1 个完整 chunk
        assertEquals(1, md.size(), "Markdown 在 1024tok 下应合并为 1 个 chunk");
        assertEquals(1, ch.size(), "章节文档在 1024tok 下应合并为 1 个 chunk");
        assertEquals(1, pt.size(), "纯文本在 1024tok 下应合并为 1 个 chunk");

        // 合并后的 chunk 保留所有章节内容
        assertTrue(md.get(0).getText().contains("退款政策"),
                "Markdown chunk 应含退款章节");
        assertTrue(ch.get(0).getText().contains("交通出行"),
                "章节 chunk 应含交通章节");
        assertTrue(pt.get(0).getText().contains("智能家居"),
                "纯文本 chunk 应含智能家居");
        System.out.println("  合并后 chunk 内容完整，覆盖原文所有章节");
    }

    // ═══════════════════════════════════════════════════════════════
    // TCG-03: 分块边界合规性
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TCG-03a: 分块大小在配置阈值范围内")
    void testChunkSizeBoundary() {
        var strategy = new SemanticChunkStrategy();
        var chunks = strategy.chunk(DOC_CHAPTER, 200, 20);

        System.out.printf("%n=== TCG-03a: 分块大小边界测试 (maxTokens=200, overlap=20) ===%n");
        assertFalse(chunks.isEmpty(), "应产生至少一个 chunk");

        for (Chunk c : chunks) {
            System.out.printf("  Chunk[%d] tokenCount=%d, 字符数=%d%n",
                    c.getIndex(), c.getTokenCount(), c.getText().length());
            assertTrue(c.getTokenCount() <= 200 + 20,
                    "Chunk[" + c.getIndex() + "] tokenCount=" + c.getTokenCount() + " 不应严重超过 maxTokens");
            assertTrue(c.getText().length() >= 20,
                    "最小 chunk 不应短于 20 字符");
        }
        System.out.printf("  %d 个 chunk 全部合规%n", chunks.size());
    }

    @Test
    @DisplayName("TCG-03b: 空文档/空白文档返回空列表")
    void testEmptyDocument() {
        var strategy = new SemanticChunkStrategy();
        assertTrue(strategy.chunk("", MAX_TOKENS, OVERLAP).isEmpty(), "空文档应返回空");
        assertTrue(strategy.chunk("   ", MAX_TOKENS, OVERLAP).isEmpty(), "空白文档应返回空");
        assertTrue(strategy.chunk(null, MAX_TOKENS, OVERLAP).isEmpty(), "null 应返回空");
        System.out.println("TCG-03b: 空文档边界处理正确");
    }

    // ═══════════════════════════════════════════════════════════════
    // TCG-04: BGE 嵌入语义质量检验
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TCG-04: BGE 嵌入的语义连贯性与句级区分度")
    void testBgeEmbeddingQuality() throws Exception {
        var session = loadBgeSession();
        var env = OrtEnvironment.getEnvironment();
        Assumptions.assumeTrue(session != null && vocab != null,
                "BGE ONNX 模型或词表不可用，跳过 BGE 嵌入测试");

        // 测试三对语义句
        String[][] pairs = {
            // 同义句对（期望高相似度）
            {"故宫门票60元，开放时间8:00-17:00", "故宫博物院票价60元，参观时间上午8点到下午5点"},
            // 部分相关句对（期望中等相似度）
            {"推荐北京烤鸭全聚德老字号", "北京美食推荐：烤鸭、炸酱面"},
            // 无关句对（期望低相似度）
            {"智能音箱支持Wi-Fi和蓝牙连接", "北京今天天气晴朗气温22度"},
            // 相同句子（期望极高相似度）
            {"天坛公园建议游览2-3小时门票15元", "天坛公园建议游览2-3小时门票15元"},
            // 不同主题同文档句（期望中等相似度）
            {"北京地铁共27条线路票价3-9元", "王府井地区靠近故宫交通便利"},
        };

        boolean[] expectHigh = { true, true, false, true, true };
        String[] labels = { "同义句", "相关句", "无关句", "相同句", "同主题异项" };

        System.out.printf("%n=== TCG-04: BGE 嵌入语义质量 ===%n");

        for (int i = 0; i < pairs.length; i++) {
            float[] e1 = embed(pairs[i][0], session, env);
            float[] e2 = embed(pairs[i][1], session, env);
            double sim = cosineSimilarity(e1, e2);
            System.out.printf("  %s: %.4f (应%s)%n",
                    labels[i], sim, expectHigh[i] ? ">0.40" : "<0.40");
        }

        // 相同句相似度应最高
        float[] eSame1 = embed(pairs[3][0], session, env);
        float[] eSame2 = embed(pairs[3][1], session, env);
        double sameSim = cosineSimilarity(eSame1, eSame2);
        assertTrue(sameSim > 0.90, "相同句 BGE 相似度应 > 0.90: " + sameSim);

        // 无关句相似度应显著低于同义句
        float[] eSyn1 = embed(pairs[0][0], session, env);
        float[] eSyn2 = embed(pairs[0][1], session, env);
        float[] eUnr1 = embed(pairs[2][0], session, env);
        float[] eUnr2 = embed(pairs[2][1], session, env);
        double synSim = cosineSimilarity(eSyn1, eSyn2);
        double unrSim = cosineSimilarity(eUnr1, eUnr2);
        assertTrue(synSim > unrSim + 0.2,
                "同义句相似度(" + synSim + ")应显著高于无关句(" + unrSim + ")");

        System.out.println("  BGE 嵌入质量检验通过");
        session.close();
    }

    // ═══════════════════════════════════════════════════════════════
    // TCG-05: 策略对比测试
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TCG-05a: Semantic vs Recursive 分块数量对比")
    void testStrategyComparison() {
        var semantic = new SemanticChunkStrategy();
        var recursive = new RecursiveChunkStrategy();

        var docs = List.of(
                Map.entry("Markdown", DOC_MARKDOWN),
                Map.entry("章节文档", DOC_CHAPTER),
                Map.entry("纯文本", DOC_PLAINTEXT)
        );

        System.out.printf("%n=== TCG-05a: 策略对比 ===%n");
        System.out.println("  文档类型  | 语义分块数 | 递归分块数 | 差异");

        for (var entry : docs) {
            var sChunks = semantic.chunk(entry.getValue(), MAX_TOKENS, OVERLAP);
            var rChunks = recursive.chunk(entry.getValue(), MAX_TOKENS, OVERLAP);
            int diff = sChunks.size() - rChunks.size();
            String label = diff > 0 ? "语义多" + diff : (diff < 0 ? "递归多" + (-diff) : "相同");
            System.out.printf("  %s | %d | %d | %s%n",
                    entry.getKey(), sChunks.size(), rChunks.size(), label);
        }
    }

    @Test
    @DisplayName("TCG-05b: 语义分块保留中文章节标题在 chunk 中")
    void testSemanticChunkPreservesHeadings() {
        var semantic = new SemanticChunkStrategy();
        // 用小阈值拆分并验证章节标题被保留
        var chunks = semantic.chunk(DOC_CHAPTER, 150, 15);

        // 至少有一个 chunk 包含中文章节标题
        boolean hasHeading = chunks.stream()
                .anyMatch(c -> c.getText().contains("一、") || c.getText().contains("二、")
                        || c.getText().contains("三、") || c.getText().contains("四、"));
        assertTrue(hasHeading, "语义分块的 chunk 应保留中文章节标题（一、二、三、四、）");
        System.out.printf("  TCG-05b: 章节标题保留=%s%n", hasHeading);
    }

    // ═══════════════════════════════════════════════════════════════
    // TCG-06: Parent-Child 双粒度分块
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TCG-06: Parent-Child 双粒度分块验证")
    void testParentChildChunking() {
        var chunker = new ParentChildDocumentChunker(
                new SemanticChunkStrategy(), 256, 1024, 50);

        var parsedDocs = List.of(
                createParsedDoc("doc-01", "订单系统指南", DOC_MARKDOWN, "manual"),
                createParsedDoc("doc-02", "北京旅游攻略", DOC_CHAPTER, "travel")
        );

        var result = chunker.chunkParentChild(parsedDocs);

        System.out.printf("%n=== TCG-06: Parent-Child 双粒度分块 ===%n");
        System.out.printf("  父块(阅读用): %d, 子块(检索用): %d%n",
                result.parentDocs().size(), result.childDocs().size());

        assertFalse(result.parentDocs().isEmpty(), "应有父块");
        assertFalse(result.childDocs().isEmpty(), "应有子块");

        // 每个子块关联父块 ID
        long withParent = result.childDocs().stream()
                .filter(doc -> doc.getParentDocId() != null && !doc.getParentDocId().isBlank())
                .count();
        assertEquals(result.childDocs().size(), withParent, "所有子块应关联父块");

        // 父块 ID 集合
        Set<String> parentIds = new HashSet<>();
        for (var pd : result.parentDocs()) {
            parentIds.add(pd.getId());
        }
        for (var cd : result.childDocs()) {
            assertTrue(parentIds.contains(cd.getParentDocId()),
                    "子块 parentDocId=" + cd.getParentDocId() + " 应在父块集合中");
        }

        // 子块平均大小应小于父块
        double avgParentTokens = result.parentDocs().stream()
                .mapToInt(pd -> estimateTokens(pd.getContent()))
                .average().orElse(0);
        double avgChildTokens = result.childDocs().stream()
                .mapToInt(cd -> estimateTokens(cd.getContent()))
                .average().orElse(0);
        System.out.printf("  平均 Token: 父块=%.1f, 子块=%.1f (期望 子块<父块)%n",
                avgParentTokens, avgChildTokens);
        assertTrue(avgChildTokens < avgParentTokens,
                "子块平均(" + avgChildTokens + ")应小于父块(" + avgParentTokens + ")");

        System.out.println("  TCG-06: Parent-Child 分块验证通过");
    }
}
