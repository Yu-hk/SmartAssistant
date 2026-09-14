package com.example.smartassistant.consumer.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Admin writes the shared catalog; Product reads the same rows. No in-memory write fallback. */
@Service
public class AdminProductFeatureService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AdminProductFeatureService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    AdminProductFeatureService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public Map<String, Object> get(String productCode) {
        String code = code(productCode);
        var rows = jdbc.query("""
                SELECT product_code, product_name, weight_grams, battery_life_hours,
                       battery_life_scenario, noise_cancelling, feature_source, features_verified_at,
                       features_revision, features_updated_by, features_updated_at
                  FROM products WHERE product_code = ?
                """, (rs, row) -> snapshot(rs), code);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        return rows.getFirst();
    }

    public Map<String, Object> save(String productCode, JsonNode body, long actorId) {
        if (actorId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少有效管理员身份");
        String code = code(productCode);
        ProductFeatureUpdate update = ProductFeatureUpdate.parse(body, clock);
        OffsetDateTime modified = clock.instant().atOffset(ZoneOffset.UTC);
        // The single compare-and-set UPDATE is atomic. Concurrent writers cannot overwrite silently.
        int rows = jdbc.update("""
                UPDATE products SET weight_grams = ?, battery_life_hours = ?, battery_life_scenario = ?,
                       noise_cancelling = ?, feature_source = ?, features_verified_at = ?,
                       features_revision = features_revision + 1, features_updated_by = ?, features_updated_at = ?
                 WHERE product_code = ? AND features_revision = ?
                """, update.weightGrams(), update.batteryLifeHours(), update.batteryLifeScenario(),
                update.noiseCancelling(), update.source(), update.verifiedAt(), actorId, modified,
                code, update.expectedRevision());
        if (rows == 0) {
            get(code); // A missing product is 404; an existing product with a stale revision is 409.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "参数已被其他操作修改，请重新读取版本后保存");
        }
        // Return the exact committed version, not a second SELECT which could observe another writer.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", code);
        result.put("revision", update.expectedRevision() + 1);
        result.put("features", featureMap(update.weightGrams(), update.batteryLifeHours(), update.batteryLifeScenario(),
                update.noiseCancelling(), update.source(), update.verifiedAt()));
        result.put("updatedBy", actorId);
        result.put("updatedAt", modified.toString());
        return result;
    }

    private static Map<String, Object> snapshot(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", rs.getString("product_code"));
        result.put("productName", rs.getString("product_name"));
        result.put("revision", rs.getLong("features_revision"));
        result.put("features", featureMap(rs.getBigDecimal("weight_grams"), rs.getBigDecimal("battery_life_hours"),
                rs.getString("battery_life_scenario"), rs.getObject("noise_cancelling", Boolean.class),
                rs.getString("feature_source"), rs.getObject("features_verified_at", OffsetDateTime.class)));
        result.put("updatedBy", rs.getObject("features_updated_by", Long.class));
        OffsetDateTime modified = rs.getObject("features_updated_at", OffsetDateTime.class);
        result.put("updatedAt", modified == null ? null : modified.toInstant().toString());
        return result;
    }

    private static Map<String, Object> featureMap(Object weight, Object battery, String scenario,
                                                Boolean anc, String source, OffsetDateTime verifiedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("weightGrams", weight);
        fields.put("batteryLifeHours", battery);
        fields.put("batteryLifeScenario", scenario);
        fields.put("noiseCancelling", anc);
        fields.put("source", source);
        fields.put("verifiedAt", verifiedAt == null ? null : verifiedAt.toInstant().toString());
        return fields;
    }

    private static String code(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效的商品编码");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
