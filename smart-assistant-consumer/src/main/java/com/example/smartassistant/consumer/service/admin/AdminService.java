/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.consumer.infrastructure.db.DatabaseDialect;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent administration and user-session service.
 *
 * <p>The ordinary session methods are always scoped to the authenticated user.
 * Global access is deliberately exposed only through the admin-specific methods
 * used by {@code /api/admin/**}.</p>
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_FAQ_IMPORT_SIZE = 500;
    private static final int MAX_FAQ_ANSWER_LENGTH = 20_000;
    private static final Set<String> FAQ_IMPORT_TYPES = Set.of("json", "csv", "markdown");

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;

    public AdminService(JdbcTemplate jdbcTemplate, DatabaseDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /**
     * The project does not currently enable automatic Flyway execution. Keep the
     * additive migration in docs/database/migrations, and also self-heal these two
     * small administration tables when the consumer starts.
     */
    @PostConstruct
    void initializePersistentAdminStorage() {
        try {
            ensureRoutingAuditColumns();
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS conversation_session_state (" +
                    "user_id BIGINT NOT NULL, " +
                    "session_id VARCHAR(100) NOT NULL, " +
                    "status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE_IDLE', " +
                    "closed_at TIMESTAMP NULL, " +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (user_id, session_id))");
            try {
                String ifNotExists = "mysql".equalsIgnoreCase(dialect.getType()) ? "" : "IF NOT EXISTS ";
                jdbcTemplate.execute("CREATE INDEX " + ifNotExists +
                        "idx_conversation_session_state_status " +
                        "ON conversation_session_state(status, updated_at)");
            } catch (Exception indexError) {
                // MySQL has no portable CREATE INDEX IF NOT EXISTS. A duplicate
                // index must not prevent the following FAQ table from self-healing.
                if ("mysql".equalsIgnoreCase(dialect.getType())) {
                    log.debug("[Admin] Session-state index already exists or cannot be created: {}",
                            indexError.getMessage());
                } else {
                    throw indexError;
                }
            }

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS admin_faq (" +
                    "id " + dialect.serialType() + " PRIMARY KEY, " +
                    "category VARCHAR(50) NOT NULL DEFAULT 'general', " +
                    "question VARCHAR(500) NOT NULL UNIQUE, " +
                    "answer TEXT NOT NULL, " +
                    "keywords VARCHAR(1000), " +
                    "source_name VARCHAR(255), " +
                    "source_type VARCHAR(32) NOT NULL DEFAULT 'manual', " +
                    "hit_count BIGINT NOT NULL DEFAULT 0, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            ensureColumn("admin_faq", "source_name", "VARCHAR(255)");
            ensureColumn("admin_faq", "source_type", "VARCHAR(32) NOT NULL DEFAULT 'manual'");
            seedDefaultFaqs();
        } catch (Exception e) {
            // The application can still serve chat traffic if a deployment role
            // temporarily lacks DDL permission. The documented migration remains
            // the authoritative production installation path.
            log.warn("[Admin] Unable to initialize persistent admin storage: {}", e.getMessage());
        }
    }

    /**
     * Self-heal additive monitoring columns because this project does not execute
     * Flyway automatically. Database metadata keeps this portable across the
     * supported PostgreSQL/MySQL deployments and avoids dialect-specific
     * {@code ADD COLUMN IF NOT EXISTS} syntax.
     */
    private void ensureRoutingAuditColumns() throws SQLException {
        if (!tableExists("routing_call_log")) {
            return;
        }
        ensureColumn("routing_call_log", "prompt_tokens", "BIGINT");
        ensureColumn("routing_call_log", "completion_tokens", "BIGINT");
        ensureColumn("routing_call_log", "total_tokens", "BIGINT");
        ensureColumn("routing_call_log", "tool_calls", "TEXT");
        ensureColumn("routing_call_log", "request_id", "VARCHAR(128)");
    }

    private void ensureColumn(String table, String column, String sqlType) throws SQLException {
        if (columnExists(table, column)) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + sqlType);
        } catch (RuntimeException concurrentOrPermissionFailure) {
            // Multiple Consumer instances may start at the same time. A racing
            // instance adding the column first is success; every other failure
            // must remain visible instead of being mistaken for a duplicate.
            if (!columnExists(table, column)) {
                throw concurrentOrPermissionFailure;
            }
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String normalized = normalizeIdentifier(metadata, table);
            try (ResultSet tables = metadata.getTables(
                    connection.getCatalog(), connection.getSchema(), normalized, new String[]{"TABLE"})) {
                if (tables.next()) return true;
            }
            try (ResultSet tables = metadata.getTables(
                    connection.getCatalog(), null, normalized, new String[]{"TABLE"})) {
                return tables.next();
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String normalizedTable = normalizeIdentifier(metadata, table);
            String normalizedColumn = normalizeIdentifier(metadata, column);
            try (ResultSet columns = metadata.getColumns(
                    connection.getCatalog(), connection.getSchema(), normalizedTable, normalizedColumn)) {
                if (columns.next()) return true;
            }
            try (ResultSet columns = metadata.getColumns(
                    connection.getCatalog(), null, normalizedTable, normalizedColumn)) {
                return columns.next();
            }
        }
    }

    private static String normalizeIdentifier(DatabaseMetaData metadata, String identifier)
            throws SQLException {
        if (metadata.storesUpperCaseIdentifiers()) return identifier.toUpperCase(Locale.ROOT);
        if (metadata.storesLowerCaseIdentifiers()) return identifier.toLowerCase(Locale.ROOT);
        return identifier;
    }

    // ==================== Admin dashboard ====================

    public AdminStats getStats() {
        try {
            long totalSessions = queryLong(
                    "SELECT COUNT(*) FROM (SELECT session_id, user_id FROM routing_call_log " +
                            "WHERE session_id IS NOT NULL GROUP BY session_id, user_id) sessions");
            long totalUsers = queryLong(
                    "SELECT COUNT(DISTINCT user_id) FROM routing_call_log WHERE user_id IS NOT NULL");
            long totalTurns = queryLong(
                    "SELECT COUNT(*) FROM routing_call_log");
            long tokenTrackedTurns = queryLong(
                    "SELECT COUNT(*) FROM routing_call_log WHERE total_tokens IS NOT NULL");
            Long totalTokens = tokenTrackedTurns == 0 ? null : queryLong(
                    "SELECT SUM(total_tokens) FROM routing_call_log " +
                            "WHERE total_tokens IS NOT NULL");
            long tokenTrackedSessions = queryLong(
                    "SELECT COUNT(*) FROM (SELECT session_id, user_id FROM routing_call_log " +
                            "WHERE session_id IS NOT NULL GROUP BY session_id, user_id " +
                            "HAVING COUNT(*) = COUNT(total_tokens)) tracked_sessions");
            long completeSessionTokens = queryLong(
                    "SELECT COALESCE(SUM(session_tokens), 0) FROM (" +
                            "SELECT SUM(total_tokens) session_tokens FROM routing_call_log " +
                            "WHERE session_id IS NOT NULL GROUP BY session_id, user_id " +
                            "HAVING COUNT(*) = COUNT(total_tokens)) tracked_sessions");
            Double averageTokensPerSession = tokenTrackedSessions == 0 ? null
                    : rounded(completeSessionTokens * 1.0 / tokenTrackedSessions);
            long ratedSessions = queryLong(
                    "SELECT COUNT(*) FROM (SELECT session_id, user_id FROM conversation_feedback " +
                            "WHERE rating IS NOT NULL GROUP BY session_id, user_id) ratings");
            Double averageSatisfaction = queryNullableDouble(
                    "SELECT AVG(f.rating) FROM conversation_feedback f JOIN (" +
                            "SELECT session_id, user_id, MAX(id) last_id " +
                            "FROM conversation_feedback WHERE rating IS NOT NULL " +
                            "GROUP BY session_id, user_id) latest ON latest.last_id = f.id");

            // Success and handoff are session-level metrics. A long conversation
            // therefore has the same weight as a one-turn conversation.
            String latestSessionCte = "WITH sessions AS (SELECT session_id, user_id, MAX(id) last_id " +
                    "FROM routing_call_log WHERE session_id IS NOT NULL GROUP BY session_id, user_id) ";
            long successfulSessions = queryLong(
                    latestSessionCte + "SELECT COUNT(*) FROM sessions " +
                            "JOIN routing_call_log latest ON latest.id = sessions.last_id " +
                            "WHERE UPPER(COALESCE(latest.status, '')) = 'SUCCESS'");
            long handoffSessions = queryLong(
                    latestSessionCte + "SELECT COUNT(*) FROM sessions " +
                            "JOIN routing_call_log latest ON latest.id = sessions.last_id " +
                            "WHERE LOWER(COALESCE(latest.routed_agent, '')) " +
                            "IN ('human', 'human_service', 'human-service')");
            // Latency remains invocation-level because it describes individual
            // model/tool calls and is useful for infrastructure diagnostics.
            Double averageLatency = queryNullableDouble(
                    "SELECT AVG(latency_ms) FROM routing_call_log WHERE latency_ms IS NOT NULL");
            List<Long> latencies = jdbcTemplate.queryForList(
                    "SELECT latency_ms FROM routing_call_log WHERE latency_ms IS NOT NULL ORDER BY latency_ms",
                    Long.class);

            List<StatusCount> statusBreakdown = jdbcTemplate.queryForList(
                            latestSessionCte +
                                    "SELECT " + displayStatusSql("s", "latest") + " status, COUNT(*) count " +
                                    "FROM sessions JOIN routing_call_log latest ON latest.id = sessions.last_id " +
                                    "LEFT JOIN conversation_session_state s " +
                                    "ON s.session_id = sessions.session_id AND s.user_id = sessions.user_id " +
                                    "GROUP BY " + displayStatusSql("s", "latest") + " ORDER BY count DESC")
                    .stream()
                    .map(row -> new StatusCount(stringValue(row, "status"), longValue(row, "count")))
                    .toList();

            Map<String, Long> intentTotals = new LinkedHashMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT COALESCE(routed_agent, 'unknown') intent, COUNT(*) count " +
                            "FROM routing_call_log GROUP BY COALESCE(routed_agent, 'unknown') " +
                            "ORDER BY count DESC")) {
                intentTotals.merge(
                        mapAgentToIntent(stringValue(row, "intent")),
                        longValue(row, "count"),
                        Long::sum);
            }
            List<IntentCount> intentBreakdown = intentTotals.entrySet().stream()
                    .map(entry -> new IntentCount(entry.getKey(), entry.getValue()))
                    .sorted((left, right) -> Long.compare(right.count(), left.count()))
                    .toList();

            List<DailyStats> observedDaily = jdbcTemplate.queryForList(
                            "SELECT session_day, COUNT(*) session_count, AVG(rating) avg_satisfaction " +
                                    "FROM (SELECT " + dialect.dateFunc("MIN(r.created_at)") + " session_day, " +
                                    "r.session_id, r.user_id, MAX(f.rating) rating " +
                                    "FROM routing_call_log r LEFT JOIN (" +
                                    "SELECT feedback.session_id, feedback.user_id, feedback.rating " +
                                    "FROM conversation_feedback feedback JOIN (" +
                                    "SELECT session_id, user_id, MAX(id) last_id " +
                                    "FROM conversation_feedback WHERE rating IS NOT NULL " +
                                    "GROUP BY session_id, user_id) latest_feedback " +
                                    "ON latest_feedback.last_id = feedback.id) f " +
                                    "ON f.session_id = r.session_id AND f.user_id = r.user_id " +
                                    "WHERE r.session_id IS NOT NULL AND r.created_at >= " + dialect.dateSub("7") + " " +
                                    "GROUP BY r.session_id, r.user_id) session_daily " +
                                    "GROUP BY session_day ORDER BY session_day")
                    .stream()
                    .map(row -> new DailyStats(
                            dateValue(row.get("session_day")),
                            longValue(row, "session_count"),
                            nullableDouble(row.get("avg_satisfaction"))))
                    .toList();
            List<DailyStats> daily = fillLastSevenDays(observedDaily);

            return new AdminStats(
                    totalSessions,
                    totalUsers,
                    ratedSessions,
                    totalTokens,
                    averageTokensPerSession,
                    tokenTrackedSessions,
                    tokenTrackedTurns,
                    totalTurns,
                    percentage(tokenTrackedTurns, totalTurns),
                    rounded(averageSatisfaction),
                    percentage(successfulSessions, totalSessions),
                    percentage(handoffSessions, totalSessions),
                    averageLatency == null ? 0L : Math.round(averageLatency),
                    percentile95(latencies),
                    statusBreakdown,
                    intentBreakdown,
                    daily);
        } catch (Exception e) {
            log.error("[Admin] Dashboard statistics query failed", e);
            throw new IllegalStateException("Unable to load administration statistics", e);
        }
    }

    // ==================== Session listing ====================

    /**
     * Compatibility list for {@code /api/sessions}. The admin flag is ignored
     * intentionally: an administrator using the normal workspace must still see
     * only their own conversations.
     */
    public List<Map<String, Object>> getSessions(Long userId, boolean ignoredAdmin) {
        return getSessions(userId);
    }

    public List<Map<String, Object>> getSessions(Long userId) {
        if (userId == null) {
            return List.of();
        }
        SessionPage page = searchSessionsInternal(null, userId, null, null, 0, MAX_PAGE_SIZE);
        return page.items().stream().map(this::legacySessionMap).toList();
    }

    public SessionPage searchAdminSessions(
            String query, Long userId, String status, String intent, int page, int size) {
        return searchSessionsInternal(query, userId, status, intent, page, size);
    }

    private SessionPage searchSessionsInternal(
            String query, Long userId, String status, String intent, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, requestedSize));

        String cte = sessionSummaryCte(userId != null);
        List<Object> parameters = new ArrayList<>();
        if (userId != null) {
            parameters.add(userId);
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            where.append("AND (LOWER(COALESCE(ll.user_input, '')) LIKE ? ")
                    .append("OR LOWER(COALESCE(u.username, '')) LIKE ? ")
                    .append("OR LOWER(a.session_id) LIKE ?) ");
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (status != null && !status.isBlank()) {
            where.append("AND ").append(displayStatusSql("s", "ll")).append(" = ? ");
            parameters.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (intent != null && !intent.isBlank()) {
            where.append("AND LOWER(COALESCE(ll.routed_agent, 'unknown')) LIKE ? ");
            parameters.add("%" + intent.trim().toLowerCase(Locale.ROOT) + "%");
        }

        String from = " FROM aggregated a " +
                "JOIN routing_call_log ll ON ll.id = a.last_id " +
                "LEFT JOIN users u ON u.id = a.user_id " +
                "LEFT JOIN feedback f ON f.session_id = a.session_id " +
                "AND (f.user_id = a.user_id OR (f.user_id IS NULL AND a.user_id IS NULL)) " +
                "LEFT JOIN conversation_session_state s ON s.session_id = a.session_id AND s.user_id = a.user_id ";

        long total = queryLong(cte + "SELECT COUNT(*)" + from + where, parameters.toArray());

        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add((long) page * size);
        String select = "SELECT a.session_id, a.user_id, u.username, " +
                "LEFT(COALESCE(ll.user_input, ''), 100) title, " +
                "COALESCE(ll.routed_agent, 'unknown') agent_name, " +
                displayStatusSql("s", "ll") + " status, f.satisfaction, " +
                "COALESCE(f.satisfaction_comment, '') satisfaction_comment, " +
                "a.message_count, a.total_turns, a.token_tracked_turns, a.total_tokens, a.created_at, " +
                "CASE WHEN s.updated_at IS NOT NULL AND s.updated_at > a.updated_at " +
                "THEN s.updated_at ELSE a.updated_at END updated_at";
        List<SessionSummary> items = jdbcTemplate.queryForList(
                        cte + select + from + where +
                                "ORDER BY updated_at DESC, a.session_id DESC LIMIT ? OFFSET ?",
                        pageParameters.toArray())
                .stream()
                .map(this::mapSessionSummary)
                .toList();
        return new SessionPage(items, total, page, size);
    }

    private String sessionSummaryCte(boolean scopedToUser) {
        return "WITH aggregated AS (" +
                "SELECT r.session_id, r.user_id, MAX(r.id) last_id, " +
                "SUM(CASE WHEN r.response_summary IS NULL OR r.response_summary = '' THEN 1 ELSE 2 END) message_count, " +
                "COUNT(*) total_turns, COUNT(r.total_tokens) token_tracked_turns, " +
                "CASE WHEN COUNT(*) = COUNT(r.total_tokens) THEN SUM(r.total_tokens) ELSE NULL END total_tokens, " +
                "MIN(r.created_at) created_at, MAX(r.created_at) updated_at " +
                "FROM routing_call_log r WHERE r.session_id IS NOT NULL " +
                (scopedToUser ? "AND r.user_id = ? " : "") +
                "GROUP BY r.session_id, r.user_id), " +
                "feedback AS (SELECT current_feedback.session_id, current_feedback.user_id, " +
                "current_feedback.rating satisfaction, " +
                "current_feedback.feedback_text satisfaction_comment " +
                "FROM conversation_feedback current_feedback JOIN (" +
                "SELECT session_id, user_id, MAX(id) last_id FROM conversation_feedback " +
                "GROUP BY session_id, user_id) latest_feedback " +
                "ON latest_feedback.last_id = current_feedback.id) ";
    }

    public Optional<Map<String, Object>> getSessionDetail(
            String sessionId, Long userId, boolean ignoredAdmin) {
        return getUserSessionDetail(sessionId, userId).map(this::legacyDetailMap);
    }

    public Optional<SessionDetail> getUserSessionDetail(String sessionId, Long userId) {
        if (userId == null || !ownsSession(sessionId, userId)) {
            return Optional.empty();
        }
        return buildSessionDetail(sessionId, userId);
    }

    public Optional<SessionDetail> getAdminSessionDetail(String sessionId) {
        return getAdminSessionDetail(sessionId, null);
    }

    /**
     * Resolve an admin detail request by the compound conversation identity.
     * Omitting userId is supported only when the session id has exactly one
     * owner (including one legacy NULL owner); ambiguous ids fail closed.
     */
    public Optional<SessionDetail> getAdminSessionDetail(String sessionId, Long userId) {
        if (userId != null) {
            return buildSessionDetail(sessionId, userId);
        }
        List<Map<String, Object>> owners = jdbcTemplate.queryForList(
                "SELECT user_id FROM routing_call_log WHERE session_id = ? " +
                        "GROUP BY user_id ORDER BY CASE WHEN user_id IS NULL THEN 0 ELSE 1 END, user_id",
                sessionId);
        if (owners.size() != 1) {
            return Optional.empty();
        }
        return buildSessionDetail(sessionId, nullableLong(owners.getFirst().get("user_id")));
    }

    private Optional<SessionDetail> buildSessionDetail(String sessionId, Long userId) {
        String ownership = userId == null ? "user_id IS NULL" : "user_id = ?";
        List<Map<String, Object>> logs = userId == null
                ? jdbcTemplate.queryForList(
                        "SELECT id, request_id, user_input, llm_received_question, response_summary, routed_agent, status, latency_ms, " +
                                "prompt_tokens, completion_tokens, total_tokens, tool_calls, created_at " +
                                "FROM routing_call_log WHERE session_id = ? AND " + ownership +
                                " ORDER BY created_at, id", sessionId)
                : jdbcTemplate.queryForList(
                        "SELECT id, request_id, user_input, llm_received_question, response_summary, routed_agent, status, latency_ms, " +
                                "prompt_tokens, completion_tokens, total_tokens, tool_calls, created_at " +
                                "FROM routing_call_log WHERE session_id = ? AND " + ownership +
                                " ORDER BY created_at, id", sessionId, userId);
        if (logs.isEmpty()) {
            return Optional.empty();
        }

        String username = null;
        if (userId != null) {
            List<String> usernames = jdbcTemplate.queryForList(
                    "SELECT username FROM users WHERE id = ?", String.class, userId);
            username = usernames.isEmpty() ? null : usernames.getFirst();
        }

        Integer satisfaction = null;
        String satisfactionComment = "";
        List<Map<String, Object>> feedback = userId == null
                ? List.of()
                : jdbcTemplate.queryForList(
                        "SELECT rating, feedback_text FROM conversation_feedback " +
                                "WHERE session_id = ? AND user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                        sessionId, userId);
        if (!feedback.isEmpty()) {
            satisfaction = nullableInteger(feedback.getFirst().get("rating"));
            satisfactionComment = Objects.toString(feedback.getFirst().get("feedback_text"), "");
        }

        String lifecycleStatus = null;
        if (userId != null) {
            List<String> states = jdbcTemplate.queryForList(
                    "SELECT status FROM conversation_session_state WHERE session_id = ? AND user_id = ?",
                    String.class, sessionId, userId);
            if (!states.isEmpty() && states.getFirst() != null) {
                lifecycleStatus = states.getFirst();
            }
        }

        List<SessionMessage> messages = new ArrayList<>();
        long tokenTrackedTurns = 0;
        long promptTokenTotal = 0;
        long completionTokenTotal = 0;
        long tokenTotal = 0;
        boolean allPromptTokensTracked = true;
        boolean allCompletionTokensTracked = true;
        for (Map<String, Object> row : logs) {
            String id = Objects.toString(row.get("id"));
            String createdAt = timestampValue(row.get("created_at"));
            String requestId = nullableString(row.get("request_id"));
            Long promptTokens = nullableLong(row.get("prompt_tokens"));
            Long completionTokens = nullableLong(row.get("completion_tokens"));
            Long totalTokens = nullableLong(row.get("total_tokens"));
            String promptSnapshot = nullableString(row.get("llm_received_question"));
            ToolUsageCache.ToolUsage toolUsage = ToolUsageHeaders.decode(
                    nullableString(row.get("tool_calls")));
            if (totalTokens != null) {
                tokenTrackedTurns++;
                tokenTotal = safeAdd(tokenTotal, totalTokens);
            }
            if (promptTokens == null) {
                allPromptTokensTracked = false;
            } else {
                promptTokenTotal = safeAdd(promptTokenTotal, promptTokens);
            }
            if (completionTokens == null) {
                allCompletionTokensTracked = false;
            } else {
                completionTokenTotal = safeAdd(completionTokenTotal, completionTokens);
            }
            messages.add(new SessionMessage(
                    id + "-user", "user", Objects.toString(row.get("user_input"), ""),
                    createdAt, requestId, null, null, null, null, null, null,
                    null, null, List.of()));
            String response = Objects.toString(row.get("response_summary"), "");
            boolean hasInvocationAudit = promptSnapshot != null || toolUsage != null
                    || promptTokens != null || completionTokens != null || totalTokens != null;
            if (!response.isBlank() || hasInvocationAudit) {
                messages.add(new SessionMessage(
                        id + "-assistant", "assistant",
                        response.isBlank() ? "（未记录回复内容）" : response, createdAt,
                        requestId,
                        Objects.toString(row.get("routed_agent"), "unknown"),
                        Objects.toString(row.get("status"), "UNKNOWN"),
                        nullableLong(row.get("latency_ms")),
                        promptTokens, completionTokens, totalTokens,
                        promptSnapshot,
                        toolUsage != null ? toolUsage.complete() : null,
                        toolUsage != null ? toolUsage.calls() : List.of()));
            }
        }
        int totalTurns = logs.size();
        boolean tokenUsageComplete = tokenTrackedTurns == totalTurns;

        Map<String, Object> first = logs.getFirst();
        Map<String, Object> last = logs.getLast();
        String agentName = Objects.toString(last.get("routed_agent"), "unknown");
        String sessionStatus = normalizeDisplayStatus(
                lifecycleStatus, agentName, Objects.toString(last.get("status"), "FAILED"));
        return Optional.of(new SessionDetail(
                sessionId,
                userId,
                username,
                truncate(Objects.toString(first.get("user_input"), ""), 100),
                agentName,
                mapAgentToIntent(agentName),
                sessionStatus,
                satisfaction,
                satisfactionComment,
                messages.size(),
                allPromptTokensTracked ? promptTokenTotal : null,
                allCompletionTokensTracked ? completionTokenTotal : null,
                tokenUsageComplete ? tokenTotal : null,
                Math.toIntExact(tokenTrackedTurns),
                totalTurns,
                tokenUsageComplete,
                timestampValue(first.get("created_at")),
                timestampValue(last.get("created_at")),
                messages));
    }

    @Transactional
    public boolean deleteSession(String sessionId, Long userId, boolean ignoredAdmin) {
        return deleteUserSession(sessionId, userId);
    }

    @Transactional
    public boolean deleteUserSession(String sessionId, Long userId) {
        if (userId == null) {
            return false;
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM routing_call_log WHERE session_id = ? AND user_id = ?", sessionId, userId);
        if (deleted == 0) {
            return false;
        }
        jdbcTemplate.update(
                "DELETE FROM conversation_feedback WHERE session_id = ? AND user_id = ?", sessionId, userId);
        jdbcTemplate.update(
                "DELETE FROM conversation_session_state WHERE session_id = ? AND user_id = ?", sessionId, userId);
        return true;
    }

    @Transactional
    public boolean deleteAdminSession(String sessionId) {
        return deleteAdminSession(sessionId, null);
    }

    @Transactional
    public boolean deleteAdminSession(String sessionId, Long userId) {
        Long resolvedUserId = userId;
        boolean legacyOwner = false;
        if (resolvedUserId == null) {
            List<Map<String, Object>> owners = jdbcTemplate.queryForList(
                    "SELECT user_id FROM routing_call_log WHERE session_id = ? " +
                            "GROUP BY user_id ORDER BY CASE WHEN user_id IS NULL THEN 0 ELSE 1 END, user_id",
                    sessionId);
            if (owners.size() != 1) {
                return false;
            }
            resolvedUserId = nullableLong(owners.getFirst().get("user_id"));
            legacyOwner = resolvedUserId == null;
        }

        int deleted = legacyOwner
                ? jdbcTemplate.update(
                        "DELETE FROM routing_call_log WHERE session_id = ? AND user_id IS NULL", sessionId)
                : jdbcTemplate.update(
                        "DELETE FROM routing_call_log WHERE session_id = ? AND user_id = ?",
                        sessionId, resolvedUserId);
        if (deleted == 0) {
            return false;
        }
        if (legacyOwner) {
            jdbcTemplate.update(
                    "DELETE FROM conversation_feedback WHERE session_id = ? AND user_id IS NULL", sessionId);
        } else {
            jdbcTemplate.update(
                    "DELETE FROM conversation_feedback WHERE session_id = ? AND user_id = ?",
                    sessionId, resolvedUserId);
            jdbcTemplate.update(
                    "DELETE FROM conversation_session_state WHERE session_id = ? AND user_id = ?",
                    sessionId, resolvedUserId);
        }
        return true;
    }

    // ==================== User feedback and lifecycle ====================

    @Transactional
    public Optional<SatisfactionResult> saveSatisfaction(
            String sessionId, Long userId, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        if (userId == null || !lockOwnedSession(sessionId, userId)) {
            return Optional.empty();
        }

        String safeComment = comment == null ? "" : comment.trim();
        int updated = jdbcTemplate.update(
                "UPDATE conversation_feedback SET rating = ?, feedback_text = ? " +
                        "WHERE session_id = ? AND user_id = ?",
                rating, safeComment, sessionId, userId);
        if (updated == 0) {
            String agentName = latestAgent(sessionId, userId);
            jdbcTemplate.update(
                    "INSERT INTO conversation_feedback " +
                            "(session_id, user_id, rating, feedback_text, agent_name, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    sessionId, userId, rating, safeComment, agentName);
        }
        return Optional.of(new SatisfactionResult(sessionId, rating, safeComment));
    }

    @Transactional
    public boolean closeSession(String sessionId, Long userId) {
        if (userId == null || !ownsSession(sessionId, userId)) {
            return false;
        }
        int updated = jdbcTemplate.update(
                "UPDATE conversation_session_state SET status = 'CLOSED', " +
                        "closed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE user_id = ? AND session_id = ?", userId, sessionId);
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO conversation_session_state " +
                                "(user_id, session_id, status, closed_at, updated_at) " +
                                "VALUES (?, ?, 'CLOSED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        userId, sessionId);
            } catch (DuplicateKeyException race) {
                jdbcTemplate.update(
                        "UPDATE conversation_session_state SET status = 'CLOSED', " +
                                "closed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP " +
                                "WHERE user_id = ? AND session_id = ?", userId, sessionId);
            }
        }
        return true;
    }

    private boolean ownsSession(String sessionId, Long userId) {
        return queryLong(
                "SELECT COUNT(*) FROM routing_call_log WHERE session_id = ? AND user_id = ?",
                sessionId, userId) > 0;
    }

    /**
     * Serialize first-time feedback writes on an existing conversation row.
     * This avoids duplicate inserts without imposing a new unique constraint
     * that could reject historical production data during migration.
     */
    private boolean lockOwnedSession(String sessionId, Long userId) {
        return !jdbcTemplate.queryForList(
                "SELECT id FROM routing_call_log WHERE session_id = ? AND user_id = ? " +
                        "ORDER BY id LIMIT 1 FOR UPDATE",
                sessionId, userId).isEmpty();
    }

    private String latestAgent(String sessionId, Long userId) {
        List<String> agents = jdbcTemplate.queryForList(
                "SELECT routed_agent FROM routing_call_log WHERE session_id = ? AND user_id = ? " +
                        "ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, sessionId, userId);
        return agents.isEmpty() ? null : agents.getFirst();
    }

    // ==================== Persistent FAQ / knowledge entries ====================

    public List<FaqItem> getFaqs() {
        try {
            return jdbcTemplate.queryForList(
                            "SELECT id, category, question, answer, keywords, source_name, source_type, " +
                                    "hit_count, created_at, updated_at " +
                                    "FROM admin_faq ORDER BY updated_at DESC, id DESC")
                    .stream().map(this::mapFaq).toList();
        } catch (Exception e) {
            log.error("[Admin] FAQ list query failed", e);
            throw new IllegalStateException("Unable to load administration knowledge base", e);
        }
    }

    @Transactional
    public FaqItem createFaq(Map<String, String> body) {
        String question = valueOrDefault(body.get("question"), "");
        String answer = valueOrDefault(body.get("answer"), "");
        if (question.isBlank() || answer.isBlank()) {
            throw new IllegalArgumentException("question and answer are required");
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO admin_faq (category, question, answer, keywords, source_type, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, 'manual', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    valueOrDefault(body.get("category"), "general"),
                    question,
                    answer,
                    valueOrDefault(body.get("keywords"), ""));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("an FAQ with the same question already exists");
        }
        return findFaqByQuestion(question)
                .orElseThrow(() -> new IllegalStateException("FAQ insert succeeded but could not be read"));
    }

    @Transactional
    public FaqItem updateFaq(String id, Map<String, String> body) {
        Long faqId = parseId(id);
        if (faqId == null) {
            return null;
        }
        Optional<FaqItem> current = findFaq(faqId);
        if (current.isEmpty()) {
            return null;
        }
        FaqItem existing = current.get();
        String question = valueOrDefault(body.get("question"), existing.question());
        String answer = valueOrDefault(body.get("answer"), existing.answer());
        if (question.isBlank() || answer.isBlank()) {
            throw new IllegalArgumentException("question and answer are required");
        }
        jdbcTemplate.update(
                "UPDATE admin_faq SET category = ?, question = ?, answer = ?, keywords = ?, " +
                        "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                valueOrDefault(body.get("category"), existing.category()),
                question,
                answer,
                valueOrDefault(body.get("keywords"), existing.keywords()),
                faqId);
        return findFaq(faqId).orElse(null);
    }

    /**
     * Imports a client-parsed external knowledge file as one atomic batch.
     * Duplicate questions are skipped by default or updated when overwrite is enabled.
     */
    @Transactional
    public FaqImportResult importFaqs(
            String sourceName,
            String sourceType,
            boolean overwrite,
            List<Map<String, String>> items) {
        String normalizedSourceName = valueOrDefault(sourceName, "external-knowledge");
        String normalizedSourceType = valueOrDefault(sourceType, "").toLowerCase(Locale.ROOT);
        if (normalizedSourceName.isBlank() || normalizedSourceName.length() > 255) {
            throw new IllegalArgumentException("sourceName is required and must not exceed 255 characters");
        }
        if (!FAQ_IMPORT_TYPES.contains(normalizedSourceType)) {
            throw new IllegalArgumentException("sourceType must be json, csv, or markdown");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("at least one knowledge item is required");
        }
        if (items.size() > MAX_FAQ_IMPORT_SIZE) {
            throw new IllegalArgumentException("a single import cannot exceed " + MAX_FAQ_IMPORT_SIZE + " items");
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        Set<String> seenQuestions = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            Map<String, String> item = items.get(index);
            if (item == null) {
                throw new IllegalArgumentException("knowledge item " + (index + 1) + " is empty");
            }
            String question = validateImportField(item.get("question"), "question", index, 500, true);
            String answer = validateImportField(item.get("answer"), "answer", index, MAX_FAQ_ANSWER_LENGTH, true);
            String category = validateImportField(item.get("category"), "category", index, 50, false);
            String keywords = validateImportField(item.get("keywords"), "keywords", index, 1000, false);
            if (category.isBlank()) category = "general";

            String questionKey = question.toLowerCase(Locale.ROOT);
            if (!seenQuestions.add(questionKey)) {
                skipped++;
                continue;
            }
            Long existingId = findFaqIdByQuestionIgnoreCase(question);
            if (existingId != null) {
                if (!overwrite) {
                    skipped++;
                    continue;
                }
                jdbcTemplate.update(
                        "UPDATE admin_faq SET category = ?, question = ?, answer = ?, keywords = ?, " +
                                "source_name = ?, source_type = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        category, question, answer, keywords,
                        normalizedSourceName, normalizedSourceType, existingId);
                updated++;
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO admin_faq (category, question, answer, keywords, source_name, source_type, " +
                            "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    category, question, answer, keywords, normalizedSourceName, normalizedSourceType);
            created++;
        }
        return new FaqImportResult(items.size(), created, updated, skipped);
    }

    public boolean deleteFaq(String id) {
        Long faqId = parseId(id);
        return faqId != null && jdbcTemplate.update("DELETE FROM admin_faq WHERE id = ?", faqId) > 0;
    }

    public boolean hitFaq(String id) {
        Long faqId = parseId(id);
        return faqId != null && jdbcTemplate.update(
                "UPDATE admin_faq SET hit_count = hit_count + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                faqId) > 0;
    }

    private Optional<FaqItem> findFaq(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords, source_name, source_type, " +
                        "hit_count, created_at, updated_at " +
                        "FROM admin_faq WHERE id = ?", id);
        return rows.stream().findFirst().map(this::mapFaq);
    }

    private Optional<FaqItem> findFaqByQuestion(String question) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords, source_name, source_type, " +
                        "hit_count, created_at, updated_at " +
                        "FROM admin_faq WHERE question = ?", question);
        return rows.stream().findFirst().map(this::mapFaq);
    }

    private Long findFaqIdByQuestionIgnoreCase(String question) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM admin_faq WHERE LOWER(question) = LOWER(?) ORDER BY id LIMIT 1",
                Long.class,
                question);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static String validateImportField(
            String value,
            String field,
            int index,
            int maxLength,
            boolean required) {
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

    private void seedDefaultFaqs() {
        seedFaq("order", "怎么查询我的订单？",
                "请提供您的订单号（格式：ORD-xxx），我可以帮您查询订单状态和物流信息。",
                "订单查询,订单状态,物流");
        seedFaq("order", "如何申请退款？",
                "请提供订单号，我可以帮您查询退款政策和流程。",
                "退款,退货,取消订单");
        seedFaq("product", "如何查询商品信息？",
                "请告诉我商品名称或编码，我可以帮您查询商品详情、价格和库存情况。",
                "商品查询,商品信息,价格");
        seedFaq("general", "你们有哪些服务？",
                "我可以帮助查询订单、商品信息和常见问题。",
                "服务,功能,帮助");
    }

    private void seedFaq(String category, String question, String answer, String keywords) {
        if (queryLong("SELECT COUNT(*) FROM admin_faq WHERE question = ?", question) > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO admin_faq (category, question, answer, keywords, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    category, question, answer, keywords);
        } catch (DuplicateKeyException ignored) {
            // Multiple consumer instances may seed concurrently.
        }
    }

    // ==================== Costs ====================

    public Map<String, Object> getCosts() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> byAgent = jdbcTemplate.queryForList(
                    "SELECT routed_agent, COUNT(*) calls, COALESCE(SUM(latency_ms), 0) total_latency " +
                            "FROM routing_call_log WHERE created_at >= " + dialect.dateSub("7") + " " +
                            "GROUP BY routed_agent ORDER BY calls DESC " + dialect.limit(10));
            result.put("byAgent", byAgent);
            List<Map<String, Object>> daily = jdbcTemplate.queryForList(
                    "SELECT " + dialect.dateFunc("created_at") + " date, COUNT(*) call_count, " +
                            "COALESCE(SUM(latency_ms), 0) total_latency FROM routing_call_log " +
                            "WHERE created_at >= " + dialect.dateSub("7") + " " +
                            "GROUP BY " + dialect.dateFunc("created_at") + " ORDER BY date");
            result.put("daily", daily);
            long totalCalls = queryLong(
                    "SELECT COUNT(*) FROM routing_call_log WHERE created_at >= " + dialect.dateSub("7"));
            long totalLatency = queryLong(
                    "SELECT COALESCE(SUM(latency_ms), 0) FROM routing_call_log " +
                            "WHERE created_at >= " + dialect.dateSub("7"));
            result.put("summary", Map.of(
                    "totalCalls7d", totalCalls,
                    "totalLatencyMs7d", totalLatency,
                    "avgLatencyMs", totalCalls == 0 ? 0 : totalLatency / totalCalls));
        } catch (Exception e) {
            log.warn("[Admin] Cost query failed: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Mapping helpers ====================

    private SessionSummary mapSessionSummary(Map<String, Object> row) {
        String agentName = stringValue(row, "agent_name");
        return new SessionSummary(
                stringValue(row, "session_id"),
                nullableLong(row.get("user_id")),
                stringValue(row, "username"),
                stringValue(row, "title"),
                agentName,
                mapAgentToIntent(agentName),
                stringValue(row, "status"),
                nullableInteger(row.get("satisfaction")),
                stringValue(row, "satisfaction_comment"),
                Math.toIntExact(longValue(row, "message_count")),
                nullableLong(row.get("total_tokens")),
                Math.toIntExact(longValue(row, "token_tracked_turns")),
                Math.toIntExact(longValue(row, "total_turns")),
                longValue(row, "token_tracked_turns") == longValue(row, "total_turns"),
                timestampValue(row.get("created_at")),
                timestampValue(row.get("updated_at")));
    }

    private Map<String, Object> legacySessionMap(SessionSummary item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.sessionId());
        map.put("sessionId", item.sessionId());
        map.put("userId", item.userId());
        map.put("username", item.username());
        map.put("title", item.title());
        map.put("agentName", item.agentName());
        map.put("intent", item.intent());
        map.put("status", item.status());
        map.put("satisfaction", item.satisfaction() == null ? 0 : item.satisfaction());
        map.put("satisfactionComment", item.satisfactionComment());
        map.put("messageCount", item.messageCount());
        map.put("totalTokens", item.totalTokens());
        map.put("tokenTrackedTurns", item.tokenTrackedTurns());
        map.put("totalTurns", item.totalTurns());
        map.put("tokenUsageComplete", item.tokenUsageComplete());
        map.put("createdAt", item.createdAt());
        map.put("created_at", item.createdAt());
        map.put("updatedAt", item.updatedAt());
        return map;
    }

    private Map<String, Object> legacyDetailMap(SessionDetail detail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", detail.sessionId());
        map.put("userId", detail.userId());
        map.put("username", detail.username());
        map.put("title", detail.title());
        map.put("agentName", detail.agentName());
        map.put("intent", detail.intent());
        map.put("status", detail.status());
        map.put("satisfaction", detail.satisfaction());
        map.put("satisfactionComment", detail.satisfactionComment());
        map.put("messageCount", detail.messageCount());
        map.put("promptTokens", detail.promptTokens());
        map.put("completionTokens", detail.completionTokens());
        map.put("totalTokens", detail.totalTokens());
        map.put("tokenTrackedTurns", detail.tokenTrackedTurns());
        map.put("totalTurns", detail.totalTurns());
        map.put("tokenUsageComplete", detail.tokenUsageComplete());
        map.put("createdAt", detail.createdAt());
        map.put("updatedAt", detail.updatedAt());
        map.put("messages", detail.messages());
        return map;
    }

    private FaqItem mapFaq(Map<String, Object> row) {
        String sourceType = stringValue(row, "source_type");
        return new FaqItem(
                Objects.toString(row.get("id"), ""),
                stringValue(row, "category"),
                stringValue(row, "question"),
                stringValue(row, "answer"),
                stringValue(row, "keywords"),
                stringValue(row, "source_name"),
                sourceType.isBlank() ? "manual" : sourceType,
                longValue(row, "hit_count"),
                timestampValue(row.get("created_at")),
                timestampValue(row.get("updated_at")));
    }

    private long queryLong(String sql, Object... args) {
        Number result = jdbcTemplate.queryForObject(sql, Number.class, args);
        return result == null ? 0L : result.longValue();
    }

    private Double queryNullableDouble(String sql, Object... args) {
        Number result = jdbcTemplate.queryForObject(sql, Number.class, args);
        return result == null ? null : result.doubleValue();
    }

    private static long percentile95(List<Long> sortedValues) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * 0.95) - 1);
        return sortedValues.get(index);
    }

    private static double percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double rounded(Double value) {
        return value == null ? null : BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer nullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static long longValue(Map<String, Object> row, String key) {
        Long value = nullableLong(row.get(key));
        return value == null ? 0L : value;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String stringValue(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private static String nullableString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String timestampValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String dateValue(Object value) {
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        return Objects.toString(value, "");
    }

    private static List<DailyStats> fillLastSevenDays(List<DailyStats> observed) {
        Map<String, DailyStats> byDate = new LinkedHashMap<>();
        for (DailyStats item : observed) {
            byDate.put(item.date(), item);
        }
        List<DailyStats> result = new ArrayList<>(7);
        LocalDate today = LocalDate.now();
        for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
            String date = today.minusDays(daysAgo).toString();
            result.add(byDate.getOrDefault(date, new DailyStats(date, 0, null)));
        }
        return result;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value.trim();
    }

    private static Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String displayStatusSql(String stateAlias, String logAlias) {
        return "CASE " +
                "WHEN UPPER(COALESCE(" + stateAlias + ".status, '')) = 'CLOSED' THEN 'CLOSED' " +
                "WHEN UPPER(COALESCE(" + stateAlias + ".status, '')) IN ('SUSPENDED', 'FROZEN') THEN 'SUSPENDED' " +
                "WHEN LOWER(COALESCE(" + logAlias + ".routed_agent, '')) " +
                "IN ('human', 'human_service', 'human-service') THEN 'HUMAN_TRANSFER' " +
                "WHEN UPPER(COALESCE(" + logAlias + ".status, '')) " +
                "IN ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'TIMEOUT') " +
                "THEN UPPER(" + logAlias + ".status) ELSE 'FAILED' END";
    }

    private static String normalizeDisplayStatus(
            String lifecycleStatus, String agentName, String callStatus) {
        if ("CLOSED".equalsIgnoreCase(lifecycleStatus)) {
            return "CLOSED";
        }
        if ("SUSPENDED".equalsIgnoreCase(lifecycleStatus)
                || "FROZEN".equalsIgnoreCase(lifecycleStatus)) {
            return "SUSPENDED";
        }
        String normalizedAgent = agentName == null ? "" : agentName.toLowerCase(Locale.ROOT);
        if (normalizedAgent.equals("human")
                || normalizedAgent.equals("human_service")
                || normalizedAgent.equals("human-service")) {
            return "HUMAN_TRANSFER";
        }
        String normalizedCallStatus = callStatus == null
                ? "FAILED" : callStatus.toUpperCase(Locale.ROOT);
        return switch (normalizedCallStatus) {
            case "SUCCESS", "PARTIAL_SUCCESS", "FAILED", "TIMEOUT" -> normalizedCallStatus;
            default -> "FAILED";
        };
    }

    private String mapAgentToIntent(String agent) {
        if (agent == null || agent.isBlank()) {
            return "general";
        }
        return switch (agent.toLowerCase(Locale.ROOT)) {
            case "order", "order-service", "order_service" -> "order";
            case "product", "product-service", "product_service" -> "product";
            case "refund", "refund-service", "refund_service" -> "refund";
            case "human", "human-service", "human_service" -> "handoff";
            case "general", "router_fallback", "none" -> "general";
            default -> agent;
        };
    }

    // ==================== API contracts ====================

    public record AdminStats(
            long totalSessions,
            long totalUsers,
            long ratedSessions,
            Long totalTokens,
            Double avgTokensPerSession,
            long tokenTrackedSessions,
            long tokenTrackedTurns,
            long totalTurns,
            double tokenCoverageRate,
            Double averageSatisfaction,
            double successRate,
            double handoffRate,
            long avgLatencyMs,
            long p95LatencyMs,
            List<StatusCount> statusBreakdown,
            List<IntentCount> intentBreakdown,
            List<DailyStats> daily) {
        public static AdminStats empty() {
            return new AdminStats(0, 0, 0, null, null, 0, 0, 0, 0,
                    null, 0, 0, 0, 0,
                    List.of(), List.of(), List.of());
        }
    }

    public record StatusCount(String status, long count) {}

    public record IntentCount(String intent, long count) {}

    public record DailyStats(String date, long sessionCount, Double avgSatisfaction) {}

    public record SessionPage(List<SessionSummary> items, long total, int page, int size) {}

    public record SessionSummary(
            String sessionId,
            Long userId,
            String username,
            String title,
            String agentName,
            String intent,
            String status,
            Integer satisfaction,
            String satisfactionComment,
            int messageCount,
            Long totalTokens,
            int tokenTrackedTurns,
            int totalTurns,
            boolean tokenUsageComplete,
            String createdAt,
            String updatedAt) {}

    public record SessionDetail(
            String sessionId,
            Long userId,
            String username,
            String title,
            String agentName,
            String intent,
            String status,
            Integer satisfaction,
            String satisfactionComment,
            int messageCount,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            int tokenTrackedTurns,
            int totalTurns,
            boolean tokenUsageComplete,
            String createdAt,
            String updatedAt,
            List<SessionMessage> messages) {}

    public record SessionMessage(
            String id,
            String role,
            String content,
            String createdAt,
            String requestId,
            String agentName,
            String status,
            Long latencyMs,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            String promptSnapshot,
            Boolean toolUsageComplete,
            List<ToolUsageCache.ToolCall> toolCalls) {}

    public record SatisfactionResult(String sessionId, int rating, String comment) {}

    public record FaqImportResult(int total, int created, int updated, int skipped) {}

    public record FaqItem(
            String id,
            String category,
            String question,
            String answer,
            String keywords,
            String sourceName,
            String sourceType,
            long hitCount,
            String createdAt,
            String updatedAt) {}
}
