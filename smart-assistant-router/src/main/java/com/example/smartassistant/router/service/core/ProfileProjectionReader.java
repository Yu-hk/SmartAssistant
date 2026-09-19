package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** PG admission/lifecycle and Redis ownership are checked on every optional selection. */
@Service
public class ProfileProjectionReader {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transaction;
    @Autowired
    private com.example.smartassistant.common.memory.ProfileRecoveryGuard recoveryGuard;
    static final DefaultRedisScript<String> READ = new DefaultRedisScript<>("""
        for i=1,3 do
          local t=redis.call('TYPE',KEYS[i]).ok
          if t~='none' and t~='string' then return nil end
        end
        if redis.call('GET',KEYS[2])~=ARGV[1] then return nil end
        if redis.call('GET',KEYS[3])~=ARGV[2] then return nil end
        return redis.call('GET',KEYS[1])
        """,String.class);
    @Autowired
    public ProfileProjectionReader(ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<StringRedisTemplate> redis,
                                   ObjectProvider<PlatformTransactionManager> manager) {
        this(jdbc.getIfAvailable(),redis.getIfAvailable(),manager.getIfAvailable());
    }
    public ProfileProjectionReader(JdbcTemplate jdbc,StringRedisTemplate redis,PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.redis=redis;
        transaction=manager==null?null:new TransactionTemplate(manager);
        if(transaction!=null) transaction.setTimeout(2);
    }
    public String read(Long userId,String requestId) {
        if(userId==null || userId<=0 || requestId==null || requestId.isBlank() || requestId.length()>128
                || jdbc==null || redis==null || transaction==null) return null;
        return transaction.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='100ms'");
            if(recoveryGuard!=null) {
                try { recoveryGuard.requireSafe(); }
                catch(com.example.smartassistant.common.memory.ProfileRecoveryGuard.Unavailable unavailable) { return null; }
            }
            var generations=jdbc.queryForList("""
                SELECT l.generation FROM profile_lifecycle l
                JOIN profile_request_admission a ON a.user_id=l.user_id AND a.generation=l.generation
                WHERE l.user_id=? AND l.analysis_enabled AND a.request_hash=? FOR SHARE OF l
                """,Long.class,userId,digest(requestId));
            if(generations.size()!=1) return null;
            long generation=generations.getFirst();
            // Shared PG lock orders this read before concurrent pause/erase transactions.
            return redis.execute(READ,List.of(RoutingKeys.userProfileContext(requestId),
                    "routing:user-profile-owner:"+requestId,"routing:user-profile-lifecycle:"+userId),
                    userId+"|"+generation,generation+"|ACTIVE");
        });
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Legacy cache entries have no request generation; never reuse them after
     * the first erasure. Lock only the short Redis operation, not model calls. */
    public <T> T baselineCache(Long user,java.util.function.Supplier<T> operation) {
        if(user==null || user<=0 || jdbc==null || transaction==null) return null;
        return transaction.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='100ms'");
            jdbc.execute("SET LOCAL statement_timeout='1s'");
            if(recoveryGuard!=null) recoveryGuard.requireSafe();
            jdbc.update("INSERT INTO profile_lifecycle(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING",user);
            var row=jdbc.queryForMap("SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=? FOR UPDATE",user);
            if(((Number)row.get("generation")).longValue()!=0 || !Boolean.TRUE.equals(row.get("analysis_enabled"))) return null;
            return operation.get();
        });
    }
}
