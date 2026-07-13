package com.example.smartassistant.common.embedding;

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

public class BgeEmbeddingModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BgeEmbeddingModel.class);
    /** 最大 Token 数（BGE-small/large 系列支持 512，之前 128 导致长文本被截断） */
    private static final int MAX_LEN = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Map<String, Integer> vocab;
    private final int embedDim;
    private final boolean available;

    public BgeEmbeddingModel(String modelPath) {
        this(modelPath, null);
    }

    public BgeEmbeddingModel(String modelPath, String vocabPath) {
        OrtEnvironment e = null;
        OrtSession s = null;
        Map<String, Integer> v = Collections.emptyMap();
        int d = 384;
        boolean a = false;
        try {
            v = loadVocab(vocabPath);
            if (v.isEmpty()) log.warn("[BGE] vocab not loaded");

            Path mp = Paths.get(modelPath);
            if (!Files.exists(mp)) throw new RuntimeException("Model not found: " + modelPath);

            e = OrtEnvironment.getEnvironment();
            var opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            s = e.createSession(modelPath, opts);

            d = detectDim(e, s);
            a = true;
            log.info("[BGE] Model loaded (dim={}, path={})", d, modelPath);
        } catch (Exception ex) {
            log.error("[BGE] Failed: {}", ex.getMessage());
        }
        env = e;
        session = s;
        vocab = v;
        embedDim = d;
        available = a;
    }

    private static Map<String, Integer> loadVocab(String vocabPath) {
        List<String> candidates = new ArrayList<>();
        if (vocabPath != null) candidates.add(vocabPath);
        candidates.add("models/tokenizer.json");
        candidates.add("src/main/resources/models/tokenizer.json");
        candidates.add("smart-assistant-common/src/main/resources/models/tokenizer.json");

        for (String cp : candidates) {
            Path p = Paths.get(cp);
            if (Files.exists(p)) {
                try {
                    var mapper = new ObjectMapper();
                    Map<String, Object> root = mapper.readValue(p.toFile(), new TypeReference<Map<String, Object>>() {});
                    Map<String, Object> modelNode = (Map<String, Object>) root.get("model");
                    if (modelNode != null && modelNode.get("vocab") instanceof Map) {
                        Map<String, Integer> result = new HashMap<>();
                        for (var entry : ((Map<String, Object>) modelNode.get("vocab")).entrySet()) {
                            result.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                        }
                        log.info("[BGE] vocab loaded ({} entries)", result.size());
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("[BGE] vocab parse failed: {}", e.getMessage());
                }
            }
        }
        return Collections.emptyMap();
    }

    private static int detectDim(OrtEnvironment env, OrtSession session) {
        try {
            long[] dummy = new long[MAX_LEN];
            dummy[0] = 101; dummy[1] = 102;
            long[] mask = new long[MAX_LEN]; mask[0] = 1; mask[1] = 1;
            var inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(dummy), new long[]{1, MAX_LEN}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, MAX_LEN}));
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[MAX_LEN]), new long[]{1, MAX_LEN}));
            try (var r = session.run(inputs)) {
                return ((OnnxTensor) r.get(0)).getFloatBuffer().array().length / MAX_LEN;
            }
        } catch (Exception e) {
            log.warn("[BGE] dim detection failed, using default 384");
            return 384;
        }
    }

    public float[] embedding(String text) {
        if (!available || text == null || text.isBlank()) return null;
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
        try {
            var inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, MAX_LEN}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, MAX_LEN}));
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[MAX_LEN]), new long[]{1, MAX_LEN}));
            try (var r = session.run(inputs)) {
                return meanPoolAndNorm(((OnnxTensor) r.get(0)).getFloatBuffer().array(), mask);
            }
        } catch (Exception e) {
            log.warn("[BGE] embedding failed: {}", e.getMessage());
            return null;
        }
    }

    private float[] meanPoolAndNorm(float[] output, long[] mask) {
        int dim = embedDim;
        int seqLen = output.length / dim;
        float[] emb = new float[dim];
        float valid = 0;
        for (int i = 0; i < seqLen && i < MAX_LEN; i++) {
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

    @Override
    public void close() {
        if (session != null) try { session.close(); } catch (Exception e) { log.debug("[BGE] 关闭 ONNX session: {}", e.getMessage()); }
    }

    public boolean isAvailable() { return available; }
    public int dimensions() { return embedDim; }
}
