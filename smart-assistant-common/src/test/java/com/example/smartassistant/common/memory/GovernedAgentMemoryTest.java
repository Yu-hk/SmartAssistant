package com.example.smartassistant.common.memory;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GovernedAgentMemoryTest {
    @TempDir Path root;
    @Test void springWiringSelectsGovernedConstructor() {
        var store=mock(GovernedAgentMemoryStore.class);
        when(store.load("order","42")).thenReturn(Map.of("replyStyle","简洁 || 2026-09-19"));
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(GovernedAgentMemoryStore.class,()->store);
            context.register(AgentMemoryService.class);context.refresh();
            assertTrue(context.getBean(AgentMemoryService.class).getAllFormatted("order","42").contains("POSTGRES_AGENT"));
            verify(store).load("order","42");
        }
    }
    @Test void productionConstructorNeverReadsOrWritesLegacyFiles() throws Exception {
        new AgentMemoryService(root.toString()).save("order","42","preferBrand","legacy");
        byte[] original=Files.readAllBytes(root.resolve("42/order-memory.md"));
        var store=mock(GovernedAgentMemoryStore.class);
        when(store.load("order","42")).thenThrow(new GovernedAgentMemoryStore.Rejected());
        var service=new AgentMemoryService(root.toString(),store);
        assertEquals("",service.getAllFormatted("order","42"));
        assertNull(service.get("order","42","preferBrand"));
        assertFalse(service.trySave("order","42","preferBrand","changed"));
        assertFalse(service.tryDelete("order","42","preferBrand"));
        assertArrayEquals(original,Files.readAllBytes(root.resolve("42/order-memory.md")));
    }
    @Test void governedFactsCarryTheirOwnProvenance() {
        var store=mock(GovernedAgentMemoryStore.class);
        when(store.load("order","42")).thenReturn(Map.of("preferBrand","华为 || 2026-09-19"));
        String result=new AgentMemoryService(root.toString(),store).getAllFormatted("order","42");
        assertTrue(result.contains("POSTGRES_AGENT")); assertFalse(result.contains("AGENT_FILE"));
    }
    @Test void missingAdmissionNeverCallsModel() {
        var store=mock(GovernedAgentMemoryStore.class);
        when(store.admission("order","42",null,"喜欢简洁回答")).thenThrow(new GovernedAgentMemoryStore.Rejected());
        var ai=mock(AiChatService.class);
        var extractor=extractor(store,ai);
        try { extractor.extractFromConversation("order","42","喜欢简洁回答","ok"); verifyNoInteractions(ai); }
        finally { extractor.close(); }
    }
    @Test void capturedGenerationIsUsedForWholeBatchAndNotRecaptured() {
        var store=mock(GovernedAgentMemoryStore.class); var ai=mock(AiChatService.class);
        when(store.admission("order","42","request","喜欢华为，简洁回答")).thenReturn(3L);
        when(ai.entity(any(),anyString(),any(org.springframework.core.ParameterizedTypeReference.class))).thenAnswer(call->{
            when(store.admission("order","42","request","喜欢华为，简洁回答")).thenReturn(4L);
            return Map.of("preferBrand","华为","replyStyle","简洁");
        });
        var extractor=extractor(store,ai);
        try {
            extractor.extractFromConversation("order","42","喜欢华为，简洁回答","ok","request");
            verify(store,times(1)).admission("order","42","request","喜欢华为，简洁回答");
            verify(store).save("order","42",3L,Map.of("preferBrand","华为","replyStyle","简洁"));
        } finally { extractor.close(); }
    }
    @Test void invalidatedWriteIsDiscardedWithoutLegacyFallback() {
        var store=mock(GovernedAgentMemoryStore.class); var ai=mock(AiChatService.class);
        when(store.admission("order","42","request","喜欢华为")).thenReturn(2L);
        when(ai.entity(any(),anyString(),any(org.springframework.core.ParameterizedTypeReference.class))).thenReturn(Map.of("preferBrand","华为"));
        doThrow(new GovernedAgentMemoryStore.Rejected()).when(store).save(anyString(),anyString(),anyLong(),anyMap());
        var extractor=extractor(store,ai);
        try {
            assertDoesNotThrow(()->extractor.extractFromConversation("order","42","喜欢华为","ok","request"));
            assertFalse(Files.exists(root.resolve("42/order-memory.md")));
            verify(store,times(1)).save(anyString(),anyString(),eq(2L),anyMap());
        } finally { extractor.close(); }
    }
    @Test void optionalReadsHaveBoundedWaitEvenIfDatabaseIgnoresInterruption() throws Exception {
        var store=mock(GovernedAgentMemoryStore.class);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(store.load("order","42")).thenAnswer(call->{
            while(release.getCount()>0) {try {release.await();}catch(InterruptedException ignored){}}
            return Map.of("replyStyle","late || 2026-09-19");
        });
        var service=new AgentMemoryService(root.toString(),store);
        try {
            long start=System.nanoTime();
            assertEquals("",service.getAllFormatted("order","42"));
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);
        } finally {release.countDown();service.close();}
    }
    @Test void extractionBulkheadRejectsExtraWorkWithoutRunningOnCaller() throws Exception {
        var store=mock(GovernedAgentMemoryStore.class);var ai=mock(AiChatService.class);
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        when(ai.entity(any(),anyString(),any(org.springframework.core.ParameterizedTypeReference.class))).thenAnswer(call->{
            entered.countDown(); assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS));return Map.of();
        });
        var extractor=extractor(store,ai);
        try {
            extractor.extractAsync("order","42","question","ok","one");
            extractor.extractAsync("order","42","question","ok","two");
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
            extractor.extractAsync("order","42","question","ok","three");
            verify(store,never()).admission("order","42","three","question");
        } finally {release.countDown();extractor.close();}
    }
    @SuppressWarnings("unchecked") private MemoryExtractor extractor(GovernedAgentMemoryStore store,AiChatService ai) {
        ObjectProvider<ChatModel> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(ChatModel.class));
        return new MemoryExtractor(provider,new AgentMemoryService(root.toString(),store),ai);
    }
}
