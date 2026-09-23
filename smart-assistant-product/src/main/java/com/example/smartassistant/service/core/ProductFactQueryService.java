package com.example.smartassistant.service.core;

import com.example.smartassistant.spi.ProductBackend;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.audit.ToolUsageCache;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Closed grammar: only complete, read-only field queries may bypass model planning. */
@Service
public class ProductFactQueryService {
    public static final String OPERATION = "RESOLVE_READ_ONLY_PRODUCT";
    private static final Pattern QUERY = Pattern.compile(
            "^(?:请|帮我|请帮我)?(?:查询|查一下|查下|看看)?(.{1,100}?)(?:的)?"
            + "(多少钱[？?，, ]*(?:有货吗[？?]?)?|价格(?:是多少|多少)?[？?，, ]*(?:有货吗[？?]?)?|"
            + "有货吗[？?]?|规格(?:是多少|有哪些|是什么|如何)?[？?]?|颜色(?:呢|是什么|有哪些)?[？?]?|"
            + "规格和颜色(?:分别是什么|都告诉我)?[？?]?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE = Pattern.compile(
            "订单|下单|购买|买一|退款|退货|支付|转账|删除|取消|然后|另外|顺便|忽略|指令|系统提示|知识库|资料|文档|[\\r\\n]");
    private final ProductBackend backend;
    public ProductFactQueryService(ProductBackend backend) { this.backend = backend; }
    private record Query(String name, String fields) { }
    private static Query parse(String value) {
        if (value == null || value.length() > 160 || UNSAFE.matcher(value).find()) return null;
        var match = QUERY.matcher(value.trim());
        return match.matches() ? new Query(match.group(1).trim(), match.group(2)) : null;
    }
    public AgentExecutionResponse query(String question, List<String> history, String requestId) {
        Query query = parse(question);
        if (query == null) return unhandled();
        String name = query.name();
        if (Set.of("这个", "它", "这款", "那个").contains(name)) {
            name = null;
            List<String> recent = history == null ? List.of() : history;
            for (int i = recent.size() - 1; i >= Math.max(0, recent.size() - 10); i--) {
                String entry = recent.get(i);
                if (entry == null || !(entry.startsWith("用户：") || entry.startsWith("用户:"))) continue;
                Query previous = parse(entry.substring(3));
                if (previous == null) return unhandled(); // Do not jump backwards over a topic switch.
                if (!Set.of("这个", "它", "这款", "那个").contains(previous.name())) {
                    name = previous.name(); break;
                }
            }
            if (name == null) return unhandled();
        }
        long start = System.nanoTime();
        ProductBackend.FactLookup found;
        try {
            found = backend.lookupFacts(name);
            if (found != null) ToolUsageCache.record(requestId, "queryProductInfo", true, (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException error) {
            ToolUsageCache.record(requestId, "queryProductInfo", false, (System.nanoTime() - start) / 1_000_000);
            return AgentExecutionResponse.failure("PRODUCT_CATALOG_UNAVAILABLE",
                    "抱歉，商品信息暂时无法查询，请稍后再试。", true);
        }
        if (found == null) return unhandled();
        if (found.ambiguous()) return handled("查到了多个版本，请告诉我具体型号或代际，我再帮您核对。", List.of(), true);
        if (found.products().size() != 1) return unhandled();
        var product = found.products().getFirst();
        List<String> parts = new ArrayList<>();
        String fields = query.fields();
        if (fields.startsWith("多少钱") || fields.startsWith("价格")) parts.add(product.price() == null
                ? "价格暂未确认" : "目前售价为 " + product.price().stripTrailingZeros().toPlainString() + " 元");
        if (fields.contains("有货")) parts.add(switch (Objects.toString(product.stock(), "")) {
            case "充足" -> "库存充足"; case "紧张" -> "库存紧张"; case "缺货", "无货", "售罄" -> "暂时缺货";
            default -> "库存状态暂未确认";
        });
        if (fields.contains("规格")) {
            // Legacy prose may embed unrelated fields: do not silently strip facts or claim exact scope.
            if (product.spec() != null && product.spec().matches("(?s).*(颜色|配色|售价|库存|商品编码|SKU).*")) return unhandled();
            parts.add("规格为" + known(product.spec()));
        }
        if (fields.contains("颜色")) parts.add("颜色为" + known(product.color()));
        var response = handled(product.name() + "，" + String.join("；", parts) + "。",
                List.of(Map.of("name", product.name())), false);
        Map<String, Object> data = new LinkedHashMap<>(response.data());
        // Internal quote is catalog-derived; never trust an amount supplied in natural language.
        if (product.price() != null && product.price().signum() > 0
                && Set.of("充足", "紧张").contains(Objects.toString(product.stock(), "")))
            data.put("orderQuote", Map.of("productName", product.name(), "amount", product.price()));
        return AgentExecutionResponse.success(response.answer(), data, DomainQualityResult.pass(1, "PRODUCT_CATALOG_FACTS"));
    }
    private static String known(String value) { return value == null || value.isBlank() ? "暂未提供" : value; }
    private static AgentExecutionResponse handled(String answer, List<?> products, boolean clarification) {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("handled", true, "deterministic", true,
                "products", products, "clarificationRequired", clarification));
        if (clarification) data.put("clarificationRequest",
                new com.example.smartassistant.common.agent.protocol.ClarificationRequest("product", "QUERY_PRODUCT", List.of("product")).toMap());
        return AgentExecutionResponse.success(answer, data,
                DomainQualityResult.pass(1.0, "PRODUCT_CATALOG_FACTS"));
    }
    private static AgentExecutionResponse unhandled() {
        return AgentExecutionResponse.success("", Map.of("handled", false, "deterministic", true), DomainQualityResult.unknown());
    }
}
