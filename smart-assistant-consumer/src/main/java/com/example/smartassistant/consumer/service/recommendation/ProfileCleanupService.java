package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal, disabled-by-default cleanup coordinator. No HTTP deletion/resume endpoint exists. */
@Service
public class ProfileCleanupService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ProfileCleanupService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProfileRedisCleanup redis;
    private final boolean enabled;
    private static final List<String> AUTOMATED=List.of("POSTGRES_PROFILE","REDIS_INDEXED");
    public ProfileCleanupService(JdbcTemplate jdbc,PlatformTransactionManager manager,ProfileRedisCleanup redis,
            @Value("${profile.cleanup.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.redis=redis;this.enabled=enabled;tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }

    /** Future authenticated adapter must supply the principal's own ID, never a request-body target. */
    public UUID request(Long principalUserId,UUID idempotencyKey) {
        if(!enabled) throw new IllegalStateException("Profile cleanup is not available");
        if(principalUserId==null || principalUserId<=0 || idempotencyKey==null) throw new IllegalArgumentException("Owner and idempotency key required");
        return tx.execute(status->{
            limits();
            jdbc.update("INSERT INTO profile_lifecycle(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING",principalUserId);
            jdbc.queryForMap("SELECT generation FROM profile_lifecycle WHERE user_id=? FOR UPDATE",principalUserId);
            var existing=jdbc.queryForList("SELECT job_id FROM profile_cleanup_job WHERE user_id=? AND idempotency_key=?",UUID.class,principalUserId,idempotencyKey);
            if(!existing.isEmpty()) return existing.getFirst();
            long generation=jdbc.queryForObject("""
                UPDATE profile_lifecycle SET generation=generation+1,analysis_enabled=false,updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? RETURNING generation
                """,Long.class,principalUserId);
            UUID job=UUID.randomUUID();
            jdbc.update("INSERT INTO profile_cleanup_job(job_id,user_id,idempotency_key,generation,state) VALUES (?,?,?,?,'PAUSED')",job,principalUserId,idempotencyKey,generation);
            for(String target:AUTOMATED)
                jdbc.update("INSERT INTO profile_cleanup_receipt(job_id,target,state) VALUES (?,?,'PENDING')",job,target);
            for(String target:List.of("LEGACY_STORAGE","DERIVED_COPIES","BACKUP_RESTORE"))
                jdbc.update("INSERT INTO profile_cleanup_receipt(job_id,target,state,error_code) VALUES (?,?,'BLOCKED','INVENTORY_OR_ADAPTER_REQUIRED')",job,target);
            return job;
        });
    }

    /** Ownership-constrained status, with no deleted content in jobs or receipts. */
    public Map<String,Object> status(Long principalUserId,UUID job) {
        if(!enabled || principalUserId==null || principalUserId<=0 || job==null) return Map.of();
        var rows=jdbc.queryForList("SELECT job_id,generation,state FROM profile_cleanup_job WHERE job_id=? AND user_id=?",job,principalUserId);
        if(rows.isEmpty()) return Map.of();
        var result=new java.util.LinkedHashMap<String,Object>(rows.getFirst());
        result.put("targets",jdbc.queryForList("SELECT target,state,attempts,error_code FROM profile_cleanup_receipt WHERE job_id=? ORDER BY target",job));
        return result;
    }

    @Scheduled(fixedDelayString="${profile.cleanup.poll-ms:5000}")
    public void poll() {
        if(!enabled) return;
        try { runNext(); }
        catch(RuntimeException unavailable) { log.warn("[ProfileCleanup] Retry later: type={}",unavailable.getClass().getSimpleName()); }
    }

    public boolean runNext() {
        if(!enabled) return false;
        var jobs=jdbc.queryForList("""
            SELECT j.job_id FROM profile_cleanup_job j WHERE j.state<>'STALE' AND EXISTS
            (SELECT 1 FROM profile_cleanup_receipt r WHERE r.job_id=j.job_id
             AND r.state IN ('PENDING','RETRY') AND r.next_attempt_at<=CURRENT_TIMESTAMP)
            ORDER BY j.created_at,j.job_id LIMIT 1
            """,UUID.class);
        if(jobs.isEmpty()) return false;
        UUID job=jobs.getFirst();
        for(String target:AUTOMATED) {
            try { runTarget(job,target); }
            catch(StaleCleanup stale) { markStale(job); }
            catch(RuntimeException failure) { retry(job,target); }
        }
        refresh(job);
        return true;
    }

    private void runTarget(UUID job,String target) {
        tx.executeWithoutResult(status->{
            limits();
            // Each target and its receipt commit together; crash before commit is safely retried.
            var rows=jdbc.queryForList("SELECT user_id,generation,state FROM profile_cleanup_job WHERE job_id=? FOR UPDATE SKIP LOCKED",job);
            if(rows.isEmpty() || "STALE".equals(rows.getFirst().get("state"))) return;
            var pending=jdbc.queryForList("SELECT state FROM profile_cleanup_receipt WHERE job_id=? AND target=? AND state IN ('PENDING','RETRY') AND next_attempt_at<=CURRENT_TIMESTAMP",job,target);
            if(pending.isEmpty()) return;
            long user=((Number)rows.getFirst().get("user_id")).longValue(),generation=((Number)rows.getFirst().get("generation")).longValue();
            var lifecycle=jdbc.queryForMap("SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=? FOR UPDATE",user);
            if(Boolean.TRUE.equals(lifecycle.get("analysis_enabled")) || ((Number)lifecycle.get("generation")).longValue()!=generation) throw new StaleCleanup();
            if("POSTGRES_PROFILE".equals(target)) {
                // Literal allowlist only. Accounts, orders, chats, admissions and tombstones are retained.
                jdbc.update("DELETE FROM profile_commit_candidate WHERE user_id=?",user);
                jdbc.update("DELETE FROM profile_agent_memory WHERE user_id=?",user);
                jdbc.update("DELETE FROM user_profile_entity_fact WHERE user_id=?",user);
                jdbc.update("DELETE FROM user_profile_change_log WHERE user_id=?",user);
                jdbc.update("DELETE FROM user_profile_snapshot WHERE user_id=?",user);
            } else redis.clean(user,generation);
            jdbc.update("UPDATE profile_cleanup_receipt SET state='SUCCEEDED',attempts=attempts+1,error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE job_id=? AND target=?",job,target);
        });
    }
    private void retry(UUID job,String target) {
        tx.executeWithoutResult(status->{
            limits();
            var locked=jdbc.queryForList("SELECT job_id FROM profile_cleanup_job WHERE job_id=? AND state<>'STALE' FOR UPDATE",job);
            if(locked.isEmpty()) return;
            jdbc.update("""
                UPDATE profile_cleanup_receipt SET state='RETRY',attempts=attempts+1,error_code='TARGET_UNAVAILABLE',
                    next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',updated_at=CURRENT_TIMESTAMP
                WHERE job_id=? AND target=? AND state IN ('PENDING','RETRY')
                """,job,target);
        });
    }
    private void markStale(UUID job) {
        tx.executeWithoutResult(status->{
            limits();
            jdbc.update("UPDATE profile_cleanup_job SET state='STALE',updated_at=CURRENT_TIMESTAMP WHERE job_id=?",job);
            jdbc.update("UPDATE profile_cleanup_receipt SET state='BLOCKED',error_code='LIFECYCLE_CHANGED',updated_at=CURRENT_TIMESTAMP WHERE job_id=? AND state IN ('PENDING','RETRY')",job);
        });
    }
    private void refresh(UUID job) {
        jdbc.update("""
            UPDATE profile_cleanup_job j SET state=CASE WHEN EXISTS
              (SELECT 1 FROM profile_cleanup_receipt r WHERE r.job_id=j.job_id AND r.state<>'SUCCEEDED')
              THEN 'PARTIAL' ELSE 'ONLINE_CLEANED' END,updated_at=CURRENT_TIMESTAMP
            WHERE j.job_id=? AND j.state<>'STALE'
            """,job);
    }
    private void limits() { jdbc.execute("SET LOCAL lock_timeout='500ms'");jdbc.execute("SET LOCAL statement_timeout='3s'"); }
    private static final class StaleCleanup extends RuntimeException { }
}
