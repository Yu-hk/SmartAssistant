package com.example.smartassistant.consumer.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** The intake boundary owns extraction and atomic persistence, never the recommendation request. */
@Service
public class AdminProductIntakeService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CORE = Set.of("productCode", "productName", "price", "stock", "category", "description", "spec", "color", "featuresConfirmed");
    private static final Set<String> FEATURE_KEYS = Set.of("weightGrams", "batteryLifeHours", "batteryLifeScenario", "noiseCancelling");
    private final JdbcTemplate jdbc;
    private final AdminProductFeatureService features;
    private final ProductFeatureExtractor extractor;
    private final Clock clock;

    @Autowired
    public AdminProductIntakeService(JdbcTemplate jdbc, AdminProductFeatureService features, ProductFeatureExtractor extractor) {
        this(jdbc, features, extractor, Clock.systemUTC());
    }
    AdminProductIntakeService(JdbcTemplate jdbc, AdminProductFeatureService features, ProductFeatureExtractor extractor, Clock clock) {
        this.jdbc = jdbc; this.features = features; this.extractor = extractor; this.clock = clock;
    }

    public ProductFeatureExtractor.Extraction preview(JsonNode body) {
        requireKeys(body, Set.of("description", "spec"), Set.of());
        return extractor.extract(text(body, "description", 10000, false, true), text(body, "spec", 10000, false, true));
    }

    @Transactional
    public Map<String, Object> create(JsonNode body, long actorId) {
        if (actorId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要有效的管理员身份");
        requireKeys(body, CORE, Set.of("features"));
        String code = text(body, "productCode", 50, true, false).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9._-]{0,49}")) throw bad("商品编码格式不正确");
        String name = text(body, "productName", 200, true, false);
        String category = text(body, "category", 50, true, false);
        String stock = text(body, "stock", 20, true, false);
        if (!Set.of("充足", "紧张", "缺货").contains(stock)) throw bad("库存状态只支持充足、紧张、缺货");
        String description = text(body, "description", 10000, false, true);
        String spec = text(body, "spec", 10000, false, true);
        String color = text(body, "color", 200, false, false);
        JsonNode priceNode = body.get("price");
        if (!priceNode.isNumber()) throw bad("价格必须是数字");
        BigDecimal price = priceNode.decimalValue().stripTrailingZeros();
        if (price.signum() < 0 || price.scale() > 2 || price.compareTo(new BigDecimal("99999999.99")) > 0) throw bad("价格超出允许范围或精度");
        if (!body.get("featuresConfirmed").isBoolean()) throw bad("featuresConfirmed 必须为布尔值");

        // Always recompute from this request's source text. Never trust an earlier browser preview as extraction evidence.
        var extraction = extractor.extract(description, spec);
        ObjectNode extracted = JSON.valueToTree(extraction.features());
        ObjectNode finalFields = extracted.deepCopy();
        if (body.has("features")) {
            requireKeys(body.get("features"), FEATURE_KEYS, Set.of());
            finalFields = ((ObjectNode) body.get("features")).deepCopy();
        }
        boolean known = false;
        for (String key : FEATURE_KEYS) known |= !finalFields.get(key).isNull();
        if (known && !body.get("featuresConfirmed").booleanValue()) throw bad("请核对提取结果或手动修正后确认，未知参数可以留空");
        if (known && description.isBlank() && spec.isBlank()) throw bad("已知参数必须保留对应的简介或规格资料");
        List<String> overrides = new ArrayList<>();
        for (String key : FEATURE_KEYS) if (!sameValue(finalFields.get(key), extracted.get(key))) overrides.add(key);
        ObjectNode write = JSON.createObjectNode().put("expectedRevision", 0);
        ObjectNode persisted = finalFields.deepCopy();
        if (known) {
            persisted.put("source", "商品录入简介/规格（管理员确认；" + ProductFeatureExtractor.VERSION + "）");
            persisted.put("verifiedAt", clock.instant().toString());
        } else {
            persisted.putNull("source"); persisted.putNull("verifiedAt");
        }
        write.set("features", persisted);
        ProductFeatureUpdate.parse(write, clock); // Validate before the first database write.
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("version", extraction.version());
        audit.put("sourceTextSha256", hash(json(JSON.createObjectNode().put("description", description).put("spec", spec))));
        audit.put("extractedFeatures", extraction.features());
        audit.put("confirmedFeatures", finalFields);
        audit.put("featuresConfirmed", body.get("featuresConfirmed").booleanValue());
        audit.put("evidence", extraction.evidence());
        audit.put("warnings", extraction.warnings());
        audit.put("manualOverrides", overrides);
        audit.put("confirmedBy", actorId);
        audit.put("confirmedAt", clock.instant().toString());
        audit.put("externalVerification", false);
        try {
            jdbc.update("""
                    INSERT INTO products (product_code, product_name, price, stock, category, spec, color, description, feature_ingestion_audit)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, code, name, price, stock, category, spec, color, description, json(audit));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品编码已存在，请先核对已有商品，不能覆盖录入");
        }
        Map<String, Object> result = new LinkedHashMap<>(features.save(code, write, actorId));
        result.put("productName", name);
        result.put("extraction", extraction);
        result.put("manualOverrides", overrides);
        return result;
    }

    private static boolean sameValue(JsonNode a, JsonNode b) {
        return a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) == 0 : a.equals(b);
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法序列化录入审计记录", e); }
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String text(JsonNode body, String key, int max, boolean required, boolean multiline) {
        JsonNode value = body.get(key);
        if (!value.isTextual()) throw bad(key + " 必须为文本");
        String result = value.textValue().trim();
        if (result.length() > max || required && result.isEmpty() || result.chars().anyMatch(c -> Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t'))))
            throw bad(key + " 长度或格式不正确");
        return result;
    }
    private static void requireKeys(JsonNode object, Set<String> required, Set<String> optional) {
        if (object == null || !object.isObject()) throw bad("请求必须是 JSON 对象");
        Set<String> actual = new HashSet<>(); object.fieldNames().forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>(required); allowed.addAll(optional);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) throw bad("请完整提供约定字段，不允许额外字段");
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
