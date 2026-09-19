package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual POSIX fsync/atomic rename tests; executed in Linux locally and on server. */
@EnabledOnOs(OS.LINUX)
class ProfileControlJournalTest {
    Path root;UUID source;ProfileControlJournal journal;
    @BeforeEach void setup() throws Exception {
        root=Files.createTempDirectory("profile-control-journal-test-").toRealPath();
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        source=UUID.randomUUID();write("source.pin","1|"+source+"\n");
        journal=new ProfileControlJournal(root,source);
    }
    @AfterEach void cleanup() throws Exception {
        assertTrue(root.getFileName().toString().startsWith("profile-control-journal-test-"));
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(),root.getParent());
        try(var paths=Files.walk(root)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    void write(String name,String body) throws Exception {
        Files.writeString(root.resolve(name),body);
        Files.setPosixFilePermissions(root.resolve(name),PosixFilePermissions.fromString("rw-------"));
    }
    ProfileControlJournal.Event event(long sequence,long user,long gen,boolean enabled) {
        return new ProfileControlJournal.Event(sequence,UUID.randomUUID(),source,user,gen,enabled);
    }
    String file(long sequence) { return String.format(Locale.ROOT,"%020d.ctl",sequence); }
    @Test void durableIdempotentAppendSurvivesObjectRestart() throws Exception {
        var a=event(1,92001,1,false);var b=event(2,92001,2,true);
        assertEquals(1,journal.sync(List.of(a)));String original=Files.readString(root.resolve(file(1)));
        assertEquals(1,new ProfileControlJournal(root,source).sync(List.of(a)));
        assertEquals(2,journal.sync(List.of(a,b)));assertEquals(original,Files.readString(root.resolve(file(1))));
        assertTrue(Files.readString(root.resolve("HEAD")).startsWith("1|2|"));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve("HEAD")));
    }
    @Test void rollbackAndChangedHistoryCannotOverwriteIndependentRecords() throws Exception {
        var a=event(1,92001,1,false);journal.sync(List.of(a));
        assertThrows(Exception.class,()->journal.sync(List.of()));
        assertThrows(Exception.class,()->journal.sync(List.of(event(1,92001,1,false))));
        assertThrows(Exception.class,()->new ProfileControlJournal(root,UUID.randomUUID()).sync(List.of(a)));
    }
    @Test void missingSourceOrUnprivatePathsAreRejected() throws Exception {
        Files.delete(root.resolve("source.pin"));assertThrows(Exception.class,()->journal.sync(List.of()));
        write("source.pin","1|"+source+"\n");Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(Exception.class,()->journal.sync(List.of()));
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(root.resolve("source.pin"),PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(Exception.class,()->journal.sync(List.of()));
    }
    @Test void corruptedAcknowledgementRejectsBeforeAppending() throws Exception {
        var a=event(1,92001,1,false);journal.sync(List.of(a));write("HEAD","1|1|"+"0".repeat(64)+"\n");
        assertThrows(Exception.class,()->journal.sync(List.of(a,event(2,92002,1,false))));
        assertFalse(Files.exists(root.resolve(file(2))));
    }
    @Test void orphanTemporaryWriteIsNotAnAcknowledgementAndCanBeRetried() throws Exception {
        String temporary="pending-"+UUID.randomUUID()+".tmp";write(temporary,"unfinished");
        var a=event(1,92001,1,false);assertEquals(1,journal.sync(List.of(a)));
        assertFalse(Files.exists(root.resolve(temporary)));assertTrue(Files.exists(root.resolve("HEAD")));
    }
    @Test void symlinksHolesAndCorruptionFailClosed() throws Exception {
        var a=event(1,92001,1,false);var b=event(2,92002,1,false);journal.sync(List.of(a,b));
        Files.delete(root.resolve(file(1)));Files.createSymbolicLink(root.resolve(file(1)),root.resolve("source.pin"));
        assertThrows(Exception.class,()->journal.sync(List.of(a,b)));
        Files.delete(root.resolve(file(1)));assertThrows(Exception.class,()->journal.sync(List.of(a,b)));
        write(file(1),"corrupt");assertThrows(Exception.class,()->journal.sync(List.of(a,b)));
    }
    @Test void generationAndIdentityContractsAreStrict() {
        assertThrows(Exception.class,()->journal.sync(List.of(event(2,92001,1,false))));
        assertThrows(Exception.class,()->journal.sync(List.of(event(1,92001,2,false))));
        assertThrows(Exception.class,()->journal.sync(List.of(event(1,92001,1,true))));
        var a=event(1,92001,1,false);
        assertThrows(Exception.class,()->journal.sync(List.of(a,new ProfileControlJournal.Event(2,a.eventId(),source,92002,1,false))));
        assertThrows(Exception.class,()->journal.sync(List.of(a,event(2,92001,3,false))));
    }
    @Test void allReadersRejectDatabaseRollbackMissingPinAndDivergentHistory() throws Exception {
        var a=event(1,92001,1,false);journal.sync(List.of(a));
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("source_id",source,"last_sequence",1L));
        var row=new HashMap<String,Object>(Map.of("sequence",1L,"event_id",a.eventId(),"source_id",source,"user_id",92001L,"generation",1L,"analysis_enabled",false));
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(1L))).thenReturn(List.of(row));
        var guard=new com.example.smartassistant.common.memory.ProfileRecoveryGuard(jdbc,true,root,source);
        assertDoesNotThrow(guard::requireSafe);
        org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("source_id",source,"last_sequence",0L));
        assertThrows(com.example.smartassistant.common.memory.ProfileRecoveryGuard.Unavailable.class,guard::requireSafe);
        org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("source_id",source,"last_sequence",1L));
        row.put("event_id",UUID.randomUUID());assertThrows(com.example.smartassistant.common.memory.ProfileRecoveryGuard.Unavailable.class,guard::requireSafe);
        row.put("event_id",a.eventId());Files.delete(root.resolve("source.pin"));
        assertThrows(com.example.smartassistant.common.memory.ProfileRecoveryGuard.Unavailable.class,guard::requireSafe);
    }
    @Test void readGuardChecksUnacknowledgedPublishedSuffixAndHistoryHoles() throws Exception {
        journal.sync(List.of());String zero=Files.readString(root.resolve("HEAD"));
        var a=event(1,92001,1,false);var b=event(2,92002,1,false);journal.sync(List.of(a,b));write("HEAD",zero);
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("source_id",source,"last_sequence",0L));
        var guard=new com.example.smartassistant.common.memory.ProfileRecoveryGuard(jdbc,true,root,source);
        assertThrows(com.example.smartassistant.common.memory.ProfileRecoveryGuard.Unavailable.class,guard::requireSafe);
        org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("source_id",source,"last_sequence",2L));
        var rows=List.of(Map.<String,Object>of("sequence",1L,"event_id",a.eventId(),"source_id",source,"user_id",92001L,"generation",1L,"analysis_enabled",false),
                Map.<String,Object>of("sequence",2L,"event_id",b.eventId(),"source_id",source,"user_id",92002L,"generation",1L,"analysis_enabled",false));
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(2L))).thenReturn(rows);
        assertDoesNotThrow(guard::requireSafe);
        Files.delete(root.resolve(file(1)));assertThrows(com.example.smartassistant.common.memory.ProfileRecoveryGuard.Unavailable.class,guard::requireSafe);
    }
}
