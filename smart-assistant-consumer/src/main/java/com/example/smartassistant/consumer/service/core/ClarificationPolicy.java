package com.example.smartassistant.consumer.service.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Server-owned schema: neither the model nor the browser defines validation rules. */
public final class ClarificationPolicy {
    private ClarificationPolicy() { }
    public record Field(String key, String label, String type, String unit, String value,
                        String min, String max, int decimals, int maxLength, String hint) { }
    private static final Map<String, Field> FIELDS = Map.ofEntries(
            Map.entry("weight", new Field("weight", "重量上限", "number", "公斤", "", "0.001", "1000", 3, 20, "请填写公斤数，最多 3 位小数")),
            Map.entry("budget", new Field("budget", "预算上限", "number", "元", "", "0.01", "100000000", 2, 20, "请填写人民币预算，最多 2 位小数")),
            Map.entry("quantity", new Field("quantity", "购买数量", "number", "件", "", "1", "10000", 0, 10, "请填写整数件数")),
            Map.entry("city", new Field("city", "城市", "text", "", "", null, null, 0, 40, "请填写城市名称")),
            Map.entry("orderNumber", new Field("orderNumber", "订单号", "text", "", "", null, null, 0, 69, "以 ORD- 或 BULK- 开头的订单编号")),
            Map.entry("product", new Field("product", "商品名称或类型", "text", "", "", null, null, 0, 100, "请填写商品名称或类型")),
            Map.entry("recipientName", new Field("recipientName", "收货人姓名", "text", "", "", null, null, 0, 40, "请填写收货人姓名")),
            Map.entry("recipientPhone", new Field("recipientPhone", "联系电话", "text", "", "", null, null, 0, 11, "请填写 11 位中国大陆手机号")),
            Map.entry("shippingAddress", new Field("shippingAddress", "收货地址", "text", "", "", null, null, 0, 200, "请填写收货地址")),
            Map.entry("reason", new Field("reason", "具体原因", "text", "", "", null, null, 0, 200, "请填写具体原因")),
            Map.entry("afterSalesType", new Field("afterSalesType", "售后类型", "text", "", "", null, null, 0, 4, "填写：退货、换货或维修")));
    public static Field field(String key) { return FIELDS.get(key); }
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
                if (!value.matches("(?:ORD|BULK)-[A-Za-z0-9-]{1,64}")) throw invalid();
            } else if ("recipientPhone".equals(key)) {
                if (!value.matches("1[3-9][0-9]{9}")) throw invalid();
            } else if ("afterSalesType".equals(key)) {
                if (!Set.of("退货", "换货", "维修").contains(value)) throw invalid();
            } else {
                if (!value.matches("[\\p{L}\\p{N} .·（）()＋+/,，。-]+")
                        || Pattern.compile("下单|付款|确认|同意|忽略|执行|删除").matcher(value).find()) throw invalid();
            }
            parts.add(("budget".equals(key) ? "预算" : field.label()) + "为" + value + field.unit());
        }
        return "补充信息：" + String.join("；", parts) + "。";
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("请检查补充信息的格式和范围，不要在字段中填写操作指令。");
    }
}
