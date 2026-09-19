package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileLegacyFilesTest {
    @TempDir Path temporary;
    Path put(String name) throws Exception {
        Path file=temporary.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,"synthetic");return file;
    }
    @Test void deletesOnlyOwnersAllowlistedDerivedFilesAndKeepsOriginalDocuments() throws Exception {
        put("42/preferences.json");put("42/memories.json");put("42/order-memory.md");put("42/memories/2026-09-19_session-one.md");
        Path document=put("42/documents/original.md"),other=put("43/preferences.json"),chat=put("42/chat.json");
        var adapter=new ProfileLegacyFiles(temporary.toString(),true);adapter.clean(42);adapter.clean(42);
        assertFalse(Files.exists(temporary.resolve("42/preferences.json")));assertFalse(Files.exists(temporary.resolve("42/memories.json")));
        assertFalse(Files.exists(temporary.resolve("42/order-memory.md")));assertFalse(Files.exists(temporary.resolve("42/memories/2026-09-19_session-one.md")));
        assertTrue(Files.exists(document));assertTrue(Files.exists(other));assertTrue(Files.exists(chat));
    }
    @Test void unknownSummaryOrMemoryAgentStopsBeforeDeletingAnyFile() throws Exception {
        Path keep=put("42/preferences.json"),unknown=put("42/unknown-memory.md");
        var adapter=new ProfileLegacyFiles(temporary.toString(),true);
        assertThrows(IllegalStateException.class,()->adapter.clean(42));assertTrue(Files.exists(keep));
        Files.delete(unknown);put("42/memories/original.txt");
        assertThrows(IllegalStateException.class,()->adapter.clean(42));assertTrue(Files.exists(keep));
    }
    @Test void disabledInvalidOwnerAndWrongTypeNeverDelete() throws Exception {
        Path keep=put("42/preferences.json");var adapter=new ProfileLegacyFiles(temporary.toString(),false);
        assertThrows(IllegalStateException.class,()->adapter.clean(42));assertTrue(Files.exists(keep));
        assertThrows(IllegalStateException.class,()->new ProfileLegacyFiles(temporary.toString(),true).clean(0));
        Files.createDirectories(temporary.resolve("42/memories.json"));
        assertThrows(IllegalStateException.class,()->new ProfileLegacyFiles(temporary.toString(),true).clean(42));assertTrue(Files.exists(keep));
    }
    @Test @EnabledOnOs(OS.LINUX) void symlinksCannotRedirectCleanupToAnotherOwner() throws Exception {
        Path other=put("43/preferences.json");Files.createSymbolicLink(temporary.resolve("42"),temporary.resolve("43"));
        assertThrows(IllegalStateException.class,()->new ProfileLegacyFiles(temporary.toString(),true).clean(42));assertTrue(Files.exists(other));
    }
}
