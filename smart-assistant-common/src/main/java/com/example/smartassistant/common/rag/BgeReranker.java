package com.example.smartassistant.common.rag;

import ai.onnxruntime.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
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
 * <p><strong>⚠️ 实验性功能</strong>：当前 tokenizer 为自定义贪心 BPE 实现，
 * 与 HuggingFace transformers 的标准 SentencePiece tokenizer 存在差异，
 * 可能影响重排质量。建议在正式上线前通过对抗式测试集验证效果。
 * 默认通过 {@code app.rag.reranker.enabled=false} 关闭。
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
 * SafeReranker safe = new SafeReranker(reranker);
 * List<KnowledgeHit> reranked = safe.rerank(hits, query, 5);
 * }</pre>
 */
public class BgeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(BgeReranker.class);

    private static final int MAX_LEN = 512;

    /** XLM-RoBERTa 特殊 token ID（与 BERT 不同） */
    private static final long CLS_ID = 0;
    private static final long SEP_ID = 2;
    private static final long PAD_ID = 1;
    private static final int UNK_ID = 3;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Map<String, Integer> vocab;
    private final boolean available;

    /** 输入/输出名称（由 ONNX 模型定义） */
    private final String inputIdsName;
    private final String attentionMaskName;
    @Nullable
    private final String tokenTypeIdsName; // XLM-RoBERTa 模型无此输入
    private final String outputName;

    public BgeReranker(String modelPath, String vocabPath) {
        OrtEnvironment e = null;
        OrtSession s = null;
        Map<String, Integer> v = Collections.emptyMap();
        boolean a = false;
        String inId = "input_ids";
        String attn = "attention_mask";
        String tok = null; // XLM-RoBERTa 可能无 token_type_ids
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
            Set<String> inputNames = s.getInputInfo().keySet();
            for (var entry : s.getInputInfo().entrySet()) {
                String name = entry.getKey();
                if (name.contains("input") || name.contains("input_ids")) inId = name;
                if (name.contains("attention") || name.contains("mask")) attn = name;
                if ((name.contains("token") || name.contains("type")) && !name.contains("input")) tok = name;
            }

            // 如果模型没有 token_type_ids 输入，标记为 null
            if (tok != null && !inputNames.contains(tok)) {
                tok = null;
            }

            for (var entry : s.getOutputInfo().entrySet()) {
                out = entry.getKey();
            }

            a = true;
            log.info("[BgeReranker] 模型加载成功: path={}, inputs={}, hasTokenTypeIds={}",
                    modelPath, inputNames, tok != null);
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

        // 对每个 (query, doc) pair 评分（使用 stream 而非 parallelStream，
        // 因为 ONNX Runtime session.run() 非线程安全）
        List<ScoredHit> scored = hits.stream()
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
            // XLM-RoBERTa 格式：CLS=0, SEP=2, PAD=1, UNK=3
            List<Long> inputIds = new ArrayList<>();
            List<Long> tokenTypeIds = new ArrayList<>();
            List<Long> attentionMask = new ArrayList<>();

            // CLS token (XLM-RoBERTa: 0)
            inputIds.add(CLS_ID); tokenTypeIds.add(0L); attentionMask.add(1L);

            // Query tokens
            int[] queryTokens = encode(query);
            for (int t : queryTokens) {
                if (inputIds.size() >= MAX_LEN - 1) break;
                inputIds.add((long) t); tokenTypeIds.add(0L); attentionMask.add(1L);
            }

            // SEP (XLM-RoBERTa: 2)
            inputIds.add(SEP_ID); tokenTypeIds.add(0L); attentionMask.add(1L);

            // Document tokens
            int[] docTokens = encode(docText);
            for (int t : docTokens) {
                if (inputIds.size() >= MAX_LEN - 1) break;
                inputIds.add((long) t); tokenTypeIds.add(1L); attentionMask.add(1L);
            }

            // SEP
            inputIds.add(SEP_ID); tokenTypeIds.add(1L); attentionMask.add(1L);

            // Pad to MAX_LEN (XLM-RoBERTa PAD: 1)
            while (inputIds.size() < MAX_LEN) {
                inputIds.add(PAD_ID); tokenTypeIds.add(0L); attentionMask.add(0L);
            }

            // 2. Create tensors
            long[] shape = {1, MAX_LEN};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(inputIds.stream().mapToLong(Long::longValue).toArray()), shape);
            OnnxTensor maskTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(attentionMask.stream().mapToLong(Long::longValue).toArray()), shape);

            // 3. Build inputs map（动态：可能包含或不含 token_type_ids）
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(inputIdsName, inputTensor);
            inputs.put(attentionMaskName, maskTensor);
            if (tokenTypeIdsName != null) {
                OnnxTensor typeTensor = OnnxTensor.createTensor(env,
                        LongBuffer.wrap(tokenTypeIds.stream().mapToLong(Long::longValue).toArray()), shape);
                inputs.put(tokenTypeIdsName, typeTensor);
            }

            var results = session.run(inputs);

            // 4. Extract score（ONNX Runtime 1.26+ 返回 Optional<OnnxValue>）
            var outputOptional = results.get(outputName);
            if (outputOptional.isEmpty()) {
                log.warn("[BgeReranker] 输出为空: {}", outputName);
                return 0;
            }
            OnnxTensor output = (OnnxTensor) outputOptional.get();
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
                // Unknown char → use [UNK] (XLM-RoBERTa: 3)
                ids.add(UNK_ID);
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
