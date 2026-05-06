package com.example.smartassistant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义分块器
 * <p>
 * 按语义边界切分文本，而非简单按字数切分。
 * 算法：
 * 1. 将文本拆分为句子
 * 2. 计算相邻句子的 embedding 相似度
 * 3. 相似度下降处（话题变化）切分
 * </p>
 */
@Slf4j
@Component
public class SemanticChunker {

    private final EmbeddingService embeddingService;

    // 中文句子分割正则：句号、问号、感叹号、分号、冒号、换行
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
            "[^。！？；\n]+[。！？；\n]");

    // 最大内容长度（防止 OOM）
    private static final int MAX_CONTENT_LENGTH = 50000;

    // ==================== 可配置参数 ====================

    /** 语义相似度阈值：低于此值认为话题变化，进行切分 */
    @Value("${travel.rag.chunk.semantic-threshold:0.45}")
    private double similarityThreshold = 0.45;

    /** 最小分块长度（字符），低于此值尝试合并 */
    @Value("${travel.rag.chunk.min-chunk-size:80}")
    private int minChunkSize = 80;

    /** 最大分块长度（字符），超过此值强制切分 */
    @Value("${travel.rag.chunk.max-chunk-size:800}")
    private int maxChunkSize = 800;

    /** 兜底硬切长度（单个句子过长时使用） */
    private static final int FALLBACK_CHUNK_SIZE = 300;

    /** 短句子跳过 embedding 的长度阈值 */
    private static final int SHORT_SENTENCE_THRESHOLD = 10;

    public SemanticChunker(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * 设置配置参数（从 application.yml 注入）
     */
    public void configure(double similarityThreshold, int minChunkSize, int maxChunkSize) {
        this.similarityThreshold = similarityThreshold;
        this.minChunkSize = minChunkSize;
        this.maxChunkSize = maxChunkSize;
        log.info("[SemanticChunker] 配置: threshold={}, min={}, max={}",
                similarityThreshold, minChunkSize, maxChunkSize);
    }

    /**
     * 语义分块入口
     * @param text 输入文本
     * @return 语义块列表
     */
    public List<String> split(String text) {
        log.info("[SemanticChunker] split: textLength={}", text != null ? text.length() : "null");

        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 限制内容长度
        String content = text;
        if (text.length() > MAX_CONTENT_LENGTH) {
            log.warn("[SemanticChunker] 内容过长({}), 截断至{}", text.length(), MAX_CONTENT_LENGTH);
            content = text.substring(0, MAX_CONTENT_LENGTH);
        }

        // 1. 拆分为句子
        List<String> sentences = splitSentences(content);
        log.info("[SemanticChunker] 句子数: {}", sentences.size());

        // 短文本直接返回
        if (sentences.size() <= 1) {
            return sentences.isEmpty() ? List.of(content) : sentences;
        }

        // 2. 计算句子向量和相似度
        List<float[]> embeddings = computeSentenceEmbeddings(sentences);
        if (embeddings.isEmpty()) {
            // Embedding 失败，回退到固定分块
            log.warn("[SemanticChunker] Embedding 失败，回退到固定分块");
            return fallbackSplit(content);
        }

        double[] similarities = computeAdjacentSimilarities(embeddings);
        log.info("[SemanticChunker] 相似度计算完成: {} 个间隔", similarities.length);

        // 3. 根据相似度合并句子为 chunk
        List<String> chunks = mergeSentences(sentences, similarities);
        log.info("[SemanticChunker] 语义分块完成: {} 个 chunk", chunks.size());

        return chunks;
    }

    // ==================== 句子拆分 ====================

    /**
     * 将文本拆分为句子
     */
    List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(text);

        int lastEnd = 0;
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            lastEnd = matcher.end();
        }

        // 处理末尾没有标点的剩余文本
        String remaining = text.substring(lastEnd).trim();
        if (!remaining.isEmpty()) {
            sentences.add(remaining);
        }

        return sentences;
    }

    // ==================== Embedding 与相似度 ====================

    /**
     * 计算所有句子的向量
     * 优化：跳过过短的句子（用相邻句子的向量替代）
     */
    private List<float[]> computeSentenceEmbeddings(List<String> sentences) {
        List<float[]> embeddings = new ArrayList<>(sentences.size());

        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);

            if (sentence.length() < SHORT_SENTENCE_THRESHOLD) {
                // 短句子：跳过 embedding，设为 null 等待后续填充
                embeddings.add(null);
                continue;
            }

            try {
                float[] vector = embeddingService.embed(sentence);
                embeddings.add(vector);
            } catch (Exception e) {
                log.warn("[SemanticChunker] 句子 {} embedding 失败: {}", i, e.getMessage());
                embeddings.add(null);
            }
        }

        // 填充短句子的向量：用前后句的平均
        fillShortSentenceEmbeddings(embeddings);

        // 如果仍有 null（首尾句失败），尝试用相邻有效向量
        for (int i = 0; i < embeddings.size(); i++) {
            if (embeddings.get(i) == null) {
                // 向前找最近的有效向量
                for (int j = 1; j < embeddings.size(); j++) {
                    if (i - j >= 0 && embeddings.get(i - j) != null) {
                        embeddings.set(i, embeddings.get(i - j));
                        break;
                    }
                    if (i + j < embeddings.size() && embeddings.get(i + j) != null) {
                        embeddings.set(i, embeddings.get(i + j));
                        break;
                    }
                }
            }
        }

        return embeddings;
    }

    /**
     * 填充短句子的向量：用前后句的均值
     */
    private void fillShortSentenceEmbeddings(List<float[]> embeddings) {
        for (int i = 0; i < embeddings.size(); i++) {
            if (embeddings.get(i) != null) continue;

            float[] prev = (i > 0) ? embeddings.get(i - 1) : null;
            float[] next = (i + 1 < embeddings.size()) ? embeddings.get(i + 1) : null;

            if (prev != null && next != null) {
                // 前后句平均
                float[] avg = new float[prev.length];
                for (int j = 0; j < prev.length; j++) {
                    avg[j] = (prev[j] + next[j]) / 2.0f;
                }
                embeddings.set(i, avg);
            } else if (prev != null) {
                embeddings.set(i, prev);
            } else if (next != null) {
                embeddings.set(i, next);
            }
        }
    }

    /**
     * 计算相邻句子对的余弦相似度
     */
    double[] computeAdjacentSimilarities(List<float[]> embeddings) {
        int n = embeddings.size();
        double[] similarities = new double[n - 1];

        for (int i = 0; i < n - 1; i++) {
            float[] v1 = embeddings.get(i);
            float[] v2 = embeddings.get(i + 1);

            if (v1 == null || v2 == null) {
                similarities[i] = 0.5; // 未知时取中间值
                continue;
            }

            similarities[i] = cosineSimilarity(v1, v2);
        }

        return similarities;
    }

    /**
     * 余弦相似度
     */
    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1.length != v2.length) return 0;

        double dot = 0, norm1 = 0, norm2 = 0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        double denom = Math.sqrt(norm1) * Math.sqrt(norm2);
        return denom == 0 ? 0 : dot / denom;
    }

    // ==================== 合并策略 ====================

    /**
     * 根据相似度将句子合并为语义块
     */
    private List<String> mergeSentences(List<String> sentences, double[] similarities) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);

            // 是否需要在当前句子后切分？
            boolean shouldSplit = false;
            if (i > 0 && i - 1 < similarities.length) {
                double sim = similarities[i - 1];

                // 条件1：相似度低于阈值 → 话题变化
                // 条件2：当前 chunk 已达到最大长度
                // 条件3：当前 chunk 超过最小长度（避免 chunk 太短）
                shouldSplit = (sim < similarityThreshold)
                        && currentChunk.length() >= minChunkSize;

                // 如果 chunk 达到最大长度，也要强制切分
                if (currentChunk.length() + sentence.length() > maxChunkSize
                        && currentChunk.length() >= minChunkSize) {
                    shouldSplit = true;
                }
            }

            // 执行切分
            if (shouldSplit) {
                String chunk = currentChunk.toString().trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                currentChunk = new StringBuilder();
            }

            currentChunk.append(sentence);
        }

        // 最后一个 chunk
        String lastChunk = currentChunk.toString().trim();
        if (!lastChunk.isEmpty()) {
            chunks.add(lastChunk);
        }

        // 合并过短的 chunk（可能出现在文档开头或结尾）
        return mergeShortChunks(chunks);
    }

    /**
     * 合并过短的 chunk（低于 minChunkSize 的尝试合入相邻 chunk）
     */
    private List<String> mergeShortChunks(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String chunk : chunks) {
            buffer.append(chunk);
            // 如果缓冲区达到最小长度，或这是最后一个 chunk
            if (buffer.length() >= minChunkSize) {
                result.add(buffer.toString().trim());
                buffer = new StringBuilder();
            }
        }

        // 处理剩余内容
        if (!buffer.isEmpty()) {
            if (!result.isEmpty()) {
                // 合并到最后 chunk
                String last = result.remove(result.size() - 1);
                result.add((last + buffer.toString()).trim());
            } else {
                result.add(buffer.toString().trim());
            }
        }

        return result;
    }

    // ==================== 兜底方案 ====================

    /**
     * 回退方案：固定长度分块（与原逻辑一致）
     */
    private List<String> fallbackSplit(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + FALLBACK_CHUNK_SIZE, text.length());

            // 尽量在句子边界切分
            if (end < text.length()) {
                int boundary = Math.max(
                        Math.max(text.lastIndexOf("。", end), text.lastIndexOf("\n", end)),
                        text.lastIndexOf("！", end));
                if (boundary > start + FALLBACK_CHUNK_SIZE / 2) {
                    end = boundary + 1;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = end;
        }

        return chunks;
    }
}
