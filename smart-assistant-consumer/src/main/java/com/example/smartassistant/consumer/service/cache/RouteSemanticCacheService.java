package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.common.cache.CacheVersionManager;
import com.example.smartassistant.common.intent.IntentTagGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Consumer-owned semantic routing cache.
 *
 * <p>Only routing hints are cached. Replies are never cached here, so a cache
 * hit cannot leak user-specific content or return stale tool data. Router
 * remains responsible for validating the hint and coordinating execution.</p>
 */
@Service
public class RouteSemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(RouteSemanticCacheService.class);
    private static final String EXACT_PREFIX = "consumer:route-cache:v1:exact:";
    private static final String INTENT_PREFIX = "consumer:route-cache:v1:intent:";
    private static final Set<String> NON_CACHEABLE_AGENTS = Set.of(
            "none", "unknown", "human_service", "builtin_clarification");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IntentTagGenerator intentTagGenerator;
    private final CacheVersionManager cacheVersionManager;
    private final Duration ttl;

    public RouteSemanticCacheService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper objectMapper,
            IntentTagGenerator intentTagGenerator,
            ObjectProvider<CacheVersionManager> versionProvider,
            @Value("${consumer.route-semantic-cache.ttl:PT24H}") Duration ttl) {
        this.redisTemplate = redisProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.intentTagGenerator = intentTagGenerator;
        this.cacheVersionManager = versionProvider.getIfAvailable();
        this.ttl = ttl;
    }

    public CachedRouteHint find(String question) {
        if (redisTemplate == null || question == null || question.isBlank()) {
            return null;
        }
        try {
            CachedRouteHint exact = read(EXACT_PREFIX + sha256(normalize(question)));
            if (valid(exact)) {
                return exact;
            }
            String generatedIntent = intentTagGenerator.generate(question);
            if (generatedIntent == null) {
                return null;
            }
            CachedRouteHint semantic = read(INTENT_PREFIX + sha256(generatedIntent));
            return valid(semantic) ? semantic : null;
        } catch (Exception e) {
            log.warn("[ConsumerRouteCache] lookup failed: {}", e.getMessage());
            return null;
        }
    }

    public void save(String question, Map<String, Object> routeResponse) {
        if (redisTemplate == null || question == null || question.isBlank() || routeResponse == null) {
            return;
        }
        String agentName = stringValue(routeResponse.get("agentName"));
        if (agentName == null || NON_CACHEABLE_AGENTS.contains(agentName)) {
            return;
        }
        if (stringValue(routeResponse.get("error")) != null
                || Boolean.TRUE.equals(routeResponse.get("clarification"))) {
            return;
        }
        String intentTag = stringValue(routeResponse.get("intentTag"));
        if (intentTag == null) {
            intentTag = intentTagGenerator.generate(question);
        }
        if (intentTag == null) {
            return;
        }
        double confidence = numberValue(routeResponse.get("confidence"), 0.7d);
        // Router may advance the shared version while processing this exact
        // request. Refresh after the response so the new hint is not persisted
        // with Consumer's pre-request, five-second local snapshot.
        long version = cacheVersionManager != null
                ? cacheVersionManager.refreshCurrentVersion()
                : 0L;
        CachedRouteHint hint = new CachedRouteHint(agentName, intentTag, confidence, version);
        try {
            String json = objectMapper.writeValueAsString(hint);
            redisTemplate.opsForValue().set(EXACT_PREFIX + sha256(normalize(question)), json, ttl);
            redisTemplate.opsForValue().set(INTENT_PREFIX + sha256(intentTag), json, ttl);
        } catch (Exception e) {
            log.warn("[ConsumerRouteCache] save failed: {}", e.getMessage());
        }
    }

    private CachedRouteHint read(String key) throws Exception {
        String json = redisTemplate.opsForValue().get(key);
        return json == null ? null : objectMapper.readValue(json, CachedRouteHint.class);
    }

    private boolean valid(CachedRouteHint hint) {
        return hint != null
                && hint.agentName() != null
                && !NON_CACHEABLE_AGENTS.contains(hint.agentName())
                && (cacheVersionManager == null || cacheVersionManager.isVersionValid(hint.cacheVersion()));
    }

    private static String stringValue(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static double numberValue(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String normalize(String question) {
        return question.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public record CachedRouteHint(
            String agentName,
            String intentTag,
            double confidence,
            long cacheVersion) {
    }
}
