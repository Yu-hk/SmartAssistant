package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;

/** Authoritative PG event outbox + independent same-host, fsynced journal. */
@Service
public class ProfileControlArchive {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final boolean enabled;
    private final UUID source;
    private final ProfileControlJournal journal;
    public ProfileControlArchive(JdbcTemplate jdbc,PlatformTransactionManager manager,
            @Value("${profile.control.enabled:false}") boolean enabled,
            @Value("${profile.control.directory:/profile-control}") String directory,
            @Value("${profile.control.source-id:}") String sourceId) {
        this.jdbc=jdbc;this.enabled=enabled;
        source=enabled?UUID.fromString(sourceId):null;
        journal=enabled?new ProfileControlJournal(Path.of(directory),source):null;
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(3);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public boolean enabled() { return enabled; }
    public synchronized long sync() {
        if(!enabled) throw new IllegalStateException("Control archive disabled");
        var events=read.execute(status->{
            jdbc.execute("SET LOCAL statement_timeout='2s'");
            var header=jdbc.queryForMap("SELECT source_id,last_sequence FROM profile_control_source WHERE singleton");
            if(!source.equals(header.get("source_id"))) throw new IllegalStateException("Control source mismatch");
            var rows=jdbc.query("SELECT sequence,event_id,source_id,user_id,generation,analysis_enabled FROM profile_control_outbox ORDER BY sequence LIMIT 10001",
                (r,n)->new ProfileControlJournal.Event(r.getLong(1),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getLong(4),r.getLong(5),r.getBoolean(6)));
            if(((Number)header.get("last_sequence")).longValue()!=rows.size()) throw new IllegalStateException("Incomplete control stream");
            return rows;
        });
        try { return journal.sync(events); }
        catch(java.io.IOException failure) { throw new IllegalStateException("Independent control archive unavailable",failure); }
    }
    /** Caller must hold the lifecycle row lock inside the deletion/reopen transaction. */
    public void append(UUID eventId,long user,long generation,boolean analysisEnabled) {
        if(!enabled || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Control event requires enabled transactional archive");
        Long sequence=jdbc.queryForObject("UPDATE profile_control_source SET last_sequence=last_sequence+1 WHERE singleton AND source_id=? RETURNING last_sequence",Long.class,source);
        jdbc.update("INSERT INTO profile_control_outbox(sequence,event_id,source_id,user_id,generation,analysis_enabled) VALUES (?,?,?,?,?,?)",
            sequence,eventId,source,user,generation,analysisEnabled);
    }
    public void requireArchived(long user,long generation) {
        long durable=sync();
        var sequence=jdbc.queryForObject("SELECT sequence FROM profile_control_outbox WHERE user_id=? AND generation=? AND NOT analysis_enabled",Long.class,user,generation);
        if(sequence==null || sequence>durable) throw new IllegalStateException("Control event not durably archived");
    }
    @Scheduled(fixedDelayString="${profile.control.poll-ms:5000}")
    public void poll() {
        if(!enabled) return;
        try { sync(); }
        catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Profile control archive unavailable: type={}",failure.getClass().getSimpleName()); }
    }
}
