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
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<ProductBackend.ProductFact> products = new ArrayList<>();
        boolean clarification = false;
        for (String name : plan.products()) {
            long started = System.nanoTime();
            ProductBackend.FactLookup lookup;
            try {
                lookup = backend.lookupFacts(name);
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
            if (products.stream().anyMatch(p -> Objects.equals(p.code(), product.code()))) {
                clarification = true;
                lines.add(name + "：与前面的名称指向同一商品，请确认是比较不同型号，还是需要多件数量。");
                evidence.add(Map.of("requestedProduct", name, "status", "DUPLICATE_IDENTITY", "fields", Map.of()));
                continue;
            }
            products.add(product);
            Map<String, Object> facts = new LinkedHashMap<>();
            List<String> statements = new ArrayList<>();
            for (Field field : plan.fields()) {
                String value = value(product, field);
                facts.put(field.name(), Map.of("known", value != null, "evidence", value == null ? "" : value));
                statements.add(value == null ? label(field) + "资料尚未核实" : value);
            }
            lines.add(product.name() + "：" + String.join("；", statements) + "。");
            evidence.add(Map.of("requestedProduct", name, "productName", product.name(), "status", "RESOLVED", "fields", facts));
        }
        boolean complete = products.size() == plan.products().size();
        if (plan.relation() == Relation.TOTAL || plan.budgetScope() == BudgetScope.TOTAL) {
            if (complete && products.stream().allMatch(MultiProductQueryService::priced)) {
                BigDecimal total = products.stream().map(ProductBackend.ProductFact::price).reduce(BigDecimal.ZERO, BigDecimal::add);
                lines.add("按每款各 1 件计算，目录价格合计 " + number(total) + " 元（未包含未核实的运费或优惠）"
                        + (plan.budget() == null ? "。" : "，" + (total.compareTo(plan.budget()) <= 0 ? "未超过" : "超过")
                        + "总预算 " + number(plan.budget()) + " 元。"));
            } else lines.add("商品型号或价格尚未全部确认，暂不能核算总价和总预算。");
        }
        if (plan.budgetScope() == BudgetScope.EACH) {
            for (var p : products) lines.add(p.name() + (priced(p)
                    ? "：" + (p.price().compareTo(plan.budget()) <= 0 ? "符合" : "超过") + "每款 " + number(plan.budget()) + " 元的预算。"
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
        data.put("multiProductQueryVersion", 1);
        data.put("clarificationRequired", clarification);
        data.put("queryPlan", Map.of("originalQuestion", plan.originalQuestion(), "products", plan.products(), "fields", plan.fields().stream().map(Enum::name).toList(),
                "relation", plan.relation().name(), "purpose", plan.purpose(), "budgetScope", plan.budgetScope().name(),
                "budget", plan.budget() == null ? "" : number(plan.budget())));
        data.put("productEvidence", evidence);
        data.put("products", products.stream().map(MultiProductQueryService::catalogData).toList());
        data.put("productCount", products.size());
        data.put("productCoverage", Map.of("requested", plan.products().size(), "resolved", products.size()));
        if (clarification) data.put("clarificationRequest", new ClarificationRequest("product", "QUERY_PRODUCT", List.of("product")).toMap());
        return AgentExecutionResponse.success(String.join("\n", lines), data,
                DomainQualityResult.pass(1, "MULTI_PRODUCT_CATALOG_FACTS"));
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
