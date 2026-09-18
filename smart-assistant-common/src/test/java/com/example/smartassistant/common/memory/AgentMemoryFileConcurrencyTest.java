package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AgentMemoryFileConcurrencyTest {
    @TempDir Path root;

    private Process worker(String mode) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // A small explicit classpath avoids Windows' command-line and @file encoding limits.
        String classpath = String.join(java.io.File.pathSeparator, location(AgentMemoryProcessWorker.class),
                location(AgentMemoryService.class), location(org.slf4j.LoggerFactory.class));
        return new ProcessBuilder(javaExecutable, "-cp", classpath, AgentMemoryProcessWorker.class.getName(), root.toString(), mode)
                .redirectErrorStream(true).redirectOutput(root.resolve(mode + ".log").toFile()).start();
    }
    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private void finished(Process process) throws Exception {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Child JVM exceeded bounded test timeout");
        assertEquals(0, process.exitValue());
    }
    @Test void anotherProcessHoldingStableLockPreventsUnprotectedOverwrite() throws Exception {
        Path folder = Files.createDirectories(root.resolve("fixture"));
        Path file = folder.resolve("product-memory.md");
        Files.writeString(file, "- original: kept\n");
        try (var channel = FileChannel.open(folder.resolve("product-memory.md.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            Process child = worker("single");
            try { finished(child); } finally { child.destroyForcibly(); }
            assertEquals("- original: kept\n", Files.readString(file));
        }
        var service = new AgentMemoryService(root.toString());
        service.save("product", "fixture", "after", "released");
        assertEquals("released", service.get("product", "fixture", "after"));
    }
    @Test void independentJvmsDoNotLoseDistinctKeysOrResurrectDeletedKeys() throws Exception {
        var service = new AgentMemoryService(root.toString());
        for (String prefix : new String[]{"a", "b"})
            for (int i = 0; i < 40; i++) service.save("product", "fixture", "old" + prefix + i, "old");
        Process a = worker("a");
        Process b = worker("b");
        try {
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!Files.exists(root.resolve("a.ready")) || !Files.exists(root.resolve("b.ready"))) {
                assertTrue(System.nanoTime() < deadline, "Child JVM did not reach barrier");
                Thread.sleep(10);
            }
            Files.createFile(root.resolve("start"));
            finished(a); finished(b);
            for (String prefix : new String[]{"a", "b"}) {
                for (int i = 0; i < 40; i++) {
                    assertEquals("value" + i, service.get("product", "fixture", prefix + i), prefix + i);
                    assertNull(service.get("product", "fixture", "old" + prefix + i), "deleted old" + prefix + i);
                }
            }
        } finally { a.destroyForcibly(); b.destroyForcibly(); }
    }
    @Test void corruptUtf8IsNeverTreatedAsAnEmptyFile() throws Exception {
        Path file = Files.createDirectories(root.resolve("fixture")).resolve("product-memory.md");
        byte[] corrupt = {(byte) 0xc3, (byte) 0x28};
        Files.write(file, corrupt);
        var service = new AgentMemoryService(root.toString());
        service.save("product", "fixture", "newkey", "value");
        service.delete("product", "fixture", "oldkey");
        assertArrayEquals(corrupt, Files.readAllBytes(file));
        assertNull(service.get("product", "fixture", "newkey"));
    }
    @Test void terminatedProcessReleasesLockWithoutDeletingItsSidecar() throws Exception {
        Process child = worker("hold");
        try {
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!Files.exists(root.resolve("hold.ready"))) {
                assertTrue(child.isAlive(), "Lock worker failed");
                assertTrue(System.nanoTime() < deadline, "Lock worker timed out");
                Thread.sleep(10);
            }
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            var service = new AgentMemoryService(root.toString());
            service.save("product", "fixture", "after", "released");
            assertEquals("released", service.get("product", "fixture", "after"));
            assertTrue(Files.exists(root.resolve("fixture/product-memory.md.lock")));
        } finally { child.destroyForcibly(); }
    }
    @Test void sameJvmLockContentionHasBoundedWaitAndDoesNotWrite() throws Exception {
        Path folder = Files.createDirectories(root.resolve("fixture"));
        try (var channel = FileChannel.open(folder.resolve("product-memory.md.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            long start = System.nanoTime();
            assertFalse(new AgentMemoryService(root.toString()).trySave("product", "fixture", "key", "value"));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= MemoryFileTransaction.LOCK_WAIT_MILLIS);
            assertTrue(elapsed < 2000, "Lock must not block indefinitely");
            assertFalse(Files.exists(folder.resolve("product-memory.md")));
        }
    }
    @Test void interruptedWritePreservesInterruptAndExistingContents() {
        var service = new AgentMemoryService(root.toString());
        service.save("product", "fixture", "key", "old");
        Thread.currentThread().interrupt();
        try {
            service.save("product", "fixture", "key", "new");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals("old", service.get("product", "fixture", "key"));
    }
    @Test void oversizedReplacementKeepsOriginalAndLeavesNoTemporaryFile() throws Exception {
        Path file = Files.createDirectories(root.resolve("fixture")).resolve("product-memory.md");
        Files.writeString(file, "original");
        assertThrows(java.io.IOException.class, () -> MemoryFileTransaction.mutate(file,
                () -> MemoryFileTransaction.replace(file, new byte[MemoryFileTransaction.MAX_BYTES + 1])));
        assertEquals("original", Files.readString(file));
        try (var files = Files.list(file.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
        assertTrue(Files.exists(file.resolveSibling("product-memory.md.lock")));
    }
}
