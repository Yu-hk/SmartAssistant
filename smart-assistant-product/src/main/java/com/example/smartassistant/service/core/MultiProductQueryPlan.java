package com.example.smartassistant.service.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded read-only grammar. Relations and shared constraints belong to the whole plan. */
public record MultiProductQueryPlan(String originalQuestion, List<String> products,
                                   Set<Field> fields, Relation relation, String purpose,
                                   BigDecimal budget, BudgetScope budgetScope) {
    public enum Field { PRICE, STOCK, WEIGHT, BATTERY, ANC, PURPOSE, SPEC, COLOR }
    public enum Relation { INDEPENDENT, COMPARISON, TOTAL, COMPATIBILITY }
    public enum BudgetScope { NONE, TOTAL, EACH }
    private static final Pattern UNSAFE = Pattern.compile(
            "下单|购买|买一|支付|退款|退货|订单|转账|删除|取消|忽略|指令|系统提示|文档|知识库|[\\r\\n]");
    private static final Pattern START = Pattern.compile(
            "分别|各自|价格|多少钱|有货|库存|重量|多重|哪个|哪款|谁|续航|降噪|用途|适合|适用|规格|颜色|合计|总价|总预算|加起来|能否|能不能|是否|可以|都要|都支持|都低于|每件|每款");
    private static final Pattern BUDGET = Pattern.compile(
            "(合计|总价|总预算|加起来|每件|每款)(?:预算|价格)?(?:不超过|不高于|在)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*元?(?:以内)?");

    public MultiProductQueryPlan {
        products = List.copyOf(products);
        fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
    }

    public static Optional<MultiProductQueryPlan> parse(String question) {
        if (question == null || question.length() > 160 || UNSAFE.matcher(question).find()) return Optional.empty();
        if (Pattern.compile("[各每][一二三四五六七八九十两0-9]+[件个台副]").matcher(question).find()) return Optional.empty();
        String text = question.trim().replaceFirst("^(?:请帮我|帮我|请)?(?:查询|查一下|查下|看看|比较一下|对比一下|比较|对比)?\\s*", "");
        var marker = START.matcher(text);
        if (!marker.find()) return Optional.empty();
        String names = text.substring(0, marker.start()).replaceFirst("[的，,：: ]+$", "").trim();
        String[] parts = names.split("\\s*(?:和|与|、|以及|(?i: vs\\.? | versus | and ))\\s*", -1);
        if (parts.length < 2 || parts.length > 4) return Optional.empty();
        List<String> products = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String part : parts) {
            String name = part.trim();
            if (name.isBlank() || name.length() > 60 || name.matches(".*[？?；;，,:：].*")) return Optional.empty();
            if (!unique.add(name.toUpperCase(Locale.ROOT))) return Optional.empty();
            products.add(name);
        }
        String tail = text.substring(marker.start());
        EnumSet<Field> fields = EnumSet.noneOf(Field.class);
        if (contains(tail, "价格", "多少钱", "便宜", "贵", "合计", "总价", "总预算", "加起来", "每件", "每款")) fields.add(Field.PRICE);
        if (contains(tail, "有货", "库存")) fields.add(Field.STOCK);
        if (contains(tail, "重量", "多重", "轻")) fields.add(Field.WEIGHT);
        if (tail.contains("续航")) fields.add(Field.BATTERY);
        if (tail.contains("降噪")) fields.add(Field.ANC);
        if (contains(tail, "用途", "适合", "适用")) fields.add(Field.PURPOSE);
        if (tail.contains("规格")) fields.add(Field.SPEC);
        if (tail.contains("颜色")) fields.add(Field.COLOR);
        boolean compatibility = contains(tail, "一起使用", "兼容", "配合使用");
        if (compatibility) fields.add(Field.SPEC);
        Relation relation = compatibility ? Relation.COMPATIBILITY
                : contains(tail, "合计", "总价", "总预算", "加起来") ? Relation.TOTAL
                : contains(question, "哪个", "哪款", "谁", "比较", "对比", "更", "差多少") ? Relation.COMPARISON : Relation.INDEPENDENT;
        BigDecimal budget = null;
        BudgetScope scope = BudgetScope.NONE;
        var budgetMatcher = BUDGET.matcher(tail);
        if (budgetMatcher.find()) {
            budget = new BigDecimal(budgetMatcher.group(2));
            scope = Set.of("每件", "每款").contains(budgetMatcher.group(1)) ? BudgetScope.EACH : BudgetScope.TOTAL;
            if (budgetMatcher.find()) return Optional.empty(); // Multiple conflicting limits need the normal planner.
        }
        String purpose = List.of("通勤", "办公", "学习", "旅行", "运动", "游戏").stream()
                .filter(tail::contains).findFirst().orElse("");
        // Reject unparsed clauses, quantities and numeric conditions instead of silently losing them.
        String remainder = BUDGET.matcher(tail).replaceAll("")
                .replaceAll("一起使用|配合使用|主动降噪|分别|各自|价格|多少钱|有货|库存|重量|多重|哪个|哪款|谁|续航|降噪|用途|适合|适用人群|适用|规格|颜色|合计|总价|总预算|加起来|能否|能不能|是否|可以|都要|都支持|通勤|办公|学习|旅行|运动|游戏|便宜|差多少|兼容|对比|比较", "")
                .replaceAll("[的是多少什么如何怎么样呢吗各都和与及还更最长短轻贵好支持有无不？?，,。；;：:、\\s]", "");
        if (!remainder.isEmpty() || fields.isEmpty()) return Optional.empty();
        return Optional.of(new MultiProductQueryPlan(question, products, fields, relation, purpose, budget, scope));
    }

    private static boolean contains(String value, String... words) {
        return Arrays.stream(words).anyMatch(value::contains);
    }
}
