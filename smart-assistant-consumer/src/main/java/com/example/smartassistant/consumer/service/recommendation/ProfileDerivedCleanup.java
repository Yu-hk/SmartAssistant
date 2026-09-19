package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Original questions/replies stay intact. Legacy graph body copies must expire
 * before success; ambiguous shared session keys are never deleted on a user's behalf. */
@Service
public class ProfileDerivedCleanup {
    private final JdbcTemplate jdbc;private final StringRedisTemplate redis;private final ObjectMapper json;
    public ProfileDerivedCleanup(JdbcTemplate jdbc,StringRedisTemplate redis,ObjectMapper json){this.jdbc=jdbc;this.redis=redis;this.json=json;}
    public void clean(long user) {
        if(user<=0) throw new IllegalArgumentException("Owner required");
        var sessions=jdbc.queryForList("SELECT DISTINCT session_id FROM routing_call_log WHERE user_id=? AND session_id IS NOT NULL LIMIT 501",String.class,user);
        if(sessions.size()>500) throw new IllegalStateException("Diagnostic inventory limit");
        for(String session:sessions) {
            if(!session.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalStateException("Unknown diagnostic identity");
            String key="routing:execution-graph:"+session;
            Long bytes=redis.opsForValue().size(key);
            if(bytes!=null && bytes>262144) throw new IllegalStateException("Diagnostic size limit");
            String value=redis.opsForValue().get(key);
            if(value==null) continue;
            try {
                if(!metadataOnly(json.readTree(value))) throw new IllegalStateException("Legacy diagnostics awaiting expiry");
            } catch(java.io.IOException invalid){throw new IllegalStateException("Diagnostic inventory incomplete");}
        }
        jdbc.update("UPDATE routing_call_log SET llm_received_question=NULL WHERE user_id=?",user);
    }
    static boolean metadataOnly(JsonNode graph) {
        if(graph==null || !graph.isObject() || !fields(graph,Set.of("requestId","question","modelName","modelTier","questionChars","status","startedAt","completedAt","nodes","edges"))
                || !graph.path("question").isTextual() || !graph.path("question").asText().isEmpty()
                || !identity(graph.path("requestId")) || !model(graph.path("modelName")) || !model(graph.path("modelTier"))
                || !graph.path("questionChars").isIntegralNumber() || !graph.path("startedAt").isIntegralNumber()
                || !(graph.path("completedAt").isNull() || graph.path("completedAt").isIntegralNumber())
                || !Set.of("running","completed","failed").contains(graph.path("status").asText())
                || !graph.path("nodes").isArray() || !graph.path("edges").isArray()
                || graph.path("nodes").size()>100 || graph.path("edges").size()>1000) return false;
        for(JsonNode node:graph.path("nodes")) {
            if(!node.isObject() || !fields(node,Set.of("id","label","agent","type","status","summary","dependsOn","elapsedMs"))
                    || !identity(node.path("id")) || !node.path("dependsOn").isArray()
                    || !(node.path("elapsedMs").isNull() || node.path("elapsedMs").isIntegralNumber())
                    || !Set.of("planner","agent","merger").contains(node.path("type").asText())
                    || !Set.of("pending","running","completed","failed","skipped").contains(node.path("status").asText())
                    || !Set.of("","product","order","knowledge","general","router-fallback").contains(node.path("agent").asText())
                    || !Set.of("意图拆解与节点分配","汇总 Agent 执行结果","商品服务","订单服务","知识检索","业务处理节点").contains(node.path("label").asText())
                    || !Set.of("处理完成","处理失败","未执行","等待处理","已完成执行结果汇总").contains(node.path("summary").asText())) return false;
            for(JsonNode parent:node.path("dependsOn")) if(!identity(parent)) return false;
        }
        for(JsonNode edge:graph.path("edges"))
            if(!edge.isObject() || !fields(edge,Set.of("from","to","label")) || !identity(edge.path("from")) || !identity(edge.path("to")) || !"依赖".equals(edge.path("label").asText())) return false;
        return true;
    }
    private static boolean fields(JsonNode node,Set<String> allowed) {
        var names=node.fieldNames();while(names.hasNext()) if(!allowed.contains(names.next())) return false;return true;
    }
    private static boolean identity(JsonNode node){return node.isTextual() && node.asText().matches("[a-zA-Z0-9_-]{1,128}");}
    private static boolean model(JsonNode node){return node.isTextual() && node.asText().matches("[a-zA-Z0-9_.:/ -]{0,128}");}
}
