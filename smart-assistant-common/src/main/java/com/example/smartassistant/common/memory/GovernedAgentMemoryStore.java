package com.example.smartassistant.common.memory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Request-admitted Agent facts. Never falls back to unversioned local files. */
@Component
public class GovernedAgentMemoryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    @Autowired
    public GovernedAgentMemoryStore(ObjectProvider<JdbcTemplate> jdbc,
                                   ObjectProvider<PlatformTransactionManager> manager) {
        this(jdbc.getIfAvailable(),manager.getIfAvailable());
    }

    public GovernedAgentMemoryStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        transaction=manager==null?null:new TransactionTemplate(manager);
        if(transaction!=null) transaction.setTimeout(3);
    }

    public long admission(String agent,String userId,String requestId,String question) {
        long user=owner(agent,userId);
        if(requestId==null || requestId.isBlank() || requestId.length()>128 || question==null) throw new Rejected();
        ready();
        var rows=jdbc.queryForList("""
            SELECT a.generation FROM profile_request_admission a
            JOIN profile_lifecycle l ON l.user_id=a.user_id AND l.generation=a.generation AND l.analysis_enabled
            WHERE a.user_id=? AND a.request_hash=? AND a.input_hash=?
            """,Long.class,user,digest(requestId),digest(question));
        if(rows.size()!=1) throw new Rejected();
        return rows.getFirst();
    }

    public void save(String agent,String userId,long generation,Map<String,String> facts) {
        long user=owner(agent,userId); ready();
        if(generation<0 || facts==null || facts.size()>10) throw new Rejected();
        var checked=new TreeMap<String,String>();
        facts.forEach((key,value)->{
            if(key==null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || value==null || value.isBlank()
                    || value.length()>500 || value.matches("(?s).*[\\p{Cntrl}\\u2028\\u2029].*")) throw new Rejected();
            checked.put(key,value);
        });
        transaction.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL lock_timeout='500ms'");
            var rows=jdbc.queryForList("SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=? FOR UPDATE",user);
            if(rows.size()!=1 || !Boolean.TRUE.equals(rows.getFirst().get("analysis_enabled"))
                    || ((Number)rows.getFirst().get("generation")).longValue()!=generation) throw new Rejected();
            checked.forEach((key,value)->jdbc.update("""
                INSERT INTO profile_agent_memory(user_id,agent,memory_key,memory_value,generation)
                VALUES (?,?,?,?,?) ON CONFLICT(user_id,agent,memory_key) DO UPDATE
                SET memory_value=EXCLUDED.memory_value,generation=EXCLUDED.generation,
                    updated_at=CURRENT_TIMESTAMP,expires_at=CURRENT_TIMESTAMP+INTERVAL '90 days'
                """,user,agent,key,value,generation));
        });
    }

    /** Existing formatter receives dated values; generation/expiry are filtered in the same SQL statement. */
    public Map<String,String> load(String agent,String userId) {
        long user=owner(agent,userId); ready();
        var result=new LinkedHashMap<String,String>();
        jdbc.query("""
            SELECT m.memory_key,m.memory_value,m.updated_at FROM profile_agent_memory m
            JOIN profile_lifecycle l ON l.user_id=m.user_id AND l.generation=m.generation AND l.analysis_enabled
            WHERE m.user_id=? AND m.agent=? AND m.expires_at>CURRENT_TIMESTAMP
            ORDER BY m.updated_at DESC,m.memory_key LIMIT 100
            """,rs->{
                result.put(rs.getString(1),rs.getString(2)+" || "+rs.getTimestamp(3).toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate());
            },user,agent);
        return result;
    }

    private void ready() { if(jdbc==null || transaction==null) throw new Rejected(); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long owner(String agent,String userId) {
        if(!Set.of("order","product","general").contains(agent==null?"":agent)) throw new Rejected();
        try { long user=Long.parseLong(userId); if(user>0) return user; } catch(RuntimeException ignored) {}
        throw new Rejected();
    }
    public static final class Rejected extends RuntimeException {
        public Rejected() { super("Agent memory request is not admitted"); }
    }
}
