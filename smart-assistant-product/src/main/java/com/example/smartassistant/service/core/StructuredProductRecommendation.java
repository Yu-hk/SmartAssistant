package com.example.smartassistant.service.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import com.example.smartassistant.spi.ProductFeatures;

/** Catalog-backed amounts and rendering. Models select evidence references, never write money. */
public final class StructuredProductRecommendation {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> FIELDS = Set.of("spec", "rating", "reviewCount", "popularity", "features");
    private static final Set<String> LIMITS = Set.of("SINGLE_CANDIDATE", "NO_COMPARABLE_SPEC",
            "NO_PHOTO_BENCHMARK", "MISSING_RATING", "NEEDS_VERIFICATION");
    private static final Pattern DETAILS = Pattern.compile("差额|剩余|还剩|余额|结余|算式|计算过程");
    private static final Pattern NO_DETAILS = Pattern.compile(
            "(?:(?:不要|无需|不用|不必|不需要|别)(?:展示|显示|计算|算|提供|给出|说明)?"
                    + "|不(?:展示|显示|计算|算|提供|给出|说明))"
                    + "(?:预算)?(?:差额|剩余|还剩|余额|结余|算式|计算过程)"
                    + "|只(?:关心|需要|想知道|说明|告诉我).*(?:超预算|超过预算|是否超)");

    public record Product(String code, String name, BigDecimal price, String stock, String spec,
                          BigDecimal rating, Long reviewCount, Long popularity, ProductFeatures features) { }
    public record Decision(boolean valid, String selectedCode, List<String> evidenceFields,
                           List<String> limitations, String correction) { }
    private final List<Product> products;
    private final BigDecimal budget;
    private final ProductDiscoveryService.BudgetResolution budgetResolution;
    private final boolean detailsRequested;
    private final String question;
    private final ProductFeatureRequest featureRequest;

    public StructuredProductRecommendation(String question, List<? extends Map<?, ?>> catalog) {
        if (catalog == null || catalog.isEmpty()) throw new IllegalArgumentException("Missing verified catalog");
        this.question = question == null ? "" : question;
        featureRequest = ProductFeatureRequest.parse(this.question);
        budgetResolution = ProductDiscoveryService.resolveBudget(question);
        budget = budgetResolution.max();
        detailsRequested = question != null && DETAILS.matcher(question).find() && !NO_DETAILS.matcher(question).find();
        List<Product> parsed = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (Map<?, ?> item : catalog) {
            String code = text(item.get("code"));
            String name = text(item.get("name"));
            if (code.isBlank() || name.isBlank() || !codes.add(code)) throw new IllegalArgumentException("Invalid or duplicate catalog identity");
            parsed.add(new Product(code, name, amount(item.get("price")), text(item.get("stock")),
                    text(item.get("spec")), amount(item.get("rating")), count(item.get("reviewCount")), count(item.get("popularity")),
                    ProductFeatures.from(item.get("features"))));
        }
        products = List.copyOf(parsed);
    }

    public boolean hasEligibleProducts() { return products.stream().anyMatch(this::eligible); }

    private boolean eligible(Product p) {
        return !budgetResolution.ambiguous() && p.price() != null && (budget == null || p.price().compareTo(budget) <= 0)
                && featureRequest.clarification().isBlank() && featureRequest.constraints().matches(p.features())
                && !p.stock().matches("(?i)(?:.*(?:缺货|无货|售罄|售完|out.of.stock).*|0(?:\\.0+)?)");
    }

    public Map<String, Object> budgetData() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currency", "CNY");
        result.put("maxBudget", budget);
        result.put("detailsRequested", detailsRequested);
        result.put("products", products.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", p.code());
            row.put("price", p.price());
            row.put("status", status(p));
            row.put("eligible", eligible(p));
            // Do not propagate a computed remainder into normal recommendation conclusions.
            if (detailsRequested && budget != null && p.price() != null) row.put("remainder", budget.subtract(p.price()));
            return row;
        }).toList());
        return result;
    }

    public String promptData() {
        try { return JSON.writeValueAsString(Map.of("catalog", products, "budgetAssessment", budgetData())); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize catalog facts", e); }
    }

    public Decision parse(String raw) {
        try {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("```json") && value.endsWith("```")) value = value.substring(7, value.length() - 3).trim();
            JsonNode root = JSON.readTree(value);
            if (root == null || !root.isObject() || !root.path("valid").isBoolean()) throw new IllegalArgumentException("Expected decision object");
            Set<String> allowed = Set.of("valid", "selected_code", "evidence_fields", "limitations", "issues", "correction_instruction");
            root.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new IllegalArgumentException("Unexpected decision field: " + key); });
            JsonNode issues = root.get("issues");
            if (issues != null) {
                if (!issues.isArray() || issues.size() > 10) throw new IllegalArgumentException("Invalid audit issues");
                for (JsonNode issue : issues) if (!issue.isTextual()) throw new IllegalArgumentException("Invalid audit issue");
                if (root.get("valid").booleanValue() && !issues.isEmpty()) throw new IllegalArgumentException("Conflicting audit decision");
            }
            if (root.has("correction_instruction") && !root.get("correction_instruction").isTextual())
                throw new IllegalArgumentException("Invalid correction instruction");
            String correction = root.path("correction_instruction").asText("");
            if (correction.isBlank() && issues != null) correction = issues.toString();
            if (!root.get("valid").booleanValue()) return new Decision(false, "", List.of(), List.of(), correction);
            if (!root.path("selected_code").isTextual()) throw new IllegalArgumentException("Missing selected code");
            String code = root.get("selected_code").textValue();
            Product selected = products.stream().filter(p -> p.code().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown selected product"));
            if (!eligible(selected)) throw new IllegalArgumentException("Selected product does not satisfy verified budget/availability");
            List<String> fields = enumList(root.get("evidence_fields"), FIELDS);
            List<String> limits = enumList(root.get("limitations"), LIMITS);
            for (String field : fields) {
                if (field.equals("spec") && selected.spec().isBlank()
                        || field.equals("rating") && selected.rating() == null
                        || field.equals("reviewCount") && selected.reviewCount() == null
                        || field.equals("popularity") && selected.popularity() == null
                        || field.equals("features") && !selected.features().documented()) throw new IllegalArgumentException("Missing selected evidence");
            }
            if (limits.contains("SINGLE_CANDIDATE") && products.size() != 1
                    || limits.contains("MISSING_RATING") && selected.rating() != null) throw new IllegalArgumentException("Unsupported limitation");
            return new Decision(true, code, fields, limits, correction);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid structured product decision", e);
        }
    }

    public String renderAnalysis(Decision decision) {
        StringBuilder out = new StringBuilder("【数据概览】\n分析依据为本轮真实商品目录。\n【分析过程】\n");
        for (Product p : products) {
            out.append(p.name()).append("：").append(priceAndBudget(p)).append('\n');
            out.append("销售量：").append(p.popularity() == null ? "缺少可比销量数据" : "站内近30天销量口径 " + p.popularity()).append("；");
            out.append("口碑：").append(p.rating() == null ? "暂无评分证据" : "评分 " + money(p.rating()) + "/5");
            if (p.reviewCount() != null) out.append("，评价 ").append(p.reviewCount()).append(" 条");
            out.append("。\n");
        }
        out.append("性价比：仅凭价格不能判断高低，需要可比规格和用途证据。\n【核心结论】\n");
        Product selected = selected(decision);
        out.append("可进一步核实的候选：").append(selected.name()).append("，").append(priceAndBudget(selected));
        out.append("\n【数据限制】\n").append(limitations(decision));
        return out.toString();
    }

    public String renderRecommendation(Decision decision) {
        Product p = selected(decision);
        StringBuilder out = new StringBuilder("可考虑").append(p.name()).append("，").append(priceAndBudget(p));
        out.append("\n推荐理由（依据本轮目录）：");
        if (budget != null) out.append("价格符合你给出的预算上限；");
        if (p.stock().equals("充足") || p.stock().equals("紧张")) {
            out.append("目录库存标记为“").append(p.stock()).append("”；");
        }
        if (p.features().documented()) {
            out.append("\n结构化证据：").append(p.features().evidence()).append("。");
            if (featureRequest.constraints().active()) {
                out.append("这些记录满足你明确给出的特征条件；标称续航不保证实际使用时长。\n");
            }
        }
        List<String> relatedFeatures = new ArrayList<>();
        if (question.matches(".*(?:拍照|摄影|相机).*")
                && p.spec().matches(".*(?:摄像|镜头|光学|像素|相机).*") ) relatedFeatures.add("拍照");
        if (question.contains("降噪") && p.spec().contains("降噪")) relatedFeatures.add("降噪");
        if (question.matches(".*(?:续航|电池).*")
                && p.spec().matches(".*(?:续航|电池|mAh|Wh).*") ) relatedFeatures.add("续航");
        if (question.matches(".*(?:轻便|便携|重量).*")
                && p.spec().matches(".*(?:重量|轻|克|kg).*") ) relatedFeatures.add("便携性");
        if (!relatedFeatures.isEmpty()) {
            out.append("与你提到的").append(String.join("、", relatedFeatures))
                    .append("相关的目录规格是：").append(p.spec())
                    .append("。这仅说明存在相关规格，不能证明性能优于其他商品。");
        } else {
            out.append("可核实的价格和目录资料支持将它列为候选，尚不能证明满足所有使用偏好。");
        }
        if (decision.evidenceFields().contains("spec")) out.append("\n已核实规格：").append(p.spec()).append("。");
        if (decision.evidenceFields().contains("rating")) out.append("\n评分 ").append(money(p.rating())).append("/5。");
        if (decision.evidenceFields().contains("reviewCount")) out.append("评价 ").append(p.reviewCount()).append(" 条。");
        if (decision.evidenceFields().contains("popularity")) out.append("站内近30天销量口径 ").append(p.popularity()).append("（目录记录，非全网热度）。");
        if (decision.evidenceFields().contains("rating") || decision.evidenceFields().contains("popularity")) {
            out.append("评分和销量仅作口碑、热度参考，不等同于专项性能证明。");
        }
        out.append('\n').append(limitations(decision));
        if (detailsRequested && budget != null) {
            out.append("\n\n按你的要求补充预算计算：\n").append(money(budget)).append(" − ")
                    .append(money(p.price())).append(" = ").append(money(budget.subtract(p.price())))
                    .append("元，预算剩余").append(money(budget.subtract(p.price()))).append("元。");
        }
        return out.toString();
    }

    public String noEligibleAnswer() {
        if (budgetResolution.ambiguous()) return budgetResolution.clarification();
        if (!featureRequest.clarification().isBlank()) return featureRequest.clarification();
        StringBuilder answer = new StringBuilder("当前目录中没有满足当前条件的可推荐商品。\n");
        if (featureRequest.constraints().active()) answer.append("未找到结构化证据足以核实全部特征条件的候选；未知字段不能按匹配处理。\n");
        for (Product p : products) {
            answer.append(p.name()).append("：").append(priceAndBudget(p));
            if (!p.stock().isBlank()) answer.append("库存信息：").append(p.stock()).append("。");
            answer.append('\n');
        }
        return answer.append("可以调整条件，或补充可核实的价格和库存信息。").toString();
    }

    private Product selected(Decision decision) {
        if (!decision.valid()) throw new IllegalArgumentException("Rejected decision cannot be rendered as recommendation");
        return products.stream().filter(p -> p.code().equals(decision.selectedCode()) && eligible(p)).findFirst().orElseThrow();
    }

    private String priceAndBudget(Product p) {
        return "售价" + (p.price() == null ? "待核实" : money(p.price()) + "元") + "，" + switch (status(p)) {
            case "WITHIN_BUDGET" -> "未超预算。";
            case "OVER_BUDGET" -> "超出预算，不建议按当前条件选择。";
            case "PRICE_UNAVAILABLE" -> "无法确认是否符合预算。";
            default -> "未提供明确预算上限。";
        };
    }

    private String status(Product p) {
        if (p.price() == null) return "PRICE_UNAVAILABLE";
        if (budget == null) return "BUDGET_UNSPECIFIED";
        return p.price().compareTo(budget) <= 0 ? "WITHIN_BUDGET" : "OVER_BUDGET";
    }

    private String limitations(Decision decision) {
        String prefix = products.size() == 1 ? "当前只有一个候选，缺少横向比较。" : "当前结论仅限本轮目录候选。";
        return prefix + "具体用途适配性仍需结合规格或实测核实，不能据此认定全市场最优。"
                + (decision.limitations().contains("NO_PHOTO_BENCHMARK") ? "缺少拍照专项评分或实拍对比。" : "");
    }

    private static List<String> enumList(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isArray() || node.size() > allowed.size()) throw new IllegalArgumentException("Expected evidence/limitation array");
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || !allowed.contains(item.textValue()) || result.contains(item.textValue())) throw new IllegalArgumentException("Invalid reference");
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static BigDecimal amount(Object value) {
        if (value == null) return null;
        try { BigDecimal amount = new BigDecimal(value.toString()); return amount.signum() < 0 ? null : amount; }
        catch (NumberFormatException e) { return null; }
    }
    private static Long count(Object value) {
        BigDecimal number = amount(value);
        try { return number == null ? null : number.longValueExact(); }
        catch (ArithmeticException e) { return null; }
    }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private static String money(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
