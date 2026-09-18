package com.example.smartassistant.router.service.core;

import org.springframework.stereotype.Component;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/** No stop-word filtering: negation and original word order are safety-critical. */
@Component
public class BusinessFallbackParser {
    public enum Kind { PRODUCT_QUERY, CREATE_ORDER, CANCEL_ORDER, REFUND_ORDER, CLARIFY, UNKNOWN }
    public record Parsed(Kind kind, String question, Map<String, Object> input, String reply) { }
    private static final Pattern UNSAFE = Pattern.compile("不|别|勿|如果|假如|是否|怎么|如何|然后|顺便|或者|以及|忽略|指令|系统|文档|资料|知识库|[\\r\\n?？]");
    private static final String ORDER_AND_REASON = "[：: ]*((?:ORD|BULK)-[A-Za-z0-9-]+)(?:[；;]\\s*原因[：:]([^；;\\r\\n]*))?[。！!]?$";
    private static final Pattern CANCEL = Pattern.compile("^(?:请|帮我|请帮我)?取消订单" + ORDER_AND_REASON, Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND = Pattern.compile("^(?:请|帮我|请帮我)?(?:申请退款|退款订单)" + ORDER_AND_REASON, Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_REASON = Pattern.compile("如果|假如|是否|怎么|如何|然后|顺便|或者|以及|忽略|指令|系统|文档|资料|知识库|下单|购买|取消|退款|退单|退货|(?i:ORD-|BULK-)|[\\p{Cntrl}：:？?]");
    public Parsed parse(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty() || q.length() > 500) return unknown(q);
        Set<String> tokens = new HashSet<>();
        try {
            IKSegmenter segmenter = new IKSegmenter(new StringReader(q), true);
            for (Lexeme token; (token = segmenter.next()) != null;) tokens.add(token.getLexemeText());
        } catch (Exception failure) { return unknown(q); }
        if (tokens.isEmpty()) return unknown(q);
        boolean write = tokens.stream().anyMatch(t -> Set.of("下单", "购买", "取消", "退款", "退单", "退货").contains(t))
                || q.matches(".*(下单|购买|取消订单|退款|退单|退货).*");
        if (!write) {
            if (tokens.stream().anyMatch(t -> Set.of("价格", "多少钱", "规格", "颜色", "库存", "有货", "货").contains(t))
                    || q.matches(".*(多少钱|有货吗|规格|颜色).*") )
                return new Parsed(Kind.PRODUCT_QUERY, q, Map.of(), null);
            return unknown(q);
        }
        // Parse a closed command before checking its data: “不喜欢” is a reason,
        // not a negation of “申请退款”. Never infer a reason from previous turns.
        var cancel = CANCEL.matcher(q);
        if (cancel.matches()) return afterSales(q, Kind.CANCEL_ORDER, cancel.group(1), cancel.group(2));
        var refund = REFUND.matcher(q);
        if (refund.matches()) return afterSales(q, Kind.REFUND_ORDER, refund.group(1), refund.group(2));
        if (UNSAFE.matcher(q).find()) return unknown(q);
        if (q.matches("^(?:请|帮我|请帮我)?(?:下单|购买)[：: ]?.*")) {
            String body = q.replaceFirst("^(?:请|帮我|请帮我)?(?:下单|购买)[：: ]*", "");
            String[] fields = body.split("[；;]", -1);
            Map<String, Object> input = new LinkedHashMap<>();
            String product = fields[0].trim();
            if (!product.isBlank()) input.put("product_name", product);
            Map<String, String> labels = Map.of("数量", "quantity", "收货人", "recipient_name", "电话", "recipient_phone", "地址", "shipping_address");
            for (int i = 1; i < fields.length; i++) {
                String[] pair = fields[i].trim().split("[：:]", 2);
                if (pair.length != 2 || !labels.containsKey(pair[0].trim()) || pair[1].isBlank()) return unknown(q);
                if (input.putIfAbsent(labels.get(pair[0].trim()), pair[1].trim()) != null) return unknown(q);
            }
            if (input.size() != 5) return clarify(q, "可以帮您准备下单，但信息还不完整，目前没有创建订单。请按以下格式提供：下单：商品名称；数量：1；收货人：姓名；电话：手机号；地址：完整地址。核对后还需要您确认。");
            if (!"1".equals(input.get("quantity"))) return clarify(q, "备用下单流程目前仅支持单件商品，本次没有创建订单。多件商品请稍后再试或联系人工客服。");
            if (!input.get("recipient_phone").toString().matches("1[3-9][0-9]{9}") || input.get("shipping_address").toString().length() < 6)
                return clarify(q, "请核对收货手机号和完整地址，目前没有创建订单。");
            input.remove("quantity");
            return new Parsed(Kind.CREATE_ORDER, q, input, null);
        }
        if (q.contains("退单") || q.contains("退货")) return clarify(q, "请确认您是要取消未付款订单，还是为已付款订单申请退款，并提供订单号。目前没有取消订单或提交退款。");
        return clarify(q, "请明确要执行的操作、订单号和原因，例如“取消订单 ORD-xxx；原因：重复下单”或“申请退款 ORD-xxx；原因：商品不合适”。目前没有修改订单。");
    }
    private static Parsed afterSales(String q, Kind kind, String orderId, String rawReason) {
        String operation = kind == Kind.CANCEL_ORDER ? "取消订单" : "申请退款";
        String reason = rawReason == null ? "" : rawReason.trim().replaceFirst("[。！!]+$", "").trim();
        if (reason.isBlank()) return clarify(q, "还需要您提供" + (kind == Kind.CANCEL_ORDER ? "取消" : "退款")
                + "原因。请重新发送完整信息，例如“" + operation + " " + orderId.toUpperCase(Locale.ROOT)
                + "；原因：商品不合适”。核对后会请您确认，目前没有修改订单或提交退款。");
        // Keep reason text as bounded data; mixed/conditional instructions require clarification.
        if (reason.length() > 100 || UNSAFE_REASON.matcher(reason.replace("重复下单", "重复订购")).find()) return unknown(q);
        return new Parsed(kind, q, Map.of("order_id", orderId.toUpperCase(Locale.ROOT), "reason", reason), null);
    }
    private static Parsed clarify(String q, String reply) { return new Parsed(Kind.CLARIFY, q, Map.of(), reply); }
    private static Parsed unknown(String q) { return new Parsed(Kind.UNKNOWN, q, Map.of(), null); }
}
