package com.example.smartassistant.service.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.smartassistant.service.core.MultiProductQueryPlan.*;

/** Entity-scoped fields and constraints, separate from whole-question relations. */
public record ProductSemanticQueryPlan(MultiProductQueryPlan aggregate, List<Task> tasks, String unsupported) {
    public record Condition(Field field, String operator, BigDecimal number, String unit, Boolean flag) { }
    public record Task(ProductEntityResolver.Mention entity, Set<Field> fields, int quantity, List<Condition> conditions) {
        public Task { fields = Set.copyOf(fields); conditions = List.copyOf(conditions); }
    }
    private static final Pattern UNSAFE = Pattern.compile("订单|下单|购买|买一|支付|退款|退货|转账|删除|取消|忽略|指令|系统提示|文档|知识库|[\\r\\n]");
    private static final Pattern FIELD = Pattern.compile("多少钱|价格|售价|便宜|贵|重量|多重|轻|续航|降噪|颜色|规格|参数|用途|适合|适用|库存|有货|合计|总价|总预算|加起来|每款|每件|兼容|一起使用");
    private static final Pattern QUANTITY = Pattern.compile("(?:各|每款|每件)?\\s*([一二三四五六七八九两]|[1-9][0-9]?)\\s*[件个台副]");
    private static final Pattern LIMIT = Pattern.compile("(价格|售价|重量|续航)(?:是|要|应|需要)?\\s*(不超过|不高于|低于|少于|至少|不低于|超过|大于)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(元|克|千克|公斤|小时|g|kg|h)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUDGET = Pattern.compile("(合计|总价|总预算|加起来|每款|每件)(?:预算|价格)?\\s*(?:不超过|不高于|低于|在)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*元?(?:以内)?");

    public static boolean maySupport(String question) {
        String current = ProductQueryContext.current(question);
        return current.length() <= 160 && !UNSAFE.matcher(current).find() && FIELD.matcher(current).find();
    }
    public static Optional<ProductSemanticQueryPlan> parse(String question, List<ProductEntityResolver.Mention> mentions) {
        if (!maySupport(question) || mentions.isEmpty()) return Optional.empty();
        EnumSet<Field> union = fields(question);
        if (union.isEmpty()) return Optional.empty();
        String unsupported = mentions.size() > 4 ? "一次最多核对四款商品，请拆成两次咨询" : "";
        List<Task> tasks = new ArrayList<>();
        int sharedQuantity = 1;
        var shared = Pattern.compile("各([一二三四五六七八九两]|[1-9][0-9]?)[件个台副]").matcher(question);
        if (shared.find()) sharedQuantity = count(shared.group(1));
        // A contiguous list before any field has shared scope. A field between mentions closes that group.
        for (int groupStart = 0; groupStart < mentions.size();) {
            int groupEnd = groupStart;
            while (groupEnd + 1 < mentions.size()
                    && (mentions.get(groupEnd).end() > mentions.get(groupEnd + 1).start()
                        || !FIELD.matcher(question.substring(mentions.get(groupEnd).end(), mentions.get(groupEnd + 1).start())).find())) groupEnd++;
            int until = groupEnd + 1 < mentions.size() ? mentions.get(groupEnd + 1).start() : question.length();
            String clause = question.substring(mentions.get(groupEnd).end(), until);
            EnumSet<Field> groupFields = fields(clause);
            if (groupFields.isEmpty()) groupFields = fields(question.substring(0, mentions.get(groupStart).start()));
            if (groupFields.isEmpty()) { unsupported = "请明确每款商品要查询的字段"; groupFields = EnumSet.copyOf(union); }
            List<Condition> conditions = new ArrayList<>();
            var limits = LIMIT.matcher(clause);
            while (limits.find()) {
                Field field = fields(limits.group(1)).iterator().next();
                BigDecimal number = new BigDecimal(limits.group(3));
                String unit = Objects.toString(limits.group(4), "").toLowerCase(Locale.ROOT);
                if (field == Field.WEIGHT && Set.of("kg", "千克", "公斤").contains(unit)) number = number.multiply(new BigDecimal("1000"));
                boolean validUnit = switch (field) {
                    case PRICE -> unit.isEmpty() || unit.equals("元");
                    case WEIGHT -> Set.of("克", "千克", "公斤", "g", "kg").contains(unit);
                    case BATTERY -> Set.of("小时", "h").contains(unit);
                    default -> false;
                };
                if (!validUnit) unsupported = "条件的单位尚不明确，请补充元、克或小时";
                conditions.add(new Condition(field, limits.group(2), number, unit, null));
            }
            if (clause.contains("支持主动降噪") || clause.contains("支持降噪"))
                conditions.add(new Condition(Field.ANC, "EQUAL", null, "", !clause.contains("不支持")));
            for (int index = groupStart; index <= groupEnd; index++) {
                var mention = mentions.get(index);
                int end = index + 1 < mentions.size() ? mentions.get(index + 1).start() : question.length();
                String suffix = question.substring(mention.end(), Math.max(mention.end(), end));
                var quantity = QUANTITY.matcher(suffix);
                int n = sharedQuantity;
                if (quantity.find()) n = count(quantity.group(1));
                if (quantity.find()) unsupported = "同一商品出现多个数量，请核实";
                tasks.add(new Task(mention, groupFields, n, conditions));
            }
            groupStart = groupEnd + 1;
        }
        BigDecimal budget = null; BudgetScope scope = BudgetScope.NONE;
        var budgets = BUDGET.matcher(question);
        if (budgets.find()) {
            budget = new BigDecimal(budgets.group(2));
            scope = Set.of("每款", "每件").contains(budgets.group(1)) ? BudgetScope.EACH : BudgetScope.TOTAL;
            if (budgets.find()) unsupported = "包含多个预算条件，请分别确认每款与合计预算";
        }
        Relation relation = question.matches(".*(?:兼容|一起使用).*" ) ? Relation.COMPATIBILITY
                : question.matches(".*(?:合计|总价|总预算|加起来).*" ) ? Relation.TOTAL
                : question.matches(".*(?:比较|对比|哪个|哪款|更|差多少).*" ) ? Relation.COMPARISON : Relation.INDEPENDENT;
        String purpose = List.of("通勤", "办公", "学习", "旅行", "运动", "游戏").stream().filter(question::contains).findFirst().orElse("");
        // Unknown requirements must be visible, not silently stripped out of a fact response.
        StringBuilder masked = new StringBuilder(question);
        for (var mention : mentions) for (int i = mention.start(); i < mention.end(); i++) masked.setCharAt(i, ' ');
        String residual = masked.toString().replaceFirst("不是[^，,；;]{1,30}[，,；;]\\s*(?:而是|是)", "");
        residual = BUDGET.matcher(residual).replaceAll("");
        residual = LIMIT.matcher(residual).replaceAll("");
        residual = QUANTITY.matcher(residual).replaceAll("");
        residual = FIELD.matcher(residual).replaceAll("")
                .replaceAll("请帮我|帮我|查一下|查询|查下|想了解|了解一下|了解|想知道|告诉我|分别|各自|一起使用|主动|支持|每款|每件|通勤|办公|学习|旅行|运动|游戏|哪款|哪个|差多少|比较|对比|至少|不超过|不高于|合计|总价|总预算|加起来|以内|是多少|是什么|多少|有哪些|怎么样|如何|看看|不支持|能不能|能否|是否|可以", "")
                .replaceAll("[的我请想要是有无不和与及各都最更呢吗好长短轻贵第款两它这那前面，,。？?；;：:、\\s]", "");
        if (!residual.isBlank()) unsupported = "还有未能可靠解析的条件，请核实：" + residual.substring(0, Math.min(40, residual.length()));
        return Optional.of(new ProductSemanticQueryPlan(new MultiProductQueryPlan(question,
                mentions.stream().map(ProductEntityResolver.Mention::surface).toList(), union, relation, purpose, budget, scope), tasks, unsupported));
    }
    private static EnumSet<Field> fields(String text) {
        EnumSet<Field> result = EnumSet.noneOf(Field.class);
        var matcher = FIELD.matcher(text);
        while (matcher.find()) result.add(switch (matcher.group()) {
            case "重量", "多重", "轻" -> Field.WEIGHT; case "续航" -> Field.BATTERY; case "降噪" -> Field.ANC;
            case "颜色" -> Field.COLOR; case "规格", "参数", "兼容", "一起使用" -> Field.SPEC;
            case "用途", "适合", "适用" -> Field.PURPOSE; case "库存", "有货" -> Field.STOCK;
            default -> Field.PRICE;
        });
        return result;
    }
    private static int count(String value) {
        return value.equals("两") ? 2 : value.length() == 1 && "一二三四五六七八九".contains(value)
                ? "一二三四五六七八九".indexOf(value) + 1 : Integer.parseInt(value);
    }
}
