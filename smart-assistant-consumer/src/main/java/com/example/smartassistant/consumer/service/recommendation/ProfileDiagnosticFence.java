package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.entity.RoutingCallLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Serializes late diagnostic writes with deletion, without deleting chat content. */
@Service
public class ProfileDiagnosticFence {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.smartassistant.common.memory.ProfileRecoveryGuard recoveryGuard;
    public ProfileDiagnosticFence(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);tx.setTimeout(3);
    }
    public void save(RoutingCallLog row,Runnable insert) {
        try { if(recoveryGuard!=null) recoveryGuard.requireSafe(); }
        catch(RuntimeException unavailable) { row.setLlmReceivedQuestion(null); }
        if(row.getUserId()==null || row.getUserId()<=0) {
            row.setLlmReceivedQuestion(null);insert.run();return;
        }
        tx.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL lock_timeout='500ms'");jdbc.execute("SET LOCAL statement_timeout='2s'");
            jdbc.update("INSERT INTO profile_lifecycle(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING",row.getUserId());
            var control=jdbc.queryForMap("SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=? FOR SHARE",row.getUserId());
            if(((Number)control.get("generation")).longValue()>0 || !Boolean.TRUE.equals(control.get("analysis_enabled"))) row.setLlmReceivedQuestion(null);
            insert.run();
        });
    }
}
