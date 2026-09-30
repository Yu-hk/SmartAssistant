package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.spi.ProductBackend;
import com.example.smartassistant.spi.ProductFeatures;
import java.math.BigDecimal;
import java.util.*;
import static com.example.smartassistant.service.core.MultiProductQueryPlan.*;

/** Read-only per-product retrieval and deterministic aggregation. Never creates an order quote. */
public final class MultiProductQueryService {
    private final ProductBackend backend;
    public MultiProductQueryService(ProductBackend backend) { this.backend = backend; }

    public AgentExecutionResponse query(MultiProductQueryPlan plan, String requestId) {
        return query(plan, null, requestId);
    }

    public AgentExecutionResponse query(ProductSemanticQueryPlan semantic, String requestId) {
        return query(semantic.aggregate(), semantic, requestId);
    }

    private AgentExecutionResponse query(MultiProductQueryPlan plan, ProductSemanticQueryPlan semantic, String requestId) {
        boolean strictBudget = semantic != null && plan.originalQuestion().matches(".*(?:合计|总价|总预算|加起来|每款|每件)(?:预算|价格)?\\s*低于.*");
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<ProductBackend.ProductFact> products = new ArrayList<>();
        boolean clarification = semantic != null && !semantic.unsupported().isBlank();
        List<Integer> quantities = new ArrayList<>();
        for (int index = 0; index < plan.products().size(); index++) {
            String name = plan.products().get(index);
            var task = semantic == null ? null : semantic.tasks().get(index);
            if (task != null && task.entity().status() != ProductEntityResolver.Status.RESOLVED) {
                clarification = true;
                String options = task.entity().candidates().stream().map(c -> c.name() + " [" + c.code() + "]")
                        .reduce((a, b) -> a + "、" + b).orElse("");
                lines.add(name + "：" + (task.entity().status() == ProductEntityResolver.Status.NOT_FOUND
                        ? "未找到准确匹配，请核实名称或型号。" : "需要核实具体商品，不能自动选定型号。")
                        + (options.isBlank() ? "" : "候选：" + options));
                evidence.add(Map.of("requestedProduct", name, "status", task.entity().status().name(), "fields", Map.of()));
                continue;
            }
            long started = System.nanoTime();
            ProductBackend.FactLookup lookup;
            try {
                lookup = backend.lookupFacts(task == null ? name : task.entity().code());
                ToolUsageCache.record(requestId, "queryProductInfo", lookup != null,
                        (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException unavailable) {
                ToolUsageCache.record(requestId, "queryProductInfo", false, (System.nanoTime() - started) / 1_000_000);
                return AgentExecutionResponse.failure("PRODUCT_CATALOG_UNAVAILABLE", "抱歉，商品信息暂时无法查询，请稍后再试。", true);
            }
            if (lookup == null) return AgentExecutionResponse.success("", Map.of("handled", false), DomainQualityResult.unknown());
            if (lookup.ambiguous() || lookup.products().size() != 1) {
                clarification = true;
                String status = lookup.ambiguous() ? "AMBIGUOUS" : "NOT_FOUND";
                lines.add(name + (lookup.ambiguous() ? "：匹配到多个版本，请补充具体型号或代际。" : "：未找到准确匹配，请核实商品名称或型号。"));
                evidence.add(Map.of("requestedProduct", name, "status", status, "fields", Map.of()));
                continue;
            }
            var product = lookup.products().getFirst();
            if (task != null && !Objects.equals(task.entity().code(), product.code())) {
                clarification = true;
                lines.add(name + "：目录身份发生变化，请重新核实型号。");
                evidence.add(Map.of("requestedProduct", name, "status", "IDENTITY_CHANGED", "fields", Map.of()));
                continue;
            }
            if (products.stream().anyMatch(p -> Objects.equals(p.code(), product.code()))) {
                clarification = true;
                lines.add(name + "：与前面的名称指向同一商品，请确认是比较不同型号，还是需要多件数量。");
                evidence.add(Map.of("requestedProduct", name, "status", "DUPLICATE_IDENTITY", "fields", Map.of()));
                continue;
            }
            products.add(product);
            quantities.add(task == null ? 1 : task.quantity());
            Map<String, Object> facts = new LinkedHashMap<>();
            List<String> statements = new ArrayList<>();
            for (Field field : Field.values()) {
                if (!(task == null ? plan.fields() : task.fields()).contains(field)) continue;
                String value = value(product, field);
                facts.put(field.name(), Map.of("known", value != null, "evidence", value == null ? "" : value));
                statements.add(value == null ? label(field) + "资料尚未核实" : value);
            }
            if (task != null) for (var condition : task.conditions()) {
                Boolean satisfied = satisfies(product, condition);
                if (satisfied == null) clarification = true;
                facts.put("CONDITION_" + condition.field(), Map.of("known", satisfied != null,
                        "satisfied", satisfied == null ? "UNKNOWN" : satisfied,
                        "operator", condition.operator(), "number", Objects.toString(condition.number(), ""), "unit", condition.unit()));
                statements.add(label(condition.field()) + "条件" + (satisfied == null ? "尚无法核实" : satisfied ? "符合" : "不符合"));
            }
            lines.add(product.name() + "：" + String.join("；", statements) + "。");
            evidence.add(Map.of("requestedProduct", name, "productName", product.name(), "productCode", product.code(),
                    "quantity", task == null ? 1 : task.quantity(), "status", "RESOLVED", "fields", facts));
        }
        boolean complete = products.size() == plan.products().size() && (semantic == null || semantic.unsupported().isBlank());
        if (plan.relation() == Relation.TOTAL || plan.budgetScope() == BudgetScope.TOTAL) {
            if (complete && products.stream().allMatch(MultiProductQueryService::priced)) {
                BigDecimal total = BigDecimal.ZERO;
                for (int i = 0; i < products.size(); i++) total = total.add(products.get(i).price().multiply(BigDecimal.valueOf(quantities.get(i))));
                String quantityText = quantities.stream().allMatch(n -> n == 1) ? "每款各 1 件" : "您明确给出的数量（"
                        + java.util.stream.IntStream.range(0, products.size()).mapToObj(i -> products.get(i).name() + " " + quantities.get(i) + " 件")
                            .reduce((a, b) -> a + "、" + b).orElse("") + "）";
                lines.add("按" + quantityText + "计算，目录价格合计 " + number(total) + " 元（未包含未核实的运费或优惠）"
                        + (plan.budget() == null ? "。" : "，" + (strictBudget ? (total.compareTo(plan.budget()) < 0 ? "符合" : "不符合低于")
                            : (total.compareTo(plan.budget()) <= 0 ? "未超过" : "超过"))
                        + "总预算 " + number(plan.budget()) + " 元。"));
            } else lines.add("商品型号或价格尚未全部确认，暂不能核算总价和总预算。");
        }
        if (plan.budgetScope() == BudgetScope.EACH) {
            for (var p : products) lines.add(p.name() + (priced(p)
                    ? "：" + ((strictBudget ? p.price().compareTo(plan.budget()) < 0 : p.price().compareTo(plan.budget()) <= 0) ? "符合" : strictBudget ? "不符合" : "超过")
                        + "每款 " + number(plan.budget()) + " 元的预算" + (strictBudget ? "（严格低于）" : "") + "。"
                    : "：价格未知，无法核对单款预算。"));
        }
        if (plan.relation() == Relation.COMPARISON) {
            if (complete && plan.fields().contains(Field.PRICE) && !plan.fields().contains(Field.PURPOSE)
                    && products.stream().allMatch(MultiProductQueryService::priced)) {
                var min = products.stream().min(Comparator.comparing(ProductBackend.ProductFact::price)).orElseThrow();
                var max = products.stream().max(Comparator.comparing(ProductBackend.ProductFact::price)).orElseThrow();
                lines.add(min.price().compareTo(max.price()) == 0 ? "这些商品的目录价格相同。"
                        : "目录价格最低为 " + number(min.price()) + " 元，最高与最低相差 " + number(max.price().subtract(min.price())) + " 元。");
            }
            if (plan.fields().contains(Field.BATTERY) && products.stream().map(p -> p.features().batteryLifeScenario()).distinct().count() > 1)
                lines.add("续航测试场景不同，不能直接用小时数判断哪款续航更长。");
            if (plan.fields().contains(Field.PURPOSE)) lines.add("以上用途为目录标注；仅凭这些标签尚不能确认哪款更适合"
                    + (plan.purpose().isBlank() ? "您" : plan.purpose()) + "，还需结合佩戴方式、使用条件及您最看重的需求。");
        }
        if (plan.relation() == Relation.COMPATIBILITY)
            lines.add("现有目录规格不足以核实两者兼容性，不能据此承诺可以一起使用，请补充具体连接方式或兼容性资料。");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("handled", true);
        data.put("deterministic", true);
        if (plan.products().size() > 1) data.put("multiProductQueryVersion", 1);
        if (semantic != null) {
            data.put("productEntityResolutionVersion", 1);
            data.put("entityResolutions", semantic.tasks().stream().map(ProductSemanticQueryPlan.Task::entity).toList());
            data.put("entityTasks", semantic.tasks());
            data.put("unsupportedConditions", semantic.unsupported());
            if (!semantic.unsupported().isBlank()) lines.add(semantic.unsupported() + "。以上仅列出已核实字段，不代表满足全部要求。");
        }
        data.put("clarificationRequired", clarification);
        data.put("queryPlan", Map.of("originalQuestion", plan.originalQuestion(), "products", plan.products(), "fields", plan.fields().stream().map(Enum::name).toList(),
                "relation", plan.relation().name(), "purpose", plan.purpose(), "budgetScope", plan.budgetScope().name(),
                "budget", plan.budget() == null ? "" : number(plan.budget())));
        ProductFieldCoverage.Report fieldCoverage = ProductFieldCoverage.inspect(plan, semantic, evidence);
        data.put("fieldCoverage", fieldCoverage.toMap());
        if (fieldCoverage.missing() > 0) {
            clarification = true;
            lines.add("部分所需字段的查询证据未返回，请稍后核实；不能视为已经完整回答。");
        }
        data.put("clarificationRequired", clarification);
        data.put("productEvidence", evidence);
        Map<String, Object> manifest = new LinkedHashMap<>(ProductEvidenceTrace.catalog(requestId, plan, evidence, fieldCoverage, quantities));
        data.put("products", products.stream().map(MultiProductQueryService::catalogData).toList());
        data.put("productCount", products.size());
        data.put("productCoverage", Map.of("requested", plan.products().size(), "resolved", products.size()));
        if (semantic != null && !clarification && products.size() == 1 && semantic.tasks().getFirst().quantity() == 1) {
            var product = products.getFirst();
            if (priced(product) && Set.of("充足", "紧张").contains(Objects.toString(product.stock(), "")))
                data.put("orderQuote", Map.of("productName", product.name(), "amount", product.price()));
        }
        if (clarification) data.put("clarificationRequest", new ClarificationRequest("product", "QUERY_PRODUCT", List.of("product")).toMap());
        String answer = String.join("\n", lines);
        manifest.put("responseSha256", ProductEvidenceTrace.hash(answer));
        data.put("evidenceTrace", Collections.unmodifiableMap(manifest));
        return AgentExecutionResponse.success(answer, data,
                DomainQualityResult.pass(1, "MULTI_PRODUCT_CATALOG_FACTS"));
    }

    private static Boolean satisfies(ProductBackend.ProductFact p, ProductSemanticQueryPlan.Condition c) {
        if (c.field() == Field.ANC) return p.features().documented() && p.features().noiseCancelling() != null
                ? Objects.equals(p.features().noiseCancelling(), c.flag()) : null;
        BigDecimal fact = switch (c.field()) {
            case PRICE -> priced(p) ? p.price() : null;
            case WEIGHT -> p.features().documented() ? p.features().weightGrams() : null;
            // An unspecified test scenario cannot turn a battery claim into a filter.
            case BATTERY -> null;
            default -> null;
        };
        if (fact == null || c.number() == null) return null;
        int comparison = fact.compareTo(c.number());
        return switch (c.operator()) {
            case "不超过", "不高于" -> comparison <= 0;
            case "低于", "少于" -> comparison < 0;
            case "至少", "不低于" -> comparison >= 0;
            case "超过", "大于" -> comparison > 0;
            default -> null;
        };
    }

    private static boolean priced(ProductBackend.ProductFact p) { return p.price() != null && p.price().signum() > 0; }
    private static Map<String, Object> catalogData(ProductBackend.ProductFact p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", p.code()); data.put("name", p.name());
        data.put("price", p.price()); data.put("stock", p.stock());
        data.put("spec", p.spec()); data.put("features", p.features());
        data.put("suitability", p.suitability());
        return data;
    }
    private static String value(ProductBackend.ProductFact p, Field field) {
        ProductFeatures f = p.features();
        return switch (field) {
            case PRICE -> priced(p) ? "目录售价 " + number(p.price()) + " 元" : null;
            case STOCK -> switch (Objects.toString(p.stock(), "")) {
                case "充足" -> "库存充足"; case "紧张" -> "库存紧张";
                case "缺货", "无货", "售罄" -> "暂时缺货"; default -> null;
            };
            case WEIGHT -> f.documented() && f.weightGrams() != null ? "设备净重 " + number(f.weightGrams()) + " 克" : null;
            case BATTERY -> f.documented() && f.batteryLifeHours() != null && ProductFeatures.BATTERY_SCENARIOS.contains(f.batteryLifeScenario())
                    ? "标称续航 " + number(f.batteryLifeHours()) + " 小时（" + ProductFeatures.scenarioLabel(f.batteryLifeScenario()) + "）" : null;
            case ANC -> f.documented() && f.noiseCancelling() != null ? (f.noiseCancelling() ? "支持主动降噪" : "不支持主动降噪") : null;
            case PURPOSE -> p.suitability().declared() ? p.suitability().evidence() : null;
            case SPEC -> text(p.spec()) == null ? null : "规格：" + p.spec();
            case COLOR -> text(p.color()) == null ? null : "颜色：" + p.color();
        };
    }
    private static String label(Field field) {
        return switch(field) { case PRICE -> "价格"; case STOCK -> "库存"; case WEIGHT -> "重量";
            case BATTERY -> "分场景续航"; case ANC -> "主动降噪"; case PURPOSE -> "用途与适用人群";
            case SPEC -> "规格"; case COLOR -> "颜色"; };
    }
    private static String text(String value) { return value == null || value.isBlank() ? null : value; }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
