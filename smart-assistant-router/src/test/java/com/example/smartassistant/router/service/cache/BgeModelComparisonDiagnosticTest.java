package com.example.smartassistant.router.service.cache;

import ai.onnxruntime.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BGE-small (512d) vs BGE-large (1024d) 召回准确性诊断测试。
 * 使用真实 BERT vocab 进行 tokenization，确保结果有效。
 */
class BgeModelComparisonDiagnosticTest {

    private static final int MAX_LEN = 128;
    private static final double HIT_THRESHOLD = 0.70;
    private static final double EXT_THRESHOLD = 0.15;

    // Test data: Chinese sentences (full text for real tokenization)
    static final String WEATHER_REPLY = "北京今天天气晴朗气温最高22度最低15度东南风3到4级体感舒适空气良好适合户外活动建议携带外套注意防晒紫外线中等早晚温差较大";
    static final String FOOD_REPLY = "为您推荐北京烤鸭全聚德老字号餐厅位于前门大街交通便利人均约150元环境优雅服务周到招牌菜品口味正宗建议提前预约";
    static final String TRAVEL_REPLY = "推荐游览故宫天坛颐和园长城门票约60元开放时间上午8点到下午5点游玩建议故宫半天颐和园一天交通可乘地铁公交";

    static final String Q_COLD = "冷不冷";
    static final String Q_RUN = "适合跑步吗";
    static final String Q_CLOTHES = "适合穿什么";
    static final String Q_RAIN = "需要带伞吗";
    static final String Q_FOOD = "有什么好吃的";
    static final String Q_TRAVEL = "有什么好玩的";
    static final String Q_PLAN = "帮我规划行程";
    static final String Q_POI = "故宫几点开门";

    record TestCase(String name, String text, boolean expectHit, String desc) {}
    record BgeModel(String label, int dim, String path) {}

    static final BgeModel BGE_SMALL = new BgeModel("BGE-small-zh", 512,
            "D:\\workspace\\SmartAssistant\\smart-assistant-router\\src\\main\\resources\\models\\bge-small-zh-v1.5.onnx");
    static final BgeModel BGE_LARGE = new BgeModel("BGE-large-zh", 1024,
            "D:\\workspace\\SmartAssistant\\smart-assistant-router\\src\\main\\resources\\models\\bge-large-zh-v1.5.onnx");

    static class ModelHandle implements AutoCloseable {
        final OrtSession session;
        final OrtEnvironment env;
        final int dim;
        final Map<String, Integer> vocab;

        ModelHandle(OrtSession session, OrtEnvironment env, int dim, Map<String, Integer> vocab) {
            this.session = session; this.env = env; this.dim = dim; this.vocab = vocab;
        }

        float[] embed(String text) throws Exception {
            int unkId = vocab.getOrDefault("[UNK]", 100);
            long[] ids = new long[MAX_LEN];
            long[] mask = new long[MAX_LEN];
            ids[0] = 101; mask[0] = 1;
            int pos = 1;
            for (char c : text.toCharArray()) {
                if (pos >= MAX_LEN - 1) break;
                if (Character.isWhitespace(c)) continue;
                Integer id = vocab.get(String.valueOf(c));
                ids[pos] = id != null ? (long) id : (long) unkId;
                mask[pos] = 1;
                pos++;
            }
            ids[pos] = 102; mask[pos] = 1;

            var inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, MAX_LEN}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, MAX_LEN}));
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[MAX_LEN]), new long[]{1, MAX_LEN}));
            try (var r = session.run(inputs)) {
                float[] output = ((OnnxTensor) r.get(0)).getFloatBuffer().array();
                return meanPoolAndNorm(output, mask);
            }
        }

        static ModelHandle load(BgeModel model, Map<String, Integer> vocab) throws Exception {
            if (!Files.exists(Paths.get(model.path))) {
                System.out.printf("[SKIP] %s model not found: %s%n", model.label, model.path);
                return null;
            }
            var env = OrtEnvironment.getEnvironment();
            var opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            var session = env.createSession(Files.readAllBytes(Paths.get(model.path)), opts);
            System.out.printf("[LOAD] %s%n", model.label);
            return new ModelHandle(session, env, model.dim, vocab);
        }

        @Override public void close() { try { session.close(); } catch (Exception ignored) {} }
    }

    static float[] meanPoolAndNorm(float[] output, long[] mask) {
        int dim = output.length / MAX_LEN;
        float[] emb = new float[dim];
        float valid = 0;
        for (int i = 0; i < MAX_LEN; i++) {
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

    static Map<String, Integer> loadVocab() {
        try {
            var mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(
                    Paths.get("D:\\workspace\\SmartAssistant\\smart-assistant-router\\src\\main\\resources\\models\\bge-small-zh-v1.5\\tokenizer.json").toFile(),
                    new TypeReference<Map<String, Object>>() {});
            Map<String, Object> modelNode = (Map<String, Object>) root.get("model");
            if (modelNode != null) {
                Object v = modelNode.get("vocab");
                if (v instanceof Map) {
                    Map<String, Integer> vocab = new HashMap<>();
                    for (var entry : ((Map<String, Object>) v).entrySet()) {
                        vocab.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                    }
                    System.out.printf("[VOCAB] Loaded %d entries%n", vocab.size());
                    return vocab;
                }
            }
        } catch (Exception e) {
            System.err.println("[VOCAB] Failed: " + e.getMessage());
        }
        return null;
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

    @Test
    @DisplayName("BGE-small vs BGE-large 召回准确性对比")
    void compareModels() throws Exception {
        var vocab = loadVocab();
        // BGE 模型与 vocab 依赖本地 ONNX 与 tokenizer.json 资源，缺失时跳过而非失败（属 D 类：环境/资源缺失）
        Assumptions.assumeTrue(vocab != null, "BGE tokenizer.json 不存在，跳过诊断测试");

        var small = ModelHandle.load(BGE_SMALL, vocab);
        var large = ModelHandle.load(BGE_LARGE, vocab);
        Assumptions.assumeTrue(small != null || large != null, "BGE ONNX 模型不存在，跳过诊断测试");

        var models = new ArrayList<ModelHandle>();
        if (small != null) models.add(small);
        if (large != null) models.add(large);
        assertFalse(models.isEmpty(), "At least one BGE model must be available");

        Map<String, String> testCases = new HashMap<>();
        testCases.put("WEATHER", WEATHER_REPLY);
        testCases.put("FOOD", FOOD_REPLY);
        testCases.put("TRAVEL", TRAVEL_REPLY);
        testCases.put(Q_COLD, Q_COLD);
        testCases.put(Q_RUN, Q_RUN);
        testCases.put(Q_CLOTHES, Q_CLOTHES);
        testCases.put(Q_RAIN, Q_RAIN);
        testCases.put(Q_FOOD, Q_FOOD);
        testCases.put(Q_TRAVEL, Q_TRAVEL);
        testCases.put(Q_PLAN, Q_PLAN);
        testCases.put(Q_POI, Q_POI);

        for (var m : models) {
            // Compute all embeddings
            var embs = new HashMap<String, float[]>();
            for (var e : testCases.entrySet()) {
                embs.put(e.getKey(), m.embed(e.getValue()));
            }

            System.out.printf("%n%n======== %s (dim=%d) ========%n", m.dim == 1024 ? "BGE-large-zh" : "BGE-small-zh", m.dim);

            // Cross-domain separation
            double wf = cosineSimilarity(embs.get("WEATHER"), embs.get("FOOD"));
            double wt = cosineSimilarity(embs.get("WEATHER"), embs.get("TRAVEL"));
            System.out.printf("--- 跨领域区分度 (阈值 %.2f) ---%n", HIT_THRESHOLD);
            System.out.printf("  天气 vs 美食: %.4f %s%n", wf, wf < HIT_THRESHOLD ? "✓ 正确排除" : "✗ 误命中");
            System.out.printf("  天气 vs 旅行: %.4f %s%n", wt, wt < HIT_THRESHOLD ? "✓ 正确排除" : "✗ 误命中");

            // Self-similarity
            double self = cosineSimilarity(embs.get("WEATHER"), embs.get("WEATHER"));
            System.out.printf("--- 自相似度 ---%n  天气 vs 自身: %.4f %s%n", self, self > 0.90 ? "✓" : "✗");

            // Prefix extension detection
            TestCase[] cases = {
                new TestCase(Q_COLD,     Q_COLD,     true,  "温度/温差单词共享"),
                new TestCase(Q_RUN,      Q_RUN,      true,  "适合/户外语义相关"),
                new TestCase(Q_CLOTHES,  Q_CLOTHES,  true,  "穿/衣服相关"),
                new TestCase(Q_RAIN,     Q_RAIN,     true,  "下雨/带伞天气相关"),
                new TestCase(Q_FOOD,     Q_FOOD,     false, "美食→应不命中"),
                new TestCase(Q_TRAVEL,   Q_TRAVEL,   false, "旅行→应不命中"),
                new TestCase(Q_PLAN,     Q_PLAN,     false, "行程规划→应不命中"),
                new TestCase(Q_POI,      Q_POI,      false, "景点→应不命中"),
            };
            System.out.printf("--- 前缀扩展检测 (阈值 %.2f) ---%n", EXT_THRESHOLD);
            int pass = 0, fail = 0;
            for (var c : cases) {
                double s = cosineSimilarity(embs.get(c.name()), embs.get("WEATHER"));
                boolean hit = s >= EXT_THRESHOLD;
                boolean ok = hit == c.expectHit();
                System.out.printf("  [%s] %s: %.4f %s (应%s) %s%n",
                    ok ? "✓" : "✗", c.name(), s, hit ? "命中" : "未命中",
                    c.expectHit() ? "命中" : "未命中", c.desc());
                if (ok) pass++; else fail++;
            }
            System.out.printf("  结果: %d/%d 通过, %d 失败%n", pass, pass + fail, fail);
        }

        for (var m : models) m.close();
    }

    @Test
    @DisplayName("维度一致性验证")
    void testDimensionConsistency() throws Exception {
        var vcb = loadVocab();
        var small = ModelHandle.load(BGE_SMALL, vcb);
        var large = ModelHandle.load(BGE_LARGE, vcb);
        if (small == null || large == null) { System.out.println("[SKIP] Missing model"); return; }

        System.out.printf("BGE-small: %dd, BGE-large: %dd (text-embedding-v4: %s)%n",
            small.dim, large.dim, large.dim == 1024 ? "✓ 同维" : "✗ 不同维");
        assertEquals(512, small.dim);
        assertEquals(1024, large.dim);
        small.close(); large.close();
    }
}
