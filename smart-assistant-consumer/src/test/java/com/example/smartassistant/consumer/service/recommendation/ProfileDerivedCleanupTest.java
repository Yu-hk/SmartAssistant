package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileDerivedCleanupTest {
    @Test void acceptsOnlyMinimizedKnownDiagnosticShape() throws Exception {
        var json=new ObjectMapper();
        var graph=json.readTree("""
            {"requestId":"fixture","question":"","modelName":"deepseek-v4-flash","modelTier":"light",
             "questionChars":18,"status":"completed","startedAt":1,"completedAt":2,
             "nodes":[{"id":"task_1","label":"商品服务","agent":"product","type":"agent","status":"completed",
             "summary":"处理完成","dependsOn":[],"elapsedMs":12}],"edges":[]}
            """);
        assertTrue(ProfileDerivedCleanup.metadataOnly(graph));
        ((com.fasterxml.jackson.databind.node.ObjectNode)graph).put("question","private profile");
        assertFalse(ProfileDerivedCleanup.metadataOnly(graph));
        ((com.fasterxml.jackson.databind.node.ObjectNode)graph).put("question","");
        ((com.fasterxml.jackson.databind.node.ObjectNode)graph.path("nodes").get(0)).put("summary","private preference");
        assertFalse(ProfileDerivedCleanup.metadataOnly(graph));
        assertFalse(ProfileDerivedCleanup.metadataOnly(json.readTree("{}")));
        assertFalse(ProfileDerivedCleanup.metadataOnly(json.readTree("null")));
    }
}
