package com.example.smartassistant.router.service.cache;

import ai.onnxruntime.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.LongBuffer;
import java.util.*;

/**
 * BGE ONNX 嵌入服务 — 使用 BGE ONNX 模型。
 * <p>
 * 默认加载 classpath:models/bge-small-zh-v1.5.onnx，
 * 模型不存在时 isAvailable() 返回 false，系统降级使用 TF 向量。
 * 向量维度从 ONNX 模型输出中自动检测。
 */
@Service
public class BgeOnnxEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(BgeOnnxEmbeddingService.class);
    private static final int MAX_LEN = 128;

    private OrtEnvironment env;
    private OrtSession session;
    private volatile boolean available = false;
    private int embedDim = 384;
    private Map<String, Integer> vocab;

    @PostConstruct
    public void init() {
        byte[] modelBytes = null;
        try {
            // ⭐ 优先从文件系统加载（避免将 1.3GB 模型打入 JAR）
            java.io.File externalModel = new java.io.File("../models/bge-large-zh-v1.5.onnx");
            java.io.File externalVocab = new java.io.File("../models/tokenizer.json");
            if (externalModel.exists()) {
                log.info("[BGE] 从外部文件加载模型: {}", externalModel.getAbsolutePath());
                modelBytes = java.nio.file.Files.readAllBytes(externalModel.toPath());
            } else {
                ClassPathResource modelResource = new ClassPathResource("models/bge-small-zh-v1.5.onnx");
                if (!modelResource.exists()) {
                    log.warn("[BGE] 模型不存在: models/bge-small-zh-v1.5.onnx，请运行 convert-bge-to-onnx.py");
                    return;
                }
                try (var is = modelResource.getInputStream()) {
                    modelBytes = is.readAllBytes();
                }
            }
            // Load tokenizer vocab - also try external first
            if (externalVocab.exists()) {
                try (var is = new java.io.FileInputStream(externalVocab)) {
                    loadVocab(is);
                }
            } else {
                ClassPathResource vocabResource = new ClassPathResource("models/tokenizer.json");
                if (vocabResource.exists()) {
                    try (var is = vocabResource.getInputStream()) {
                        loadVocab(is);
                    }
                }
            }
            if (vocab == null || vocab.isEmpty()) {
                log.warn("[BGE] tokenizer.json 未找到或 vocab 解析失败");
                return;
            }
            log.info("[BGE] vocab 加载成功, 大小={}", vocab.size());

            env = OrtEnvironment.getEnvironment();
            var opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            session = env.createSession(modelBytes, opts);
            // Auto-detect embedding dimension by dummy inference
            try {
                long[] dummy = new long[MAX_LEN];
                dummy[0] = 101; dummy[1] = 102;
                long[] dm = new long[MAX_LEN]; dm[0] = 1; dm[1] = 1;
                long[] dt = new long[MAX_LEN];
                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(dummy), new long[]{1, MAX_LEN}));
                inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(dm), new long[]{1, MAX_LEN}));
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(dt), new long[]{1, MAX_LEN}));
                try (var r = session.run(inputs)) {
                    embedDim = ((OnnxTensor) r.get(0)).getFloatBuffer().array().length / MAX_LEN;
                }
            } catch (Exception ignored) {}
            available = true;
            log.info("[BGE] ONNX 模型加载成功 (dim={})", embedDim);
        } catch (OutOfMemoryError e) {
            log.warn("[BGE] 堆内存不足（{}MB），模型过大，跳过加载。Jaccard 降级可用。",
                    Runtime.getRuntime().maxMemory() / 1024 / 1024);
        } catch (Exception e) {
            log.warn("[BGE] 加载失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (session != null) try { session.close(); } catch (Exception ignored) {}
    }

    public boolean isAvailable() { return available; }

    /** 从输入流加载 tokenizer vocab */
    private void loadVocab(java.io.InputStream is) throws Exception {
        var mapper = new ObjectMapper();
        Map<String, Object> root = mapper.readValue(is, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> modelNode = (Map<String, Object>) root.get("model");
        if (modelNode != null) {
            Object v = modelNode.get("vocab");
            if (v instanceof Map) {
                vocab = new HashMap<>();
                for (var entry : ((Map<String, Object>) v).entrySet()) {
                    vocab.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                }
            }
        }
    }

    /** 将文本转为 embedDim 维归一化向量 */
    public float[] embed(String text) {
        if (!available || text == null || text.isBlank()) return null;
        try {
            long[] ids = tokenize(text);
            long[] mask = new long[MAX_LEN];
            long[] types = new long[MAX_LEN];
            Arrays.fill(mask, 1);
            for (int i = ids.length; i < MAX_LEN; i++) { ids[i] = 0; mask[i] = 0; }

            var t1 = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, MAX_LEN});
            var t2 = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, MAX_LEN});
            var t3 = OnnxTensor.createTensor(env, LongBuffer.wrap(types), new long[]{1, MAX_LEN});

            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", t1);
            inputs.put("attention_mask", t2);
            inputs.put("token_type_ids", t3);

            try (var result = session.run(inputs)) {
                float[] output = ((OnnxTensor) result.get(0)).getFloatBuffer().array();
                return meanPoolAndNorm(output, mask);
            }
        } catch (Exception e) {
            log.warn("[BGE] embed 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 使用 BERT vocab 进行 tokenize */
    private long[] tokenize(String text) {
        List<Long> tokens = new ArrayList<>();
        tokens.add(101L); // [CLS]
        int unkId = vocab.getOrDefault("[UNK]", 100);
        for (char c : text.toCharArray()) {
            if (tokens.size() >= MAX_LEN - 1) break;
            if (Character.isWhitespace(c)) continue;
            Integer id = vocab.get(String.valueOf(c));
            tokens.add(id != null ? (long) id : (long) unkId);
        }
        tokens.add(102L); // [SEP]
        long[] r = new long[MAX_LEN];
        for (int i = 0; i < tokens.size() && i < MAX_LEN; i++) r[i] = tokens.get(i);
        return r;
    }

    /** Mean pooling + L2 normalize */
    private float[] meanPoolAndNorm(float[] output, long[] mask) {
        int seqLen = output.length / embedDim;
        float[] emb = new float[embedDim];
        float valid = 0;
        for (int i = 0; i < seqLen && i < mask.length; i++) {
            if (mask[i] == 0) break;
            valid++;
            for (int j = 0; j < embedDim; j++) emb[j] += output[i * embedDim + j];
        }
        if (valid > 0) for (int j = 0; j < embedDim; j++) emb[j] /= valid;
        double norm = 0;
        for (float v : emb) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int j = 0; j < embedDim; j++) emb[j] /= (float) norm;
        return emb;
    }
}
