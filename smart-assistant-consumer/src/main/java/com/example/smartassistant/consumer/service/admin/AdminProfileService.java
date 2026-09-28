package com.example.smartassistant.consumer.service.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** Metadata-only portrait management. Raw report JSON never leaves this service. */
@Service
public class AdminProfileService {
    private final JdbcTemplate jdbc;
    public AdminProfileService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public Map<String,Object> list(String query,int page,int size) {
        int safePage=Math.max(0,Math.min(page,100000)),safeSize=Math.max(1,Math.min(size,50));
        String pattern="%"+(query==null?"":query.strip()).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var result=new LinkedHashMap<String,Object>();
        result.put("page",safePage);result.put("size",safeSize);
        result.put("total",jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ROLE_USER' AND username ILIKE ? ESCAPE '!'",Long.class,pattern));
        result.put("items",jdbc.queryForList("""
            SELECT u.id,u.username,COALESCE(l.analysis_enabled,true) AS analysis_enabled,
                   s.profile_version,s.schema_version,s.reliable,s.purchase_stage,s.updated_at AS profile_updated_at,
                   j.job_id,j.state AS cleanup_state,j.created_at AS cleanup_requested_at
            FROM users u LEFT JOIN profile_lifecycle l ON l.user_id=u.id
            LEFT JOIN user_profile_snapshot s ON s.user_id=u.id
            LEFT JOIN LATERAL (SELECT job_id,state,created_at FROM profile_cleanup_job
                               WHERE user_id=u.id ORDER BY created_at DESC LIMIT 1) j ON true
            WHERE u.role='ROLE_USER' AND u.username ILIKE ? ESCAPE '!'
            ORDER BY u.id DESC LIMIT ? OFFSET ?
            """,pattern,safeSize,safePage*safeSize));
        return result;
    }

    public Map<String,Object> detail(long userId) {
        var rows=jdbc.queryForList("""
            SELECT u.id,u.username,COALESCE(l.analysis_enabled,true) AS analysis_enabled,
                   l.generation,l.updated_at AS lifecycle_updated_at,
                   s.profile_version,s.schema_version,s.reliable,s.purchase_stage,s.updated_at AS profile_updated_at
            FROM users u LEFT JOIN profile_lifecycle l ON l.user_id=u.id
            LEFT JOIN user_profile_snapshot s ON s.user_id=u.id
            WHERE u.id=? AND u.role='ROLE_USER'
            """,userId);
        if(rows.isEmpty()) return Map.of();
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        result.put("jobs",jdbc.queryForList("""
            SELECT job_id,generation,state,created_at,updated_at FROM profile_cleanup_job
            WHERE user_id=? ORDER BY created_at DESC LIMIT 10
            """,userId));
        result.put("receipts",jdbc.queryForList("""
            SELECT r.job_id,r.target,r.state,r.attempts,r.error_code,r.updated_at
            FROM profile_cleanup_receipt r JOIN profile_cleanup_job j ON j.job_id=r.job_id
            WHERE j.user_id=? ORDER BY j.created_at DESC,r.target LIMIT 50
            """,userId));
        result.put("audit",jdbc.queryForList("""
            SELECT a.action_id,a.actor_user_id,a.reason_code,a.job_id,a.created_at
            FROM profile_admin_cleanup_audit a WHERE a.target_user_id=?
            ORDER BY a.created_at DESC LIMIT 20
            """,userId));
        return result;
    }
}
