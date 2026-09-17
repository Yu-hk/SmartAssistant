package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.memory.ProfileContextPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

/** Read-only authoritative commerce view. Never reads or merges legacy Redis/file facts. */
public final class UserProfileQueryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UserProfileSnapshotStore store;

    public UserProfileQueryService(UserProfileSnapshotStore store) { this.store = store; }

    /** Caller keeps this DB read inside the existing optional profile worker/budget. */
    public String forUser(Long userId) {
        if (userId == null || userId <= 0) return "";
        return store.load(userId).filter(snapshot -> userId.equals(snapshot.userId())
                        && snapshot.profileVersion() > 0
                        && UserProfileSnapshotStore.SCHEMA_VERSION.equals(snapshot.schemaVersion()))
                .map(snapshot -> render(snapshot.reportJson(), ProfileContextPolicy.Source.POSTGRES_SNAPSHOT,
                        snapshot.schemaVersion() + "/" + snapshot.profileVersion(),
                        snapshot.updatedAt() == null ? "未知" : snapshot.updatedAt().toString()))
                .orElse("");
    }

    public static String candidate(String reportJson) {
        return render(reportJson, ProfileContextPolicy.Source.REQUEST_CANDIDATE,
                "尚未持久化", Instant.now().toString());
    }

    private static String render(String reportJson, ProfileContextPolicy.Source source,
                                 String version, String timestamp) {
        if (reportJson == null || reportJson.length() > 131072) return "";
        try {
            JsonNode report = JSON.readTree(reportJson);
            if (report == null || !report.path("commerceAssessment").path("reliable").isBoolean()
                    || !report.path("commerceAssessment").path("reliable").booleanValue()) return "";
            StringBuilder body = new StringBuilder("【电商用户洞察】\n");
            JsonNode assessment = report.path("commerceAssessment");
            field(body, "购买阶段", assessment.path("purchaseStage"));
            field(body, "决策风格", assessment.path("decisionStyle"));
            field(body, "价格敏感度", assessment.path("priceSensitivity"));
            field(body, "购买意愿", assessment.path("purchaseIntentScore"));
            field(body, "流失风险", assessment.path("churnRisk"));
            list(body, "主要顾虑", assessment.path("primaryConcerns"));
            list(body, "核心驱动", report.path("topDrivers"));
            list(body, "核心阻碍", report.path("topBarriers"));
            field(body, "购买动机", report.path("insightDimensions").path("purchaseMotivation").path("summary"));
            field(body, "价值偏好", report.path("insightDimensions").path("valuePreference").path("summary"));
            return ProfileContextPolicy.reference(source, version, timestamp, body.toString());
        } catch (java.io.IOException malformed) {
            return "";
        }
    }

    private static void field(StringBuilder output, String label, JsonNode node) {
        if (!(node.isTextual() || node.isNumber() || node.isBoolean()) || node.asText().isBlank()) return;
        output.append("- ").append(label).append(": ")
                .append(ProfileContextPolicy.singleLine(node.asText(), 300)).append('\n');
    }
    private static void list(StringBuilder output, String label, JsonNode values) {
        if (!values.isArray()) return;
        int count = 0;
        for (JsonNode value : values) {
            if (++count > 5) break;
            field(output, label, value);
        }
    }
}
