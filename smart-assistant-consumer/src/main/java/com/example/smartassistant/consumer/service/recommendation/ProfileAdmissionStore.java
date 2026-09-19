package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable request receipt; contains hashes, never the question or profile body. */
@Service
public class ProfileAdmissionStore {
    private final JdbcTemplate jdbc;
    private final ProfileGenerationFence fence;

    public ProfileAdmissionStore(JdbcTemplate jdbc, ProfileGenerationFence fence) {
        this.jdbc = jdbc;
        this.fence = fence;
    }

    public long admit(Long userId, String requestId, String question) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128 || question == null)
            throw new IllegalArgumentException("Profile admission identity required");
        String requestHash = digest(requestId);
        String inputHash = digest(question);
        long generation = fence.capture(userId);
        return fence.write(userId, generation, () -> {
            jdbc.update("""
                INSERT INTO profile_request_admission(user_id, request_hash, input_hash, generation)
                VALUES (?, ?, ?, ?) ON CONFLICT (user_id, request_hash) DO NOTHING
                """, userId, requestHash, inputHash, generation);
            var row = jdbc.queryForMap("""
                SELECT input_hash, generation FROM profile_request_admission
                WHERE user_id=? AND request_hash=?
                """, userId, requestHash);
            if (!inputHash.equals(row.get("input_hash"))
                    || ((Number) row.get("generation")).longValue() != generation)
                throw new ProfileGenerationFence.Rejected();
            return generation;
        });
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
