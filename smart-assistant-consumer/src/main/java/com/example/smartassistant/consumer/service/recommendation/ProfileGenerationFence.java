package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Supplier;

/** PostgreSQL write fence. No erasure/resume endpoint is exposed by this foundation. */
@Service
public class ProfileGenerationFence {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.smartassistant.common.memory.ProfileRecoveryGuard recoveryGuard;

    public void requireRecoverySafe() { if(recoveryGuard!=null) recoveryGuard.requireSafe(); }

    public ProfileGenerationFence(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(3);
    }

    public long capture(Long userId) {
        requireUser(userId);
        requireRecoverySafe();
        try {
            return activeGeneration(jdbc.queryForMap(
                    "SELECT generation, analysis_enabled FROM profile_lifecycle WHERE user_id = ?", userId));
        } catch (EmptyResultDataAccessException absent) {
            // Only absence is legacy generation zero; storage failures never authorize writes.
            return 0L;
        }
    }

    public void requireCurrent(Long userId, long expectedGeneration) {
        if (expectedGeneration < 0 || capture(userId) != expectedGeneration) throw new Rejected();
    }

    public <T> T write(Long userId, long expectedGeneration, Supplier<T> action) {
        requireUser(userId);
        if (expectedGeneration < 0) throw new Rejected();
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '500ms'");
            requireRecoverySafe();
            jdbc.update("INSERT INTO profile_lifecycle (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
            long actual = activeGeneration(jdbc.queryForMap(
                    "SELECT generation, analysis_enabled FROM profile_lifecycle WHERE user_id = ? FOR UPDATE", userId));
            if (actual != expectedGeneration) throw new Rejected();
            // The lifecycle row stays locked until the snapshot AND its change log commit.
            return action.get();
        });
    }

    private static long activeGeneration(Map<String, Object> row) {
        if (!Boolean.TRUE.equals(row.get("analysis_enabled")) || !(row.get("generation") instanceof Number value)
                || value.longValue() < 0) throw new Rejected();
        return value.longValue();
    }

    private static void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("Authenticated profile owner required");
    }

    /** Non-retryable invalidation, deliberately distinct from a snapshot version conflict. */
    public static final class Rejected extends RuntimeException {
        public Rejected() { super("Profile generation is no longer active"); }
    }
}
