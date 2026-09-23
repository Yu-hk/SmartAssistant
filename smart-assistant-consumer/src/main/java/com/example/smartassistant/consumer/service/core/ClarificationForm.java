package com.example.smartassistant.consumer.service.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Presentation-only projection of explicit parameter questions. Never grants business approval. */
public record ClarificationForm(int version, List<Field> fields) {
    public record Field(String key, String label, String type, String unit, String value) {}

    private static final Pattern ASK = Pattern.compile(
            "请(?:您)?(?:提供|补充|告诉|填写|明确|问|确认)|还需要|还想确认|需要您提供|你想选购|您(?:希望|想查(?:询)?|可接受)");

    public static ClarificationForm fromReply(String reply, String question, String status) {
        if (reply == null || reply.length() > 4000
                || !("SUCCESS".equals(status) || "COMPLETED".equals(status) || "CLARIFICATION".equals(status))) return null;
        List<Field> fields = new ArrayList<>();
        for (String sentence : reply.split("[。！？!?\\n]")) {
            var ask = ASK.matcher(sentence);
            if (!ask.find() || sentence.matches(".*(?:如果|若您|如需|是否需要|还想了解).*")) continue;
            sentence = sentence.substring(ask.start());
            if (sentence.replace("商品名称或类型", "商品类型").replace("城市或地区", "城市").contains("或")) continue;
            add(fields, sentence, "weight", "重量上限", "number", "公斤", "重量(?:上限|不超过)|可接受的重量", question);
            add(fields, sentence, "budget", "预算上限", "number", "元", "预算", question);
            add(fields, sentence, "city", "城市", "text", "", "城市", question);
            add(fields, sentence, "orderNumber", "订单号", "text", "", "订单号", question);
            add(fields, sentence, "product", "商品名称或类型", "text", "", "商品名称|商品类型|商品品类|哪类商品", question);
            add(fields, sentence, "quantity", "购买数量", "number", "件", "购买数量|商品数量|几件", question);
        }
        return fields.isEmpty() ? null : new ClarificationForm(1, List.copyOf(fields));
    }

    private static void add(List<Field> fields, String sentence, String key, String label,
                            String type, String unit, String expression, String question) {
        if (!Pattern.compile(expression).matcher(sentence).find()
                || fields.stream().anyMatch(field -> field.key().equals(key))) return;
        // Prefill only unambiguous values explicitly supplied in this turn, never examples in the answer.
        String value = "";
        String pattern = switch (key) {
            case "weight" -> "重量(?:上[限线]|不超过)(?:为|是|设为)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:公斤|千克|kg)";
            case "budget" -> "预算(?:上限|改为|调整为)?(?:为|是)?\\s*(\\d+(?:\\.\\d+)?)(?=\\s*(?:元|以内|[，,。；;]|$))";
            default -> null;
        };
        if (pattern != null && question != null) {
            var matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(question);
            if (matcher.find()) { value = matcher.group(1); if (matcher.find()) value = ""; }
        }
        fields.add(new Field(key, label, type, unit, value));
    }
}
