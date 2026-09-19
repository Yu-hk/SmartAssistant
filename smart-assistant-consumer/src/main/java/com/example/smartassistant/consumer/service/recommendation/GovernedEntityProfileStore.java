package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.memory.EntityProfileStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Low-trust entity facts remain separate from the reliable commerce snapshot. */
@Service
public class GovernedEntityProfileStore implements EntityProfileStore {
    private static final Set<String> CATEGORIES = Set.of("name", "location", "preference", "fear", "hobby");
    private final JdbcTemplate jdbc;
    private final ProfileGenerationFence fence;

    public GovernedEntityProfileStore(JdbcTemplate jdbc, ProfileGenerationFence fence) {
        this.jdbc = jdbc;
        this.fence = fence;
    }

    @Override public long capture(Long userId) { return fence.capture(userId); }
    @Override public long admittedGeneration(Long userId,String requestId,String question) {
        return new ProfileAdmissionStore(jdbc,fence).requireExisting(userId,requestId,question);
    }

    @Override public void save(Long userId, long generation, Map<String, String> facts) {
        if (facts == null || facts.isEmpty()) return;
        // Validate the entire batch before touching storage; never partially accept model fields.
        Map<String, String> accepted = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key == null || !CATEGORIES.contains(key) || value == null || value.isBlank() || value.length() > 500
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid entity profile fact");
            }
            accepted.put(key, value.strip());
        });
        fence.write(userId, generation, () -> {
            accepted.forEach((key, value) -> jdbc.update("""
                INSERT INTO user_profile_entity_fact(user_id, generation, category, fact_value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, generation, category) DO UPDATE
                SET fact_value=EXCLUDED.fact_value, updated_at=CURRENT_TIMESTAMP,
                    expires_at=CURRENT_TIMESTAMP + INTERVAL '90 days'
                """, userId, generation, key, value));
            return null;
        });
    }

    @Override public Map<String, String> read(Long userId) {
        if (userId == null || userId <= 0) return Map.of();
        // One statement gives a consistent lifecycle/data snapshot; no unguarded Redis fallback.
        return jdbc.query("""
            SELECT f.category, f.fact_value FROM user_profile_entity_fact f
            JOIN profile_lifecycle l ON l.user_id=f.user_id AND l.generation=f.generation
            WHERE f.user_id=? AND l.analysis_enabled AND f.expires_at>CURRENT_TIMESTAMP
            ORDER BY f.category
            """, rs -> {
                Map<String, String> result = new LinkedHashMap<>();
                while (rs.next()) result.put(rs.getString(1), rs.getString(2));
                return result;
            }, userId);
    }

    /** Logical expiry is immediate; physical cleanup is bounded and may lag during outages. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${profile.entity.cleanup-ms:3600000}")
    public void cleanupExpired() {
        jdbc.update("""
            DELETE FROM user_profile_entity_fact WHERE (user_id, generation, category) IN
            (SELECT user_id, generation, category FROM user_profile_entity_fact
             WHERE expires_at<=CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED)
            """);
    }
}
