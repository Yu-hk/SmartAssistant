package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileGovernanceTest {
    @TempDir Path root;
    @Test void rejectsPathTraversalAndInvalidKeys() throws Exception {
        var memory = new AgentMemoryService(root.resolve("users").toString());
        memory.save("../escape", "alice", "brand", "secret");
        memory.save("product", "../bob", "brand", "secret");
        memory.save("product", "alice", "brand\n- forged", "secret");
        assertFalse(Files.exists(root.resolve("users")));
    }
    @Test void storedMultilineValueCannotCreateAnotherRecord() {
        var memory = new AgentMemoryService(root.toString());
        memory.save("product", "alice", "brand", "A\n- hacked: yes || 2099-01-01");
        assertNull(memory.get("product", "alice", "hacked"));
        assertFalse(memory.get("product", "alice", "brand").contains("\n"));
        assertNull(memory.get("product", "bob", "brand"));
    }
    @Test void unknownAndFutureTimestampsNeverAppearFresh() throws Exception {
        Path folder = Files.createDirectories(root.resolve("alice"));
        Files.writeString(folder.resolve("product-memory.md"), "- brand: A\n- maxPrice: 100 || 2099-01-01\n");
        String view = new AgentMemoryService(root.toString()).getAllFormatted("product", "alice");
        assertTrue(view.contains("AGENT_FILE"));
        assertEquals(2, view.split("记录时间未知，使用前需确认", -1).length - 1);
        assertTrue(view.contains("冲突时忽略历史偏好"));
    }
    @Test void oversizedExistingFileIsNotOverwritten() throws Exception {
        Path file = Files.createDirectories(root.resolve("alice")).resolve("product-memory.md");
        Files.writeString(file, "x".repeat(262145));
        var memory = new AgentMemoryService(root.toString());
        memory.save("product", "alice", "brand", "new");
        memory.delete("product", "alice", "brand");
        assertEquals(262145, Files.size(file));
    }
    @Test void referenceBoundAndEmptyFallback() {
        assertEquals("", ProfileContextPolicy.reference(ProfileContextPolicy.Source.REDIS_ENTITY, null, null, ""));
        String view = ProfileContextPolicy.reference(ProfileContextPolicy.Source.REDIS_ENTITY, null, null, "x".repeat(10000));
        assertTrue(view.contains("记录时间: 未知")); assertTrue(view.length() < 7000);
    }
    @Test void entityProjectionUsesExplicitLegacySourceWithoutWrites() {
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var hashes = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("user:profile:42")).thenReturn(java.util.Map.of("brand", "A"));
        String view = new EntityProfileService(redis).formatProfile(42L);
        assertTrue(view.contains("REDIS_ENTITY")); assertTrue(view.contains("TTL 不等于事实更新时间"));
        verify(hashes).entries("user:profile:42"); verifyNoMoreInteractions(hashes);
    }
}
