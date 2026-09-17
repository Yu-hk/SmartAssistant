package com.example.smartassistant.service.core;

import com.example.smartassistant.common.util.UserQuestionNormalizer;
import com.example.smartassistant.spi.ProductBackend;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic product discovery for generic catalog and popularity queries. */
@Service
public class ProductDiscoveryService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int HARD_CONSTRAINT_CANDIDATE_LIMIT = 20;
    private static final String BUDGET_NUMBER = "((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)";
    private static final Pattern BUDGET_PREFIX_PATTERN = Pattern.compile(
            "(?:预算\\s*(?:不超过|不高于|控制在|只有|仅有|改为|调整为|仅|为|是|在|[:：=]|<=|≤)?|最高|最多|不超过|不高于|控制在)"
                    + "\\s*[¥￥]?\\s*" + BUDGET_NUMBER + "\\s*(万|千|[kK])?\\s*元?");
    private static final Pattern BUDGET_SUFFIX_PATTERN = Pattern.compile(
            "(?<![\\d.,])[¥￥]?\\s*" + BUDGET_NUMBER + "\\s*(万|千|[kK])?\\s*元?\\s*(?:以内|以下|之内)");

    private final ProductBackend productBackend;

    public ProductDiscoveryService(ProductBackend productBackend) {
        this.productBackend = productBackend;
    }

    /** Returns live catalog categories for tools, routing and UI clients. */
    public List<String> listProductCategories() {
        List<String> categories = productBackend.listProductCategories();
        if (categories == null) throw new com.example.smartassistant.spi.ProductCatalogUnavailableException();
        return categories.stream()
                .filter(category -> category != null && !category.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    /** Returns true only for generic discovery requests, not specific product recommendations. */
    public boolean supports(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = UserQuestionNormalizer.normalize(query)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        boolean categoryRequest = !detectCategory(normalized).isBlank();
        boolean popularityRequest = asksForPopularity(normalized);
        boolean hardConstraintRequest = resolveBudget(normalized).max() != null || resolveBudget(normalized).ambiguous()
                || normalized.contains("只看") || normalized.contains("仅看")
                || normalized.contains("限定");
        ProductFeatureRequest features = ProductFeatureRequest.parse(normalized);
        if ((features.constraints().active() || !features.clarification().isBlank())
                && normalized.matches(".*(?:多少|怎么样|吗|么|[?？]).*")
                && !normalized.matches(".*(?:推荐|想买|选购|筛选|只看|不超过|至少|≤|≥|以内).*")) return false;
        if (features.constraints().active() || !features.clarification().isBlank()
                && normalized.matches(".*(?:推荐|想买|选购|需要|轻便|便携|长续航|续航长).*")) return true;
        return (popularityRequest && (normalized.contains("商品") || normalized.contains("推荐")
                    || normalized.contains("买") || normalized.contains("流行")))
                || normalized.matches(".*(?:推荐|想买|选购|想要|需要).*(?:轻便|便携|续航|降噪|拍照|预算).*")
                || normalized.matches(".*(?:轻便|便携).*续航.*|.*续航.*(?:轻便|便携).*")
                || (normalized.contains("预算") && hardConstraintRequest)
                || normalized.contains("热门商品")
                || normalized.contains("热销商品")
                || normalized.contains("畅销商品")
                || normalized.contains("商品排行榜")
                || normalized.contains("商品排行")
                || normalized.contains("有什么商品")
                || normalized.contains("有哪些商品")
                || normalized.contains("商品列表")
                || normalized.matches(".*推荐(?:一些|一款|几款|几个|点)?商品.*")
                || (categoryRequest && (popularityRequest || normalized.contains("推荐")
                        || hardConstraintRequest));
    }

    public DiscoveryResult discover(String query, Integer requestedLimit) {
        return discover(query, null, requestedLimit);
    }

    /** Category-aware discovery. A specific category always gets a candidate pool, not limit=1. */
    public DiscoveryResult discover(String query, String requestedCategory, Integer requestedLimit) {
        String normalizedQuery = UserQuestionNormalizer.normalize(query);
        String category = normalizeCategory(requestedCategory);
        if (category.isBlank()) category = detectCategory(normalizedQuery);
        ProductFeatureRequest featureRequest = ProductFeatureRequest.parse(normalizedQuery);
        BudgetResolution budget = resolveBudget(normalizedQuery);
        BigDecimal maxBudget = budget.max();
        if (budget.ambiguous()) return clarification(budget.clarification(), category);
        boolean inStockOnly = asksForAvailableStock(normalizedQuery);
        if (!featureRequest.clarification().isBlank()) {
            String prefix = category.isBlank() ? "你想选购哪类商品？我会保留已提供的预算和特征。" : "";
            return clarification(prefix + featureRequest.clarification(), category);
        }
        if (category.isBlank() && featureRequest.constraints().active()) {
            List<String> matchingCategories = productBackend.listMatchingCategories(new ProductBackend.ProductDiscoveryCriteria(
                    "", normalizedQuery, maxBudget, inStockOnly, MAX_LIMIT, featureRequest.constraints()));
            if (matchingCategories == null) throw new com.example.smartassistant.spi.ProductCatalogUnavailableException();
            List<String> distinct = matchingCategories.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();
            if (distinct.size() == 1) category = distinct.getFirst();
            else return clarification(distinct.isEmpty()
                    ? "现有结构化目录证据不足以确定商品类型。你想选购哪类商品？我会保留已提供的预算和特征。"
                    : "符合这些特征的商品涉及" + String.join("、", distinct) + "，你想选购哪类商品？", "");
        }
        boolean popularityRequest = asksForPopularity(normalizedQuery);
        boolean browsingOnly = popularityRequest && category.isBlank()
                && !isScenarioSpecific(normalizedQuery)
                && !normalizedQuery.matches(".*(?:一款|一个|最适合|帮我选).*");
        if (category.isBlank() && !popularityRequest
                && normalizedQuery.matches("(?s).*(?:推荐|想买|选购|想要|需要|预算|轻便|便携|续航).*")
                && !normalizedQuery.matches(".*(?:商品列表|有什么商品|有哪些商品).*")) {
            return new DiscoveryResult("你想选购哪类商品，主要用来做什么？"
                    + "我会结合你已提供的预算和特征继续筛选。", 0, false, List.of(),
                    false, "", true, false);
        }

        int requested = requestedLimit == null
                ? DEFAULT_LIMIT
                : Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        int candidateLimit = maxBudget != null
                ? HARD_CONSTRAINT_CANDIDATE_LIMIT
                : category.isBlank() ? requested : Math.max(DEFAULT_LIMIT, requested);
        List<ProductBackend.ProductSummary> discoveredProducts = productBackend.listPopularProducts(
                new ProductBackend.ProductDiscoveryCriteria(
                        category, normalizedQuery, maxBudget, inStockOnly, candidateLimit, featureRequest.constraints()));
        List<ProductBackend.ProductSummary> products = discoveredProducts == null
                ? unavailableCatalog()
                : discoveredProducts.stream()
                .filter(product -> maxBudget == null
                        || (product.price() != null && product.price().compareTo(maxBudget) <= 0))
                .filter(product -> !inStockOnly || isAvailableStock(product.stock()))
                .filter(product -> featureRequest.constraints().matches(product.features()))
                .limit(requested)
                .toList();
        if (products.isEmpty()) {
            if (featureRequest.constraints().active()) {
                return new DiscoveryResult("当前目录中没有可核实满足这些结构化特征与预算条件的商品。"
                        + "字段缺失不代表不支持，但不能作为匹配证据；可以调整条件或补充已核验的参数。",
                        0, false, List.of(), false, category, false, false);
            }
            String scope = category.isBlank() ? "商品" : category;
            String constraint = maxBudget == null ? ""
                    : "且价格不超过" + formatPrice(maxBudget).replace("¥", "") + "元";
            return new DiscoveryResult("当前暂无符合“" + scope + constraint
                    + "”条件的可售商品，请调整预算或品类后再试。",
                    0, false, List.of(), false, category, false, browsingOnly);
        }

        boolean hasPopularityData = products.stream().anyMatch(product -> product.popularity() > 0);
        boolean asksForPopularity = asksForPopularity(normalizedQuery);
        boolean scenarioEvidenceLimited = isScenarioSpecific(normalizedQuery);
        StringBuilder answer = new StringBuilder();
        if (scenarioEvidenceLimited) {
            answer.append("以下仅是当前目录中的可售候选。目录没有可验证的场景适配字段，")
                    .append("因此不能把热度直接等同于适合该办公或会议场景：\n");
        } else if (asksForPopularity && hasPopularityData) {
            answer.append("近期热门").append(category.isBlank() ? "商品" : category)
                    .append("（按目录记录的站内近30天销量排序，不代表全网热度）：\n");
        } else if (asksForPopularity) {
            answer.append("当前推荐").append(category.isBlank() ? "商品" : category)
                    .append("（暂无足够销量数据，按可售状态展示）：\n");
        } else {
            answer.append("当前可选").append(category.isBlank() ? "商品" : category).append("：\n");
        }

        for (int i = 0; i < products.size(); i++) {
            ProductBackend.ProductSummary product = products.get(i);
            answer.append(i + 1).append(". ").append(value(product.name(), "未命名商品"))
                    .append(" — ").append(formatPrice(product.price()))
                    .append("，库存：").append(value(product.stock(), "未知"));
            if (hasPopularityData && product.popularity() > 0) {
                answer.append("，近30天站内销量：").append(product.popularity());
            }
            if (product.marketPrice() != null && product.price() != null
                    && product.marketPrice().compareTo(product.price()) > 0) {
                answer.append("，参考价：").append(formatPrice(product.marketPrice()));
            }
            if (product.rating() != null && product.rating().signum() > 0) {
                answer.append("，评分：").append(product.rating().stripTrailingZeros().toPlainString())
                        .append("/5");
                if (product.reviewCount() > 0) {
                    answer.append("（").append(product.reviewCount()).append("条评价）");
                }
            }
            if (scenarioEvidenceLimited) {
                answer.append("，场景适配：现有目录证据不足，需核实规格和实际需求");
            }
            if (asksForPopularity && product.popularity() > 0) {
                answer.append("。入选依据：目录记录的近30天销量；热销不等于适合个人用途");
            }
            if (product.features().documented()) {
                answer.append("。结构化证据：").append(product.features().evidence());
                if (featureRequest.constraints().active()) answer.append("。上述记录满足本次明确的特征条件；实际体验可能不同");
            }
            answer.append('\n');
        }
        if (scenarioEvidenceLimited) {
            answer.append("\n若用于多人办公室或视频会议，请继续确认并发使用人数、摄像头、麦克风、")
                    .append("扬声器、接口和预算要求；在这些规格得到验证前，不应把上述候选表述为最终推荐。");
        } else {
            if (browsingOnly) answer.append("\n以上是跨品类浏览结果，不是为你选定的唯一最佳商品。");
            answer.append("\n告诉我商品名称或型号，我可以继续查询价格、规格和库存。");
        }
        return new DiscoveryResult(answer.toString().trim(), products.size(), hasPopularityData,
                products, scenarioEvidenceLimited, category, false, browsingOnly);
    }

    private static boolean asksForPopularity(String query) {
        if (query == null) return false;
        return query.contains("热门") || query.contains("热销")
                || query.contains("畅销") || query.contains("排行") || query.contains("流行");
    }

    private static DiscoveryResult clarification(String answer, String category) {
        return new DiscoveryResult(answer, 0, false, List.of(), false, category, true, false);
    }

    private static List<ProductBackend.ProductSummary> unavailableCatalog() {
        throw new com.example.smartassistant.spi.ProductCatalogUnavailableException();
    }

    private static String formatPrice(BigDecimal price) {
        return price == null ? "价格待确认" : "¥" + price.stripTrailingZeros().toPlainString();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record DiscoveryResult(
            String answer,
            int productCount,
            boolean popularityBased,
            List<ProductBackend.ProductSummary> products,
            boolean scenarioEvidenceLimited,
            String category,
            boolean clarificationRequired,
            boolean browsingOnly) {
        public DiscoveryResult {
            products = products != null ? List.copyOf(products) : List.of();
            category = category == null ? "" : category;
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased,
                               List<ProductBackend.ProductSummary> products,
                               boolean scenarioEvidenceLimited, String category) {
            this(answer, productCount, popularityBased, products, scenarioEvidenceLimited,
                    category, false, false);
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased) {
            this(answer, productCount, popularityBased, List.of(), false, "");
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased,
                               List<ProductBackend.ProductSummary> products) {
            this(answer, productCount, popularityBased, products, false, "");
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased,
                               List<ProductBackend.ProductSummary> products,
                               boolean scenarioEvidenceLimited) {
            this(answer, productCount, popularityBased, products, scenarioEvidenceLimited, "");
        }
    }

    private static boolean asksForAvailableStock(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = query.replaceAll("\\s+", "");
        return normalized.contains("只看有货") || normalized.contains("仅看有货")
                || normalized.contains("现货") || normalized.contains("库存充足")
                || normalized.contains("可以立即下单");
    }

    private static boolean isAvailableStock(String stock) {
        if (stock == null || stock.isBlank()) return false;
        return !stock.contains("缺货") && !stock.contains("无货") && !stock.contains("售罄");
    }

    public static BigDecimal extractMaxBudget(String query) {
        return resolveBudget(query).max();
    }

    public record BudgetResolution(BigDecimal max, boolean ambiguous) {
        public String clarification() {
            return ambiguous ? "您提到了多个预算或预算范围，请确认本次购买的预算上限是多少元？我会按您确认的金额筛选。" : "";
        }
    }

    public static BudgetResolution resolveBudget(String query) {
        if (query == null || query.isBlank()) return new BudgetResolution(null, false);
        String normalized = com.example.smartassistant.service.quality.ProductMoneySyntax.normalize(query);
        // Physical dimensions are not money: e.g. 不超过1.3kg must not become a 1300-yuan budget.
        normalized = normalized.replaceAll("(?i)(?:不超过|不大于|至少|不低于|不少于|最多|至多|<=|>=|≤|≥)?"
                + "\\s*\\d+(?:\\.\\d+)?\\s*(?:kg|千克|公斤|克|g|小时|h)(?:以内|以下|以上|及以下|及以上)?", "");
        record Candidate(int start, BigDecimal value, boolean current) {}
        var candidates = new java.util.ArrayList<Candidate>();
        boolean range = false;
        for (Pattern pattern : List.of(BUDGET_PREFIX_PATTERN, BUDGET_SUFFIX_PATTERN)) {
            Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                String before = normalized.substring(0, matcher.start());
                int boundary = Math.max(Math.max(before.lastIndexOf('，'), before.lastIndexOf(',')),
                        Math.max(before.lastIndexOf('。'), before.lastIndexOf('；')));
                String clause = before.substring(boundary + 1);
                // Only discard an amount with an explicit historical/negated qualifier.
                // A later current qualifier ("之前...现在...") takes precedence within a clause.
                int old = lastMarker(clause, "之前", "原来", "原先", "以前", "上次", "过去", "不要按", "别按", "不是", "不按");
                int current = lastMarker(clause, "现在", "本次", "这次", "目前", "改为", "调整为");
                if (old >= 0 && old >= current) continue;
                String matched = matcher.group();
                boolean revised = current >= 0 || matched.contains("改为") || matched.contains("调整为");
                if (normalized.substring(matcher.end()).matches("(?s)^\\s*(?:-|—|~|～|至|到)\\s*\\d.*")) {
                    range = true;
                }
                BigDecimal value = extractBudget(matcher);
                if (value != null) candidates.add(new Candidate(matcher.start(), value, revised));
            }
        }
        candidates.sort(java.util.Comparator.comparingInt(Candidate::start));
        // Explicit revisions win; conflicting unqualified limits require confirmation.
        Candidate revision = candidates.stream().filter(Candidate::current).reduce((a, b) -> b).orElse(null);
        if (revision != null && !range) return new BudgetResolution(revision.value(), false);
        var values = candidates.stream().map(c -> c.value().stripTrailingZeros()).distinct().toList();
        if (range || values.size() > 1) return new BudgetResolution(null, true);
        return new BudgetResolution(values.isEmpty() ? null : values.getFirst(), false);
    }

    private static int lastMarker(String text, String... markers) {
        int last = -1;
        for (String marker : markers) last = Math.max(last, text.lastIndexOf(marker));
        return last;
    }

    private static BigDecimal extractBudget(Matcher matcher) {
        try {
            BigDecimal value = new BigDecimal(matcher.group(1).replace(",", ""));
            String unit = matcher.group(2);
            if ("万".equals(unit)) value = value.multiply(BigDecimal.valueOf(10_000));
            if ("千".equals(unit) || "k".equalsIgnoreCase(unit)) {
                value = value.multiply(BigDecimal.valueOf(1_000));
            }
            return value.signum() >= 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeCategory(String value) {
        if (value == null || value.isBlank()) return "";
        if (List.of("商品", "全部", "不限", "所有商品").contains(value.trim())) return "";
        String canonical = detectCategory(value);
        return canonical.isBlank() ? value.trim() : canonical;
    }

    private String detectCategory(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        List<String> categories = listProductCategories();

        // Prefer an explicit category occurring in the question. The longest
        // match wins when the catalog contains nested category names.
        String explicit = categories.stream()
                .filter(category -> normalized.contains(normalizeForMatch(category)))
                .max((left, right) -> Integer.compare(
                        normalizeForMatch(left).length(), normalizeForMatch(right).length()))
                .orElse("");
        if (!explicit.isBlank()) {
            return explicit;
        }

        // A structured short value such as "平板" may identify a single live
        // category. Ambiguous fragments such as "电脑" are deliberately ignored.
        List<String> partialMatches = categories.stream()
                .filter(category -> normalizeForMatch(category).contains(normalized))
                .toList();
        return partialMatches.size() == 1 ? partialMatches.getFirst() : "";
    }

    private static String normalizeForMatch(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isScenarioSpecific(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = query.replaceAll("\\s+", "");
        return normalized.contains("适合") || normalized.contains("用于")
                || normalized.contains("使用场景") || normalized.contains("适用场景")
                || normalized.contains("采购方案") || normalized.contains("办公室")
                || normalized.contains("办公") || normalized.contains("视频会议")
                || normalized.contains("会议室");
    }
}
