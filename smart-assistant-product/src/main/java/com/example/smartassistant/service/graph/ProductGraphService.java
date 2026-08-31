package com.example.smartassistant.service.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P3 商品知识图谱服务。
 * <p>
 * 使用内存图引擎（邻接表）构建商品间关系图谱，支撑"同类商品推荐"、"替代品查询"、"配件推荐"等
 * 全局查询场景。作为 ProductRagService 的第 5 条检索路径（Graph 检索）。
 * </p>
 *
 * <p>关系类型：</p>
 * <ul>
 *   <li>{@code SAME_CATEGORY} — 同类商品（如 iPhone 15 Pro ↔ iPhone 16）</li>
 *   <li>{@code ALTERNATIVE} — 替代品（如 AirPods Pro ↔ Sony WF-1000XM5）</li>
 *   <li>{@code ACCESSORY} — 配件（如 iPhone 15 Pro → AirPods Pro）</li>
 *   <li>{@code UPGRADE} — 升级款（如 MacBook Air M3 升级款）</li>
 * </ul>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class ProductGraphService {

    private static final Logger log = LoggerFactory.getLogger(ProductGraphService.class);

    // ==================== 枚举 ====================

    /** 商品间关系类型 */
    public enum RelationType {
        /** 同类商品（同品牌同品类） */
        SAME_CATEGORY,
        /** 替代品（功能相近但不同品牌） */
        ALTERNATIVE,
        /** 配件（配套使用） */
        ACCESSORY,
        /** 升级款（新版本） */
        UPGRADE,
        /** 互补品（搭配使用更佳） */
        COMPLEMENT
    }

    /** 图查询结果 */
    public static class GraphQueryResult {
        private final String productCode;
        private final String productName;
        private final double relevanceScore;
        private final RelationType relationType;

        public GraphQueryResult(String productCode, String productName,
                                double relevanceScore, RelationType relationType) {
            this.productCode = productCode;
            this.productName = productName;
            this.relevanceScore = relevanceScore;
            this.relationType = relationType;
        }

        public String getProductCode() { return productCode; }
        public String getProductName() { return productName; }
        public double getRelevanceScore() { return relevanceScore; }
        public RelationType getRelationType() { return relationType; }
    }

    // ==================== 图数据 ====================

    /** 商品名称缓存（code → name） */
    private final Map<String, String> productNames = new ConcurrentHashMap<>();

    /** 邻接表：商品 code → List<(关联商品 code, 关系类型, 权重)> */
    private final Map<String, List<Edge>> adjacencyList = new ConcurrentHashMap<>();

    /** 商品 → 商品分类映射 */
    private final Map<String, String> productCategory = new ConcurrentHashMap<>();

    /** 商品 → 品牌映射 */
    private final Map<String, String> productBrand = new ConcurrentHashMap<>();

    /** 边内部类 */
    private record Edge(String targetCode, RelationType type, double weight) {}

    // ==================== 初始化 ====================

    private final ProductGraphSource graphSource;

    public ProductGraphService(ProductGraphSource graphSource) {
        this.graphSource = graphSource;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${product.graph.refresh-ms:60000}")
    public synchronized void refresh() {
        ProductGraphSource.GraphSnapshot snapshot = graphSource.load();
        Map<String, String> nextNames = new HashMap<>();
        Map<String, String> nextCategories = new HashMap<>();
        Map<String, String> nextBrands = new HashMap<>();
        Map<String, List<Edge>> nextAdjacency = new HashMap<>();
        for (ProductGraphSource.ProductNode node : snapshot.nodes()) {
            nextNames.put(node.code(), node.name());
            nextCategories.put(node.code(), node.category());
            nextBrands.put(node.code(), node.brand());
            nextAdjacency.put(node.code(), new ArrayList<>());
        }
        for (ProductGraphSource.ProductRelation relation : snapshot.relations()) {
            if (!nextAdjacency.containsKey(relation.sourceCode())
                    || !nextAdjacency.containsKey(relation.targetCode())) continue;
            try {
                RelationType type = RelationType.valueOf(relation.relationType().toUpperCase(Locale.ROOT));
                addEdge(nextAdjacency, relation.sourceCode(), relation.targetCode(), type, relation.weight());
            } catch (IllegalArgumentException ignored) {
                log.warn("[ProductGraph] 忽略未知关系类型: {}", relation.relationType());
            }
        }
        productNames.clear(); productNames.putAll(nextNames);
        productCategory.clear(); productCategory.putAll(nextCategories);
        productBrand.clear(); productBrand.putAll(nextBrands);
        adjacencyList.clear(); adjacencyList.putAll(nextAdjacency);
        log.info("[ProductGraph] 实时图刷新完成: 节点={}, 边={}", getNodeCount(), getEdgeCount());
    }

    // ==================== 节点/边操作 ====================

    private static void addEdge(Map<String, List<Edge>> graph,
                                String from, String to, RelationType type, double weight) {
        graph.computeIfAbsent(from, k -> new ArrayList<>())
                .add(new Edge(to, type, weight));
        // 无向图（加反向边，但反向时类型和权重保持一致）
        graph.computeIfAbsent(to, k -> new ArrayList<>())
                .add(new Edge(from, type, weight * 0.9));
    }

    // ==================== 核心查询 ====================

    /**
     * 查询与指定商品关联的商品（按关系类型过滤）。
     *
     * @param productCode 商品编码
     * @param relationType 关系类型（null 表示全部）
     * @param maxResults 最大返回数
     * @return 关联商品列表（按相关性得分降序）
     */
    public List<GraphQueryResult> queryRelated(String productCode,
                                                RelationType relationType,
                                                int maxResults) {
        if (productCode == null || !adjacencyList.containsKey(productCode)) {
            return List.of();
        }

        List<Edge> edges = adjacencyList.get(productCode);
        Stream<Edge> stream = edges.stream();
        if (relationType != null) {
            stream = stream.filter(e -> e.type() == relationType);
        }

        return stream
                .sorted((a, b) -> Double.compare(b.weight(), a.weight()))
                .limit(maxResults)
                .map(e -> new GraphQueryResult(
                        e.targetCode(),
                        productNames.getOrDefault(e.targetCode(), e.targetCode()),
                        e.weight(),
                        e.type()))
                .collect(Collectors.toList());
    }

    /**
     * 查询与指定商品同品类的所有商品（同类推荐）。
     */
    public List<GraphQueryResult> querySameCategory(String productCode, int maxResults) {
        return queryRelated(productCode, RelationType.SAME_CATEGORY, maxResults);
    }

    /**
     * 查询指定商品的配件推荐。
     */
    public List<GraphQueryResult> queryAccessories(String productCode, int maxResults) {
        return queryRelated(productCode, RelationType.ACCESSORY, maxResults);
    }

    /**
     * 查询指定商品的替代品。
     */
    public List<GraphQueryResult> queryAlternatives(String productCode, int maxResults) {
        return queryRelated(productCode, RelationType.ALTERNATIVE, maxResults);
    }

    /**
     * 综合推荐：合并所有关系类型的关联商品，去重后按得分排序。
     */
    public List<GraphQueryResult> queryRecommendations(String productCode, int maxResults) {
        if (productCode == null || !adjacencyList.containsKey(productCode)) {
            return List.of();
        }

        Map<String, GraphQueryResult> merged = new LinkedHashMap<>();
        List<Edge> edges = adjacencyList.get(productCode);

        for (Edge edge : edges) {
            String targetName = productNames.getOrDefault(edge.targetCode(), edge.targetCode());
            GraphQueryResult existing = merged.get(edge.targetCode());
            if (existing == null || existing.getRelevanceScore() < edge.weight()) {
                merged.put(edge.targetCode(),
                        new GraphQueryResult(edge.targetCode(), targetName,
                                edge.weight(), edge.type()));
            }
        }

        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 从用户查询文本中尝试提取商品编码。
     * 支持按名称模糊匹配。
     */
    public String matchProduct(String query) {
        if (query == null || query.isBlank()) return null;

        String q = query.toLowerCase(Locale.CHINESE);

        // 精确匹配编码
        for (String code : adjacencyList.keySet()) {
            if (q.contains(code.toLowerCase())) {
                return code;
            }
        }

        // 模糊匹配名称
        for (Map.Entry<String, String> entry : productNames.entrySet()) {
            if (q.contains(entry.getValue().toLowerCase(Locale.CHINESE).replace("（", "").replace("）", ""))) {
                return entry.getKey();
            }
        }

        return null;
    }

    // ==================== 统计 ====================

    /** 获取图中的节点数 */
    public int getNodeCount() {
        return adjacencyList.size();
    }

    /** 获取图中的边数 */
    public int getEdgeCount() {
        return adjacencyList.values().stream().mapToInt(List::size).sum() / 2;
    }

    /** 获取所有商品编码 */
    public Set<String> getAllProductCodes() {
        return adjacencyList.keySet();
    }

    /** 获取商品名称 */
    public String getProductName(String code) {
        return productNames.get(code);
    }

    /** 获取商品分类 */
    public String getProductCategory(String code) {
        return productCategory.get(code);
    }

    /** 获取商品品牌 */
    public String getProductBrand(String code) {
        return productBrand.get(code);
    }
}
