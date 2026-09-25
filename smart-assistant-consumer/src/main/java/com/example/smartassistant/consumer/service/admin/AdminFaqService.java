package com.example.smartassistant.consumer.service.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** FAQ persistence and import policy. Transactions are owned by the public AdminService facade. */
final class AdminFaqService {
    private static final Logger log = LoggerFactory.getLogger(AdminFaqService.class);
    private static final int MAX_IMPORT_SIZE = 500;
    private static final int MAX_ANSWER_LENGTH = 20_000;
    private static final Set<String> IMPORT_TYPES = Set.of("json", "csv", "markdown");

    private final JdbcTemplate jdbc;

    AdminFaqService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AdminService.FaqItem> getFaqs() {
        try {
            return jdbc.queryForList(
                    "SELECT id, category, question, answer, keywords, source_name, source_type, " +
                            "hit_count, created_at, updated_at FROM admin_faq ORDER BY updated_at DESC, id DESC")
                    .stream().map(AdminFaqService::mapFaq).toList();
        } catch (Exception error) {
            log.error("[Admin] FAQ list query failed", error);
            throw new IllegalStateException("Unable to load administration knowledge base", error);
        }
    }

    AdminService.FaqItem createFaq(Map<String, String> body) {
        String question = valueOrDefault(body.get("question"), "");
        String answer = valueOrDefault(body.get("answer"), "");
        if (question.isBlank() || answer.isBlank()) {
            throw new IllegalArgumentException("question and answer are required");
        }
        try {
            jdbc.update("INSERT INTO admin_faq (category, question, answer, keywords, source_type, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, 'manual', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    valueOrDefault(body.get("category"), "general"), question, answer,
                    valueOrDefault(body.get("keywords"), ""));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("an FAQ with the same question already exists");
        }
        return findFaqByQuestion(question)
                .orElseThrow(() -> new IllegalStateException("FAQ insert succeeded but could not be read"));
    }

    AdminService.FaqItem updateFaq(String id, Map<String, String> body) {
        Long faqId = parseId(id);
        if (faqId == null) return null;
        Optional<AdminService.FaqItem> current = findFaq(faqId);
        if (current.isEmpty()) return null;
        AdminService.FaqItem existing = current.get();
        String question = valueOrDefault(body.get("question"), existing.question());
        String answer = valueOrDefault(body.get("answer"), existing.answer());
        if (question.isBlank() || answer.isBlank()) {
            throw new IllegalArgumentException("question and answer are required");
        }
        jdbc.update("UPDATE admin_faq SET category = ?, question = ?, answer = ?, keywords = ?, " +
                        "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                valueOrDefault(body.get("category"), existing.category()), question, answer,
                valueOrDefault(body.get("keywords"), existing.keywords()), faqId);
        return findFaq(faqId).orElse(null);
    }

    AdminService.FaqImportResult importFaqs(String sourceName, String sourceType, boolean overwrite,
                                            List<Map<String, String>> items) {
        String normalizedName = valueOrDefault(sourceName, "external-knowledge");
        String normalizedType = valueOrDefault(sourceType, "").toLowerCase(Locale.ROOT);
        if (normalizedName.isBlank() || normalizedName.length() > 255) {
            throw new IllegalArgumentException("sourceName is required and must not exceed 255 characters");
        }
        if (!IMPORT_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("sourceType must be json, csv, or markdown");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("at least one knowledge item is required");
        }
        if (items.size() > MAX_IMPORT_SIZE) {
            throw new IllegalArgumentException("a single import cannot exceed " + MAX_IMPORT_SIZE + " items");
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            Map<String, String> item = items.get(index);
            if (item == null) {
                throw new IllegalArgumentException("knowledge item " + (index + 1) + " is empty");
            }
            String question = validateImportField(item.get("question"), "question", index, 500, true);
            String answer = validateImportField(item.get("answer"), "answer", index, MAX_ANSWER_LENGTH, true);
            String category = validateImportField(item.get("category"), "category", index, 50, false);
            String keywords = validateImportField(item.get("keywords"), "keywords", index, 1000, false);
            if (category.isBlank()) category = "general";
            if (!seen.add(question.toLowerCase(Locale.ROOT))) {
                skipped++;
                continue;
            }
            Long existingId = findFaqIdByQuestionIgnoreCase(question);
            if (existingId != null) {
                if (!overwrite) {
                    skipped++;
                    continue;
                }
                jdbc.update("UPDATE admin_faq SET category = ?, question = ?, answer = ?, keywords = ?, " +
                                "source_name = ?, source_type = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        category, question, answer, keywords, normalizedName, normalizedType, existingId);
                updated++;
                continue;
            }
            jdbc.update("INSERT INTO admin_faq (category, question, answer, keywords, source_name, source_type, " +
                            "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    category, question, answer, keywords, normalizedName, normalizedType);
            created++;
        }
        return new AdminService.FaqImportResult(items.size(), created, updated, skipped);
    }

    boolean deleteFaq(String id) {
        Long faqId = parseId(id);
        return faqId != null && jdbc.update("DELETE FROM admin_faq WHERE id = ?", faqId) > 0;
    }

    boolean hitFaq(String id) {
        Long faqId = parseId(id);
        return faqId != null && jdbc.update(
                "UPDATE admin_faq SET hit_count = hit_count + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                faqId) > 0;
    }

    void seedDefaults() {
        seed("order", "怎么查询我的订单？",
                "登录后可以查询当前账号的订单列表；查询某一笔订单时，请选择对应订单，所需资料以订单服务提示为准。",
                "订单查询,订单状态,物流");
        seed("order", "如何申请退款？",
                "了解退款政策无需提供订单号；办理具体订单的退款时，请选择对应订单，订单服务会核实状态并提示所需资料。",
                "退款,退货,取消订单");
        seed("product", "如何查询商品信息？",
                "可以告诉我商品名称、品类或您的使用需求，我会查询相关商品；如需进一步明确条件，商品服务会提示您补充。",
                "商品查询,商品信息,价格");
        seed("general", "你们有哪些服务？", "我可以帮助查询订单、商品信息和常见问题。", "服务,功能,帮助");
    }

    private void seed(String category, String question, String answer, String keywords) {
        Number count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_faq WHERE question = ?", Number.class, question);
        if (count != null && count.longValue() > 0) return;
        try {
            jdbc.update("INSERT INTO admin_faq (category, question, answer, keywords, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    category, question, answer, keywords);
        } catch (DuplicateKeyException ignored) {
            // Multiple consumers may seed concurrently.
        }
    }

    private Optional<AdminService.FaqItem> findFaq(Long id) {
        return jdbc.queryForList("SELECT id, category, question, answer, keywords, source_name, source_type, " +
                        "hit_count, created_at, updated_at FROM admin_faq WHERE id = ?", id)
                .stream().findFirst().map(AdminFaqService::mapFaq);
    }

    private Optional<AdminService.FaqItem> findFaqByQuestion(String question) {
        return jdbc.queryForList("SELECT id, category, question, answer, keywords, source_name, source_type, " +
                        "hit_count, created_at, updated_at FROM admin_faq WHERE question = ?", question)
                .stream().findFirst().map(AdminFaqService::mapFaq);
    }

    private Long findFaqIdByQuestionIgnoreCase(String question) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM admin_faq WHERE LOWER(question) = LOWER(?) ORDER BY id LIMIT 1",
                Long.class, question);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static AdminService.FaqItem mapFaq(Map<String, Object> row) {
        String type = stringValue(row, "source_type");
        Number hits = (Number) row.get("hit_count");
        return new AdminService.FaqItem(
                Objects.toString(row.get("id"), ""), stringValue(row, "category"),
                stringValue(row, "question"), stringValue(row, "answer"),
                stringValue(row, "keywords"), stringValue(row, "source_name"),
                type.isBlank() ? "manual" : type, hits == null ? 0L : hits.longValue(),
                Objects.toString(row.get("created_at"), null), Objects.toString(row.get("updated_at"), null));
    }

    private static String validateImportField(String value, String field, int index, int maxLength, boolean required) {
        String normalized = valueOrDefault(value, "");
        if (required && normalized.isBlank()) {
            throw new IllegalArgumentException("knowledge item " + (index + 1) + " requires " + field);
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    "knowledge item " + (index + 1) + " field " + field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    private static String stringValue(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private static Long parseId(String id) {
        try { return Long.valueOf(id); }
        catch (NumberFormatException ignored) { return null; }
    }
}
