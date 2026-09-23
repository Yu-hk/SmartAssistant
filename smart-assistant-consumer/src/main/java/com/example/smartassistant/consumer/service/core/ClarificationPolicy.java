package com.example.smartassistant.consumer.service.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Server-owned schema: neither the model nor the browser defines validation rules. */
public final class ClarificationPolicy {
    private ClarificationPolicy() { }
    public record Field(String key, String label, String type, String unit, String value,
                        String min, String max, int decimals, int maxLength) { }
    private static final Map<String, Field> FIELDS = Map.of(
            "weight", new Field("weight", "重量上限", "number", "公斤", "", "0.001", "1000", 3, 20),
            "budget", new Field("budget", "预算上限", "number", "元", "", "0.01", "10000000", 2, 20),
            "quantity", new Field("quantity", "购买数量", "number", "件", "", "1", "10000", 0, 10),
            "city", new Field("city", "城市", "text", "", "", null, null, 0, 40),
            "orderNumber", new Field("orderNumber", "订单号", "text", "", "", null, null, 0, 68),
            "product", new Field("product", "商品名称或类型", "text", "", "", null, null, 0, 100));
    private static final Map<String, String> EVIDENCE = Map.of(
            "weight", "重量|多重|公斤|千克", "budget", "预算|价位|价格范围|多少钱以内",
            "quantity", "数量|几件|多少件|几台|多少台", "city", "城市|地区|所在地",
            "orderNumber", "订单号|订单编号", "product", "商品名称|商品类型|品类|哪款|哪类|什么商品");
    public static Field field(String key) { return FIELDS.get(key); }
    public static boolean supportedEvidence(String key, String evidence, String reply) {
        return FIELDS.containsKey(key) && evidence != null && evidence.length() >= 4
                && evidence.length() <= 300 && reply.contains(evidence)
                && Pattern.compile(EVIDENCE.get(key)).matcher(evidence).find()
                && Pattern.compile("[？?]|请|多少|哪|提供|补充|确认|告知|告诉|希望|想要").matcher(evidence).find()
                && !Pattern.compile("如果|如需|是否需要|不需要|无需|不用|可选").matcher(evidence).find();
    }
    public static String reply(List<String> keys, Map<String, String> values) {
        if (keys == null || keys.isEmpty() || keys.size() > 6 || values == null
                || !values.keySet().equals(new HashSet<>(keys))) throw invalid();
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            Field field = FIELDS.get(key);
            String raw = values.get(key);
            if (field == null || raw == null || raw.length() > field.maxLength()
                    || raw.chars().anyMatch(Character::isISOControl)) throw invalid();
            String value = raw.strip();
            if (value.isEmpty()) throw invalid();
            if ("number".equals(field.type())) {
                if (!value.matches("[0-9]+(?:\\.[0-9]+)?")) throw invalid();
                BigDecimal number = new BigDecimal(value);
                if (number.scale() > field.decimals() || number.compareTo(new BigDecimal(field.min())) < 0
                        || number.compareTo(new BigDecimal(field.max())) > 0) throw invalid();
                value = number.stripTrailingZeros().toPlainString();
            } else if ("orderNumber".equals(key)) {
                if (!value.matches("ORD-[A-Za-z0-9-]{1,64}")) throw invalid();
            } else {
                if (!value.matches("[\\p{L}\\p{N} .·（）()＋+/-]+")
                        || Pattern.compile("下单|退款|付款|确认|同意|忽略|执行|删除").matcher(value).find()) throw invalid();
            }
            parts.add(("budget".equals(key) ? "预算" : field.label()) + "为" + value + field.unit());
        }
        return "补充信息：" + String.join("；", parts) + "。";
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("请检查补充信息的格式和范围，不要在字段中填写操作指令。");
    }
}
