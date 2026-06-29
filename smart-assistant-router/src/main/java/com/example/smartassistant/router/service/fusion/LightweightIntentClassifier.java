package com.example.smartassistant.router.service.fusion;

import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import com.example.smartassistant.router.service.skill.SkillDefinition;
import com.example.smartassistant.router.service.skill.SkillRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L3 轻量意图分类器（小模型路径）。
 * <p>
 * 复用 BGE 嵌入服务，通过预计算意图类中心向量 + 余弦相似度进行快速分类。
 * 覆盖中频模糊意图，推理延迟 5-15ms（BGE 嵌入耗时）。
 * </p>
 *
 * <p>意图类中心向量通过 {@link #loadIntentCentroids()} 初始化，
 * 可通过配置文件或 Nacos Config 热更新。</p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class LightweightIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LightweightIntentClassifier.class);

    /** 意图分类结果 */
    public record ClassifyResult(String intentTag, double confidence, String category) {}

    /** 意图中心向量：intentTag → 归一化的 BGE 中心向量 */
    private final Map<String, float[]> intentCentroids = new ConcurrentHashMap<>();

    /** 意图元数据：intentTag → {category, description} */
    private final Map<String, IntentMeta> intentMeta = new ConcurrentHashMap<>();

    private final BgeOnnxEmbeddingService embeddingService;
    private final SkillRepository skillRepository;

    public LightweightIntentClassifier(BgeOnnxEmbeddingService embeddingService,
                                       SkillRepository skillRepository) {
        this.embeddingService = embeddingService;
        this.skillRepository = skillRepository;
    }

    @PostConstruct
    public void init() {
        // 从 SkillRepository 加载意图中心向量
        var examples = skillRepository.getExamplesMap();
        for (var entry : examples.entrySet()) {
            String skillId = entry.getKey();
            List<String> skillExamples = entry.getValue();
            if (skillExamples.isEmpty()) continue;

            SkillDefinition def = skillRepository.get(skillId);
            String category = def != null ? def.category() : "unknown";
            String intentTag = def != null ? def.intentTag() : skillId;
            registerIntent(intentTag, category, skillExamples.toArray(new String[0]));
        }

        // 回退：如果技能仓库未定义示例，加载内置默认
        if (intentCentroids.isEmpty()) {
            log.warn("[IntentClassifier] SkillRepository 未提供示例，使用内置默认");
            loadHardcodedIntents();
        }

        log.info("[IntentClassifier] 初始化完成: intents={}", intentCentroids.size());
    }

    /**
     * 加载硬编码的意图中心向量（回退方案）。
     */
    private void loadHardcodedIntents() {
        registerIntent("退款申请", "order",
                "我要退款", "申请退款", "退货", "退钱", "不想要了", "退款处理", "取消订单退款");
        registerIntent("订单查询", "order",
                "查订单", "我的订单", "订单状态", "物流信息", "包裹到哪里了", "查看订单");
        registerIntent("取消订单", "order",
                "取消订单", "撤销订单", "不买了", "取消");
        registerIntent("商品查询", "product",
                "查商品", "商品信息", "价格查询", "有没有", "推荐商品", "性价比");
        registerIntent("库存查询", "product",
                "有没有货", "库存", "现货", "什么时候到货", "缺货");
        registerIntent("问候", "general",
                "你好", "您好", "在吗", "早上好", "下午好", "hello", "hi", "嗨");
        registerIntent("天气查询", "general",
                "天气", "下雨吗", "多少度", "气温", "明天天气", "天气预报");
        registerIntent("新闻热点", "general",
                "新闻", "热点", "今天有什么新闻", "热搜", "最新消息", "发生了什么");
        registerIntent("数学计算", "general",
                "计算", "等于多少", "加减乘除", "算一下", "公式");
        registerIntent("单位换算", "general",
                "换算", "转换", "多少厘米", "多少斤", "摄氏度", "华氏度");

        log.info("[IntentClassifier] 加载了 {} 个意图类", intentCentroids.size());
    }

    private void registerIntent(String intentTag, String category, String... examples) {
        IntentMeta meta = new IntentMeta(intentTag, category);
        intentMeta.put(intentTag, meta);

        // 计算示例的平均向量作为中心
        List<float[]> vectors = new ArrayList<>();
        for (String example : examples) {
            try {
                float[] vec = embeddingService.embed(example);
                if (vec != null) vectors.add(vec);
            } catch (Exception e) {
                log.warn("[IntentClassifier] 示例编码失败: intent={}, example={}", intentTag, example);
            }
        }

        if (!vectors.isEmpty()) {
            float[] centroid = averageVectors(vectors);
            intentCentroids.put(intentTag, normalize(centroid));
        }
    }

    /**
     * 对输入文本进行意图分类。
     *
     * @param text 用户输入
     * @return 分类结果（置信度最高的意图）；无可匹配时返回 null
     */
    public ClassifyResult classify(String text) {
        if (text == null || text.isBlank() || intentCentroids.isEmpty()) {
            return null;
        }

        try {
            float[] queryVec = embeddingService.embed(text);
            if (queryVec == null) return null;

            float[] normalizedQuery = normalize(queryVec);

            String bestIntent = null;
            double bestScore = -1;

            for (Map.Entry<String, float[]> entry : intentCentroids.entrySet()) {
                double score = cosineSimilarity(normalizedQuery, entry.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    bestIntent = entry.getKey();
                }
            }

            if (bestIntent == null || bestScore < 0.3) {
                return null; // 低于阈值，无法分类
            }

            IntentMeta meta = intentMeta.get(bestIntent);
            // 将余弦相似度映射到 0~1 置信度（截断低于 0.3 的）
            double confidence = Math.max(0, (bestScore - 0.3) / 0.7);

            return new ClassifyResult(bestIntent,
                    Math.min(1.0, confidence),
                    meta != null ? meta.category() : "unknown");

        } catch (Exception e) {
            log.warn("[IntentClassifier] 分类失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 向量工具 ====================

    private float[] averageVectors(List<float[]> vectors) {
        if (vectors.isEmpty()) return new float[0];
        float[] avg = new float[vectors.get(0).length];
        for (float[] vec : vectors) {
            for (int i = 0; i < vec.length; i++) avg[i] += vec[i];
        }
        for (int i = 0; i < avg.length; i++) avg[i] /= vectors.size();
        return avg;
    }

    private float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = (float) (vec[i] / norm);
        return result;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    /** 意图元数据 */
    public record IntentMeta(String intentTag, String category) {}

    /** 获取已注册的意图数量 */
    public int getIntentCount() { return intentCentroids.size(); }

    /** 获取所有已注册的意图标签 */
    public Set<String> getRegisteredIntents() { return intentCentroids.keySet(); }
}
