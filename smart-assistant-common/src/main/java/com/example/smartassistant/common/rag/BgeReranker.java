package com.example.smartassistant.common.rag;

import ai.onnxruntime.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * bge-reranker Cross-Encoder 重排序器——基于 ONNX Runtime。
 * <p>
 * 对向量检索 Top-K 结果进行第二次精排，(query, document) 拼接后过 Cross-Encoder，
 * 输出相关性分数。精度显著优于 Bi-Encoder 的余弦相似度。
 * </p>
 *
 * <p>
 * 模型来源：BAAI/bge-reranker-v2-m3（HuggingFace），需先导出为 ONNX 格式。
 * 导出脚本见 scripts/download_reranker.py。
 * </p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * BgeReranker reranker = new BgeReranker("models/bge-reranker-v2-m3.onnx", "models/tokenizer.json");
 * List<KnowledgeHit> reranked = reranker.rerank(hits, query, 5);
 * }</pre>
 */
public class BgeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(BgeReranker.class);

    private static final int MAX_LEN = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Map<String, Integer> vocab;
    private final boolean available;

    /** 输入/输出名称（由 ONNX 模型定义） */
    private final String inputIdsName;
    private final String attentionMaskName;
    private final String tokenTypeIdsName;
    private final String outputName;

    public BgeReranker(String modelPath, String vocabPath) {
        OrtEnvironment e = null;
        OrtSession s = null;
        Map<String, Integer> v = Collections.emptyMap();
        boolean a = false;
        String inId = "input_ids";
        String attn = "attention_mask";
        String tok = "token_type_ids";
        String out = "logits";

        try {
            v = loadVocab(vocabPath);
            Path mp = Paths.get(modelPath);
            if (!Files.exists(mp)) {
                log.warn("[BgeReranker] 模型文件未找到: {}", modelPath);
                throw new RuntimeException("Model not found: " + modelPath);
            }

            e = OrtEnvironment.getEnvironment();
            var opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            s = e.createSession(modelPath, opts);

            // 自动检测输入输出名称
            for (var entry : s.getInputInfo().entrySet()) {
                String name = entry.getKey();
                if (name.contains("input") || name.contains("input_ids")) inId = name;
                if (name.contains("attention") || name.contains("mask")) attn = name;
                if (name.contains("token") || name.contains("type")) tok = name;
            }
            for (var entry : s.getOutputInfo().entrySet()) {
                out = entry.getKey();
            }

            a = true;
            log.info("[BgeReranker] 模型加载成功: path={}, inputs={}", modelPath, s.getInputInfo().keySet());
        } catch (Exception ex) {
            log.warn("[BgeReranker] 加载失败: {}（重排序降级为恒等映射）", ex.getMessage());
        }

        this.env = e;
        this.session = s;
        this.vocab = v;
        this.available = a;
        this.inputIdsName = inId;
        this.attentionMaskName = attn;
        this.tokenTypeIdsName = tok;
        this.outputName = out;
    }

    @Override
    public List<KnowledgeHit> rerank(List<KnowledgeHit> hits, String query, int topK) {
        if (!available || hits.isEmpty()) {
            return hits.size() <= topK ? hits : hits.subList(0, topK);
        }

        long start = System.currentTimeMillis();

        // 对每个 (query, doc) pair 评分
        List<ScoredHit> scored = hits.parallelStream()
                .map(hit -> {
                    double score = scorePair(query, hit.getDocument().toEmbedText());
                    return new ScoredHit(hit, score);
                })
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - start;
        log.info("[BgeReranker] 重排序完成: {}→{} 条, cost={}ms",
                hits.size(), scored.size(), elapsed);

        return scored.stream()
                .map(sh -> sh.hit)
                .collect(Collectors.toList());
    }

    /**
     * 对 (query, document) 对进行 Cross-Encoder 评分。
     *
     * @param query  用户查询
     * @param docText 文档文本
     * @return 相关性分数（越高越相关，通常 -10 ~ 10 范围）
     */
    public double scorePair(String query, String docText) {
        if (!available) return 0;

        try {
            // 1. Tokenize: [CLS] query [SEP] doc [SEP]
            List<Long> inputIds = new ArrayList<>();
            List<Long> tokenTypeIds = new ArrayList<>();
            List<Long> attentionMask = new ArrayList<>();

            // CLS token
            inputIds.add(101L); tokenTypeIds.add(0L); attentionMask.add(1L);

            // Query tokens
            int[] queryTokens = encode(query);
            for (int t : queryTokens) {
                if (inputIds.size() >= MAX_LEN - 1) break;
                inputIds.add((long) t); tokenTypeIds.add(0L); attentionMask.add(1L);
            }

            // SEP
            inputIds.add(102L); tokenTypeIds.add(0L); attentionMask.add(1L);

            // Document tokens
            int[] docTokens = encode(docText);
            for (int t : docTokens) {
                if (inputIds.size() >= MAX_LEN - 1) break;
                inputIds.add((long) t); tokenTypeIds.add(1L); attentionMask.add(1L);
            }

            // SEP
            inputIds.add(102L); tokenTypeIds.add(1L); attentionMask.add(1L);

            // Pad to MAX_LEN
            while (inputIds.size() < MAX_LEN) {
                inputIds.add(0L); tokenTypeIds.add(0L); attentionMask.add(0L);
            }

            // 2. Create tensors
            long[] shape = {1, MAX_LEN};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(inputIds.stream().mapToLong(Long::longValue).toArray()), shape);
            OnnxTensor maskTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(attentionMask.stream().mapToLong(Long::longValue).toArray()), shape);
            OnnxTensor typeTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(tokenTypeIds.stream().mapToLong(Long::longValue).toArray()), shape);

            // 3. Run inference
            var inputs = Map.of(
                    inputIdsName, inputTensor,
                    attentionMaskName, maskTensor,
                    tokenTypeIdsName, typeTensor);
            var results = session.run(inputs);

            // 4. Extract score
            @SuppressWarnings("unchecked")
            var output = (OnnxTensor) results.get(outputName);
            float[][] scores = (float[][]) output.getValue();
            float score = scores[0][0];

            results.close();
            return score;

        } catch (Exception e) {
            log.warn("[BgeReranker] 评分异常: {}", e.getMessage());
            return 0;
        }
    }

    /** 是否可用 */
    public boolean isAvailable() { return available; }

    // ==================== Tokenizer ====================

    /** 简单 BPE tokenizer：查找 vocab 映射 */
    private int[] encode(String text) {
        if (text == null || text.isBlank()) return new int[0];
        String normalized = text.toLowerCase().trim();
        List<Integer> ids = new ArrayList<>();

        // 简单的贪心 tokenization：按字符 + vocab 最长匹配
        int i = 0;
        while (i < normalized.length() && ids.size() < MAX_LEN - 3) {
            int bestLen = 0;
            Integer bestId = null;

            for (int end = Math.min(i + 20, normalized.length()); end > i; end--) {
                String sub = normalized.substring(i, end);
                Integer id = vocab.get(sub);
                if (id != null) {
                    bestLen = end - i;
                    bestId = id;
                    break;
                }
            }

            if (bestId != null) {
                ids.add(bestId);
                i += bestLen;
            } else {
                // Unknown char → use [UNK] (100)
                ids.add(100);
                i++;
            }
        }

        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 加载 vocab JSON 文件 */
    private Map<String, Integer> loadVocab(String vocabPath) throws Exception {
        if (vocabPath == null) return Collections.emptyMap();
        Path path = Paths.get(vocabPath);
        if (!Files.exists(path)) return Collections.emptyMap();
        String json = Files.readString(path);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, new TypeReference<Map<String, Integer>>() {});
    }

    /** 关闭资源 */
    public void close() throws Exception {
        if (session != null) session.close();
        // env is shared (OrtEnvironment.getEnvironment()) — don't close
    }

    private static class ScoredHit {
        final KnowledgeHit hit;
        final double score;
        ScoredHit(KnowledgeHit hit, double score) { this.hit = hit; this.score = score; }
    }
}
