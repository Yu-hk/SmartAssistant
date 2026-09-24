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
    private static final ProductDiscoverySchema DISCOVERY_SCHEMA = ProductDiscoverySchema.defaultSchema();
    private static final ProductDiscoveryIntentParser INTENT_PARSER = new ProductDiscoveryIntentParser(
            DISCOVERY_SCHEMA, ProductFeatureSchema.defaultSchema());
    private static final String BUDGET_NUMBER = "((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|[零〇一二两三四五六七八九十百千万]+)";
    private static final Pattern BUDGET_PREFIX_PATTERN = Pattern.compile(
            "(?:(?:预算|金额)\\s*(?:不超过|不高于|控制在|只有|仅有|改为|调整为|仅|为|是|在|[:：=]|<=|≤)?|最高|最多|不超过|不高于|控制在)"
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
        // Routing is decided by this turn. Historical shopping constraints may
        // help a real discovery request, but cannot turn a specification/stock
        // follow-up into another recommendation.
        String normalized = UserQuestionNormalizer.normalize(ProductQueryContext.current(query));
        ProductDiscoveryIntent intent = INTENT_PARSER.parse(normalized);
        boolean categoryRequest = !detectCategory(normalized).isBlank();
        if (intent.hasFeatureInterest() && intent.detailQuestion() && !intent.recommendation()
                && !intent.categoryRestricted()) return false;
        return intent.catalogBrowse() || intent.recommendation() && (categoryRequest
                || intent.hasFeatureInterest() || intent.hardConstraintRequested()
                || DISCOVERY_SCHEMA.contains("intent.catalog", normalized))
                || intent.hasFeatureInterest() && (intent.recommendation() || intent.categoryRestricted())
                || !intent.detailQuestion() && (intent.hasFeatureInterest()
                || intent.budget().max() != null || intent.budget().ambiguous())
                || categoryRequest && (intent.popularity() || intent.hardConstraintRequested());
    }

    /** A model-planned browse operation must not override a present-turn facts request. */
    public boolean isDetailOnly(String query) {
        if (query == null || query.isBlank()) return false;
        ProductDiscoveryIntent intent = INTENT_PARSER.parse(
                UserQuestionNormalizer.normalize(ProductQueryContext.current(query)));
        return intent.detailQuestion() && !intent.recommendation()
                && !intent.catalogBrowse() && !intent.hardConstraintRequested();
    }

    public DiscoveryResult discover(String query, Integer requestedLimit) {
        return discover(query, null, requestedLimit);
    }

    /** Category-aware discovery. A specific category always gets a candidate pool, not limit=1. */
    public DiscoveryResult discover(String query, String requestedCategory, Integer requestedLimit) {
        String normalizedQuery = UserQuestionNormalizer.normalize(query);
        String category = detectCategory(normalizedQuery);
        if (category.isBlank()) category = normalizeCategory(requestedCategory);
        ProductDiscoveryIntent intent = INTENT_PARSER.parse(normalizedQuery);
        ProductFeatureRequest featureRequest = intent.features();
        BudgetResolution budget = intent.budget();
        BigDecimal maxBudget = budget.max();
        if (budget.ambiguous()) return clarification(budget.clarification(), category, List.of("budget"));
        boolean inStockOnly = intent.inStockOnly();
        if (!featureRequest.clarification().isBlank()) {
            String prefix = category.isBlank() ? "你想选购哪类商品？我会保留已提供的预算和特征。" : "";
            var fields = new java.util.ArrayList<>(featureRequest.missingFields());
            if (!fields.isEmpty() && category.isBlank()) fields.addFirst("product");
            return clarification(prefix + featureRequest.clarification(), category, fields);
        }
        if (category.isBlank() && featureRequest.constraints().active()) {
            List<String> matchingCategories = productBackend.listMatchingCategories(new ProductBackend.ProductDiscoveryCriteria(
                    "", normalizedQuery, maxBudget, inStockOnly, MAX_LIMIT, featureRequest.constraints()));
            if (matchingCategories == null) throw new com.example.smartassistant.spi.ProductCatalogUnavailableException();
            List<String> distinct = matchingCategories.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();
            if (distinct.size() == 1) category = distinct.getFirst();
            else return clarification(distinct.isEmpty()
                    ? "现有结构化目录证据不足以确定商品类型。你想选购哪类商品？我会保留已提供的预算和特征。"
                    : "符合这些特征的商品涉及" + String.join("、", distinct) + "，你想选购哪类商品？", "", List.of("product"));
        }
        boolean popularityRequest = intent.popularity();
        boolean browsingOnly = popularityRequest && category.isBlank()
                && !intent.scenarioSpecific() && !intent.singleChoice();
        if (category.isBlank() && !popularityRequest
                && (intent.recommendation() || intent.hasFeatureInterest() || intent.hardConstraintRequested())
                && !intent.catalogBrowse()) {
            return clarification("您想选购哪类商品？我会结合您已提供的预算和特征继续筛选。", "", List.of("product"));
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
        boolean asksForPopularity = intent.popularity();
        List<String> qualitativeLabels = ProductFeatureSchema.defaultSchema()
                .preferenceLabels(featureRequest.qualitativePreferences());
        boolean scenarioEvidenceLimited = intent.scenarioSpecific() || !qualitativeLabels.isEmpty();
        StringBuilder answer = new StringBuilder();
        if (!qualitativeLabels.isEmpty()) {
            answer.append("您提到的").append(String.join("、", qualitativeLabels))
                    .append("属于选购偏好。当前目录没有可核实的对应偏好标签，")
                    .append("以下仅供同品类浏览，不能据此确认符合您的偏好：\n");
        } else if (scenarioEvidenceLimited) {
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
        if (!qualitativeLabels.isEmpty()) {
            answer.append("\n若您有明确的重量上限或具体使用需求，可以告诉我；")
                    .append("有可核实规格后，我再帮您筛选。");
        } else if (scenarioEvidenceLimited) {
            answer.append("\n若用于多人办公室或视频会议，请继续确认并发使用人数、摄像头、麦克风、")
                    .append("扬声器、接口和预算要求；在这些规格得到验证前，不应把上述候选表述为最终推荐。");
        } else {
            if (browsingOnly) answer.append("\n以上是跨品类浏览结果，不是为你选定的唯一最佳商品。");
            answer.append("\n告诉我商品名称或型号，我可以继续查询价格、规格和库存。");
        }
        return new DiscoveryResult(answer.toString().trim(), products.size(), hasPopularityData,
                products, scenarioEvidenceLimited, category, false, browsingOnly);
    }

    private static DiscoveryResult clarification(String answer, String category, List<String> fields) {
        return new DiscoveryResult(answer, 0, false, List.of(), false, category, true, false, fields);
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
            boolean browsingOnly,
            List<String> missingFields) {
        public DiscoveryResult {
            products = products != null ? List.copyOf(products) : List.of();
            category = category == null ? "" : category;
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }

        public DiscoveryResult(String answer, int productCount, boolean popularityBased,
                List<ProductBackend.ProductSummary> products, boolean scenarioEvidenceLimited,
                String category, boolean clarificationRequired, boolean browsingOnly) {
            this(answer, productCount, popularityBased, products, scenarioEvidenceLimited, category,
                    clarificationRequired, browsingOnly, List.of());
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

    private static boolean isAvailableStock(String stock) {
        if (stock == null || stock.isBlank()) return false;
        return !DISCOVERY_SCHEMA.unavailableStock(stock);
    }

    public static BigDecimal extractMaxBudget(String query) {
        return resolveBudget(query).max();
    }

    public record BudgetResolution(BigDecimal max, boolean ambiguous, String issue) {
        public BudgetResolution(BigDecimal max, boolean ambiguous) { this(max, ambiguous, ""); }
        public String clarification() {
            return !issue.isBlank() ? issue : ambiguous
                    ? "您提到了多个预算或预算范围，请确认本次购买的预算上限是多少元？我会按您确认的金额筛选。" : "";
        }
    }

    public static BudgetResolution resolveBudget(String query) {
        List<String> turns = ProductQueryContext.turns(query);
        for (int i = turns.size() - 1; i >= 0; i--) {
            BudgetResolution budget = resolveTurnBudget(turns.get(i));
            if (budget.max() != null || budget.ambiguous()) return budget;
        }
        return new BudgetResolution(null, false);
    }

    private static BudgetResolution resolveTurnBudget(String query) {
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
                if (normalized.substring(matcher.end()).matches("(?s)^\\s*(?:-|—|~|～|至|到|或)\\s*[\\d零〇一二两三四五六七八九十百千万].*")) {
                    range = true;
                }
                BigDecimal value = extractBudget(matcher);
                String following = normalized.substring(matcher.end()).stripLeading();
                if (DISCOVERY_SCHEMA.terms("budget.unsupported-currencies").stream()
                        .anyMatch(following::startsWith))
                    return new BudgetResolution(null, true, DISCOVERY_SCHEMA.budgetMessage("currency"));
                if (value != null) candidates.add(new Candidate(matcher.start(), value, revised));
                else range = true; // An unparseable explicit budget is not an absent budget.
            }
        }
        candidates.sort(java.util.Comparator.comparingInt(Candidate::start));
        // Explicit revisions win; conflicting unqualified limits require confirmation.
        Candidate revision = candidates.stream().filter(Candidate::current).reduce((a, b) -> b).orElse(null);
        if (revision != null && !range) return validatedBudget(revision.value());
        var values = candidates.stream().map(c -> c.value().stripTrailingZeros()).distinct().toList();
        if (range || values.size() > 1) return new BudgetResolution(null, true);
        return values.isEmpty() ? new BudgetResolution(null, false) : validatedBudget(values.getFirst());
    }

    private static BudgetResolution validatedBudget(BigDecimal value) {
        if (value.compareTo(DISCOVERY_SCHEMA.budgetMinimum()) < 0
                || value.compareTo(DISCOVERY_SCHEMA.budgetMaximum()) > 0)
            return new BudgetResolution(null, true, DISCOVERY_SCHEMA.budgetMessage("invalid"));
        return new BudgetResolution(value, false);
    }

    private static int lastMarker(String text, String... markers) {
        int last = -1;
        for (String marker : markers) last = Math.max(last, text.lastIndexOf(marker));
        return last;
    }

    private static BigDecimal extractBudget(Matcher matcher) {
        try {
            String raw = matcher.group(1);
            BigDecimal value = raw.matches("[零〇一二两三四五六七八九十百千万]+")
                    ? chineseBudget(raw) : new BigDecimal(raw.replace(",", ""));
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

    /** Strict integer money notation; colloquial 三千五/一两千 must be clarified. */
    private static BigDecimal chineseBudget(String raw) {
        long total = 0, section = 0;
        int digit = -1, lastUnit = 10000;
        boolean zero = false, usedWan = false;
        for (char c : raw.toCharArray()) {
            int number = "零一二三四五六七八九".indexOf(c);
            if (c == '〇') number = 0;
            if (c == '两') number = 2;
            if (number >= 0) {
                if (number == 0) {
                    if (digit >= 0) throw new NumberFormatException("Invalid Chinese budget");
                    zero = true;
                } else {
                    if (digit >= 0) throw new NumberFormatException("Ambiguous Chinese budget");
                    digit = number;
                }
                continue;
            }
            int unit = switch (c) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; default -> 10000; };
            if (unit == 10000) {
                if (usedWan || section + Math.max(0, digit) == 0) throw new NumberFormatException("Invalid Chinese budget");
                total = (section + Math.max(0, digit)) * unit;
                section = 0; digit = -1; lastUnit = 10000; usedWan = true; zero = false;
            } else {
                if (unit >= lastUnit || digit < 0 && !(unit == 10 && section == 0 && !usedWan))
                    throw new NumberFormatException("Invalid Chinese budget");
                section += (digit < 0 ? 1 : digit) * unit;
                digit = -1; lastUnit = unit; zero = false;
            }
        }
        if (digit >= 0 && lastUnit > 10 && (section > 0 || usedWan) && !zero)
            throw new NumberFormatException("Ambiguous Chinese budget");
        return BigDecimal.valueOf(total + section + Math.max(0, digit));
    }

    private String normalizeCategory(String value) {
        if (value == null || value.isBlank()) return "";
        if (DISCOVERY_SCHEMA.terms("category.all").contains(value.trim())) return "";
        String canonical = detectCategory(value);
        return canonical.isBlank() ? value.trim() : canonical;
    }

    private String detectCategory(String value) {
        List<String> turns = ProductQueryContext.turns(value);
        for (int i = turns.size() - 1; i >= 0; i--) {
            String category = detectTurnCategory(turns.get(i));
            if (!category.isBlank()) return category;
        }
        return "";
    }

    private String detectTurnCategory(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        List<String> categories = listProductCategories();
        for (String alias : DISCOVERY_SCHEMA.categoryAliases()) {
            String[] mapping = alias.split(":", 2);
            if (mapping.length == 2 && normalized.contains(mapping[0]) && categories.contains(mapping[1]))
                return mapping[1];
        }

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

}
