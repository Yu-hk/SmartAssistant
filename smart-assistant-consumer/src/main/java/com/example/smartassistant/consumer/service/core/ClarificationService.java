package com.example.smartassistant.consumer.service.core;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.smartassistant.consumer.entity.RoutingCallLog;
import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Stateless form permits contain field names, not submitted personal information. */
@Service
public class ClarificationService {
    private final SecretKey key;
    private final ClarificationPlanner planner;
    private final RoutingCallLogMapper logs;
    private final StringRedisTemplate redis;
    public ClarificationService(@Value("${jwt.secret:${JWT_SECRET:}}") String secret,
                                ClarificationPlanner planner, RoutingCallLogMapper logs, StringRedisTemplate redis) {
        this.planner = planner; this.logs = logs; this.redis = redis;
        this.key = secret != null && secret.length() >= 32
                ? Keys.hmacShaKeyFor(digest("clarification-form-v2:" + secret)) : null;
    }
    public record Form(int version, String token, long expiresAt, List<ClarificationPolicy.Field> fields) { }
    public record Submission(String token, Map<String, String> values) { }
    public record Issued(Form form, com.example.smartassistant.consumer.service.infrastructure.TokenUsageExtractor.TokenUsage usage) { }
    public Issued issue(String owner, String session, String sourceRequest, String question, String reply, String status) {
        var empty = new com.example.smartassistant.consumer.service.infrastructure.TokenUsageExtractor.TokenUsage(0L, 0L, 0L);
        if (key == null || owner == null || session == null || sourceRequest == null) return new Issued(null, empty);
        var plan = planner.plan(question, reply, status);
        if (plan.keys().isEmpty()) return new Issued(null, plan.usage());
        long expiresAt = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
        String token = Jwts.builder().subject(owner).claim("purpose", "clarification-v2")
                .claim("session", session).claim("source", sourceRequest).claim("fields", plan.keys())
                .id(UUID.randomUUID().toString()).expiration(new Date(expiresAt)).signWith(key).compact();
        return new Issued(new Form(2, token, expiresAt,
                plan.keys().stream().map(ClarificationPolicy::field).toList()), plan.usage());
    }
    /** Read only the owner's current turn. Deleted, old and foreign sessions fail closed. */
    public RoutingCallLog latest(String owner, String session) {
        if (owner == null || session == null || session.length() > 128) throw invalid();
        var rows = logs.selectList(new QueryWrapper<RoutingCallLog>().eq("user_id", Long.valueOf(owner))
                .eq("session_id", session).orderByDesc("created_at", "id").last("LIMIT 1"));
        if (rows.isEmpty()) throw invalid();
        return rows.getFirst();
    }
    /** Called while holding the existing conversation lease, before preprocessing or business dispatch. */
    public String accept(String owner, String session, Submission submission) {
        if (key == null || submission == null || submission.token() == null || submission.token().length() > 4096) throw invalid();
        try {
            var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(submission.token()).getPayload();
            if (!owner.equals(claims.getSubject()) || !session.equals(claims.get("session"))
                    || !"clarification-v2".equals(claims.get("purpose")) || claims.getExpiration() == null) throw invalid();
            var raw = claims.get("fields", List.class);
            if (raw == null || raw.stream().anyMatch(value -> !(value instanceof String))) throw invalid();
            List<String> keys = new ArrayList<>();
            for (Object value : raw) keys.add((String) value);
            String message = ClarificationPolicy.reply(keys, submission.values());
            var last = latest(owner, session);
            if (!Objects.equals(last.getRequestId(), claims.get("source"))
                    || !Set.of("SUCCESS", "COMPLETED", "CLARIFICATION").contains(Objects.toString(last.getStatus(), ""))) throw invalid();
            // A hash-only replay marker contains no account id, field value or conversation text.
            String used = "clarification:used:" + HexFormat.of().formatHex(digest(
                    owner + ":" + session + ":" + claims.get("source")));
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(used, "1", Duration.ofMinutes(15)))) throw invalid();
            return message;
        } catch (RuntimeException failure) { throw invalid(); }
    }
    private static byte[] digest(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("表单已失效、已提交或信息不符合要求，请检查后重试，也可以直接用文字补充。");
    }
}
